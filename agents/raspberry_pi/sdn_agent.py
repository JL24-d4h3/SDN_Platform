#!/usr/bin/env python3
"""
═══════════════════════════════════════════════════════════════
 SDN Data Plane Agent — Raspberry Pi
═══════════════════════════════════════════════════════════════

Agente del plano de datos para Raspberry Pi (nodo CDN / almacenamiento).

Funciones:
 1. Conecta al broker MQTT por WiFi.
 2. Se suscribe a `dispositivo/{MAC}/comando`.
 3. Parsea comandos JSON del controlador SDN:
      - PREPARE_BT   → Enciende Bluetooth (rfkill unblock bluetooth)
      - SWITCH_WIFI   → Conecta a WiFi de alta velocidad (nmcli)
      - RELEASE_RADIO → Apaga la radio que fue activada
 4. Publica telemetría periódica a `dispositivo/{MAC}/metrics`.
 5. Se auto-registra en el controlador SDN.

Dependencias:
    pip install paho-mqtt

Ejecución:
    sudo python3 sdn_agent.py

    (sudo necesario para rfkill y bluetoothctl)

Hardware soportado:
    - Raspberry Pi 3B+  (WiFi 5 GHz + BT 4.2)
    - Raspberry Pi 4B   (WiFi 5 GHz + BT 5.0)
    - Raspberry Pi 5    (WiFi 5 GHz + BT 5.2 con Coded PHY)
    - Con dongle USB BT 5.0 para Long Range
"""

import json
import logging
import os
import re
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime

try:
    import paho.mqtt.client as mqtt
except ImportError:
    print("ERROR: paho-mqtt no instalado.")
    print("Ejecutar: pip install paho-mqtt")
    sys.exit(1)

import config

# ══════════════════════════════════════════════
#  Logging
# ══════════════════════════════════════════════

log = logging.getLogger("sdn-agent")
log.setLevel(getattr(logging, config.LOG_LEVEL, logging.INFO))

formatter = logging.Formatter(
    "%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S"
)

console_handler = logging.StreamHandler()
console_handler.setFormatter(formatter)
log.addHandler(console_handler)

if config.LOG_FILE:
    file_handler = logging.FileHandler(config.LOG_FILE)
    file_handler.setFormatter(formatter)
    log.addHandler(file_handler)


# ══════════════════════════════════════════════
#  Detección de MAC
# ══════════════════════════════════════════════

def get_device_mac() -> str:
    """Obtiene la MAC de la interfaz WiFi en formato AA:BB:CC:DD:EE:FF."""
    if config.DEVICE_MAC_OVERRIDE:
        return config.DEVICE_MAC_OVERRIDE.upper()

    try:
        path = f"/sys/class/net/{config.WIFI_INTERFACE}/address"
        with open(path, "r") as f:
            mac = f.read().strip().upper()
            log.info(f"MAC detectada ({config.WIFI_INTERFACE}): {mac}")
            return mac
    except FileNotFoundError:
        log.warning(f"Interfaz {config.WIFI_INTERFACE} no encontrada, generando MAC local")
        fake = "02:" + ":".join(f"{b:02X}" for b in uuid.uuid4().bytes[:5])
        return fake


# ══════════════════════════════════════════════
#  Control de radios — Capa de abstracción
# ══════════════════════════════════════════════

class RadioController:
    """
    Controla las interfaces de red de la Raspberry Pi.

    Usa rfkill para encender/apagar radios y nmcli o wpa_supplicant
    para conectar a redes WiFi específicas.
    """

    def __init__(self):
        self.bt_active = False
        self.wifi_data_active = False
        self.current_data_ssid = ""
        self._check_tools()

    def _check_tools(self):
        """Verifica que las herramientas necesarias estén disponibles."""
        tools = ["rfkill", "ip"]
        if config.NETWORK_MANAGER == "nmcli":
            tools.append("nmcli")

        for tool in tools:
            result = subprocess.run(
                ["which", tool], capture_output=True, text=True
            )
            if result.returncode != 0:
                log.warning(f"Herramienta '{tool}' no encontrada en PATH")
            else:
                log.debug(f"✓ {tool} disponible")

    def _run(self, cmd: list[str], check: bool = False) -> subprocess.CompletedProcess:
        """Ejecuta un comando del sistema y loguea el resultado."""
        cmd_str = " ".join(cmd)
        log.debug(f"Ejecutando: {cmd_str}")
        try:
            result = subprocess.run(
                cmd, capture_output=True, text=True, timeout=15
            )
            if result.returncode != 0 and result.stderr:
                log.warning(f"  stderr: {result.stderr.strip()}")
            return result
        except subprocess.TimeoutExpired:
            log.error(f"  Timeout ejecutando: {cmd_str}")
            return subprocess.CompletedProcess(cmd, 1, "", "timeout")
        except FileNotFoundError:
            log.error(f"  Comando no encontrado: {cmd[0]}")
            return subprocess.CompletedProcess(cmd, 1, "", "not found")

    # ── BLUETOOTH ──

    def prepare_bluetooth(self, reason: str):
        """
        PREPARE_BT — Activar Bluetooth.

        1. rfkill unblock bluetooth  (desbloquea la radio)
        2. bluetoothctl power on      (enciende el adaptador)
        3. bluetoothctl discoverable on (para que otros nodos lo encuentren)
        """
        log.info("──── PREPARE_BT ────────────────────────")
        log.info(f"  Razón: {reason}")

        if self.bt_active:
            log.info("  Bluetooth ya está activo.")
            return

        # Desbloquear radio
        self._run(["rfkill", "unblock", "bluetooth"])

        # Esperar a que el adaptador esté listo
        time.sleep(1)

        # Encender y hacer discoverable
        self._run(["bluetoothctl", "power", "on"])
        self._run(["bluetoothctl", "discoverable", "on"])
        self._run(["bluetoothctl", "pairable", "on"])

        self.bt_active = True
        log.info("  ✓ Bluetooth activado y discoverable")
        log.info("────────────────────────────────────────")

    # ── WiFi ──

    def switch_wifi(self, ssid: str, password: str, reason: str):
        """
        SWITCH_WIFI — Conectar a la red WiFi de alta velocidad.

        Usa nmcli para crear/activar una conexión a la red de datos.
        La red de control MQTT se mantiene (Raspberry Pi puede tener
        múltiples conexiones WiFi si tiene dos interfaces, o se
        reconecta la misma interfaz).
        """
        log.info("──── SWITCH_WIFI ───────────────────────")
        log.info(f"  Razón: {reason}")
        log.info(f"  SSID solicitado: {ssid}")

        if config.NETWORK_MANAGER == "nmcli":
            self._wifi_nmcli(ssid, password)
        else:
            self._wifi_rfkill_wpa(ssid, password)

        self.wifi_data_active = True
        self.current_data_ssid = ssid
        log.info("────────────────────────────────────────")

    def _wifi_nmcli(self, ssid: str, password: str):
        """Conectar WiFi usando NetworkManager (nmcli)."""
        # Verificar si ya hay una conexión guardada
        check = self._run(["nmcli", "connection", "show", ssid])

        if check.returncode == 0:
            # Conexión existe, activarla
            log.info(f"  Conexión '{ssid}' encontrada, activando...")
            result = self._run(["nmcli", "connection", "up", ssid])
        else:
            # Crear nueva conexión
            log.info(f"  Creando nueva conexión para '{ssid}'...")
            result = self._run([
                "nmcli", "device", "wifi", "connect", ssid,
                "password", password,
                "ifname", config.WIFI_INTERFACE
            ])

        if result.returncode == 0:
            log.info(f"  ✓ Conectado a WiFi: {ssid}")
            # Obtener IP asignada
            ip_result = self._run([
                "nmcli", "-g", "IP4.ADDRESS",
                "device", "show", config.WIFI_INTERFACE
            ])
            if ip_result.stdout.strip():
                log.info(f"    IP: {ip_result.stdout.strip()}")
        else:
            log.error(f"  ✗ Error conectando a {ssid}: {result.stderr}")

    def _wifi_rfkill_wpa(self, ssid: str, password: str):
        """Conectar WiFi usando rfkill + wpa_supplicant (modo básico)."""
        self._run(["rfkill", "unblock", "wifi"])
        log.info(f"  ✓ WiFi desbloqueada (rfkill). Conectar manualmente a {ssid}")
        # En modo rfkill puro, la conexión real requiere wpa_supplicant
        # o que el usuario configure wpa_supplicant.conf manualmente.

    # ── RELEASE ──

    def release_radio(self, reason: str):
        """
        RELEASE_RADIO — Apagar las radios de datos.

        - Bluetooth: rfkill block bluetooth
        - WiFi de datos: desconectar la red de datos (no la de control)

        NOTA: Si la WiFi de datos y la de control son la misma interfaz,
        NO se apaga WiFi (se perdería MQTT). Solo se marca como liberada.
        """
        log.info("──── RELEASE_RADIO ─────────────────────")
        log.info(f"  Razón: {reason}")

        if self.bt_active:
            self._run(["bluetoothctl", "discoverable", "off"])
            self._run(["bluetoothctl", "power", "off"])
            self._run(["rfkill", "block", "bluetooth"])
            self.bt_active = False
            log.info("  ✓ Bluetooth apagado")

        if self.wifi_data_active and self.current_data_ssid:
            # Si la red de datos es diferente a la actual, desconectar
            current = self._get_current_ssid()
            if current == self.current_data_ssid:
                log.info(f"  ⚠ WiFi de datos ({self.current_data_ssid}) es la "
                         f"conexión actual. Manteniendo para MQTT.")
            else:
                if config.NETWORK_MANAGER == "nmcli":
                    self._run(["nmcli", "connection", "down", self.current_data_ssid])
                    log.info(f"  ✓ Desconectado de WiFi de datos: {self.current_data_ssid}")

            self.wifi_data_active = False
            self.current_data_ssid = ""

        log.info("  Estado: radios de datos liberadas")
        log.info("────────────────────────────────────────")

    # ── Utilidades ──

    def _get_current_ssid(self) -> str:
        """Obtiene el SSID actual al que estamos conectados."""
        result = self._run([
            "nmcli", "-t", "-f", "active,ssid",
            "device", "wifi"
        ])
        for line in result.stdout.strip().split("\n"):
            if line.startswith("yes:") or line.startswith("sí:"):
                return line.split(":", 1)[1] if ":" in line else ""
        return ""

    def get_wifi_rssi(self) -> int:
        """Obtiene el RSSI de la conexión WiFi actual."""
        result = self._run([
            "nmcli", "-t", "-f", "IN-USE,SIGNAL",
            "device", "wifi", "list"
        ])
        for line in result.stdout.strip().split("\n"):
            if line.startswith("*:"):
                try:
                    # nmcli reporta señal como porcentaje (0-100)
                    signal_pct = int(line.split(":")[1])
                    # Convertir a dBm aproximado: -30 (excelente) a -90 (malo)
                    return int(-30 - (100 - signal_pct) * 0.6)
                except (ValueError, IndexError):
                    pass

        # Fallback: leer desde /proc
        try:
            with open("/proc/net/wireless", "r") as f:
                for line in f:
                    if config.WIFI_INTERFACE in line:
                        parts = line.split()
                        return int(float(parts[3]))
        except (FileNotFoundError, IndexError, ValueError):
            pass

        return -99  # Desconocido

    def get_ip_address(self) -> str:
        """Obtiene la IP de la interfaz WiFi."""
        result = self._run([
            "ip", "-4", "-o", "addr", "show", config.WIFI_INTERFACE
        ])
        match = re.search(r"inet\s+(\d+\.\d+\.\d+\.\d+)", result.stdout)
        return match.group(1) if match else "0.0.0.0"

    def get_active_technology(self) -> str:
        """Retorna cuál radio está activa."""
        if self.bt_active and self.wifi_data_active:
            return "wifi+bluetooth"
        elif self.bt_active:
            return "bluetooth"
        elif self.wifi_data_active:
            return "wifi_data"
        else:
            return "wifi"  # WiFi de control siempre activa


# ══════════════════════════════════════════════
#  Agente MQTT
# ══════════════════════════════════════════════

class SdnAgent:
    """
    Agente SDN del plano de datos para Raspberry Pi.
    """

    def __init__(self):
        self.device_mac = get_device_mac()
        self.radio = RadioController()
        self.client = mqtt.Client(
            client_id=config.MQTT_CLIENT_ID,
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2
        )

        # Tópicos
        self.topic_comando  = f"dispositivo/{self.device_mac}/comando"
        self.topic_metrics  = f"dispositivo/{self.device_mac}/metrics"
        self.topic_registro = f"dispositivo/{self.device_mac}/registro"

        # Callbacks MQTT
        self.client.on_connect    = self._on_connect
        self.client.on_disconnect = self._on_disconnect
        self.client.on_message    = self._on_message

        self._running = False

    def start(self):
        """Inicia el agente: conecta MQTT y publica registro."""
        log.info("═══════════════════════════════════════════════")
        log.info("  SDN Data Plane Agent — Raspberry Pi")
        log.info("═══════════════════════════════════════════════")
        log.info(f"  MAC:     {self.device_mac}")
        log.info(f"  Nombre:  {config.DEVICE_NAME}")
        log.info(f"  Broker:  {config.MQTT_BROKER_IP}:{config.MQTT_BROKER_PORT}")
        log.info(f"  Comando: {self.topic_comando}")
        log.info(f"  Metrics: {self.topic_metrics}")
        log.info("═══════════════════════════════════════════════")

        # Conectar al broker
        try:
            self.client.connect(
                config.MQTT_BROKER_IP,
                config.MQTT_BROKER_PORT,
                keepalive=60
            )
        except ConnectionRefusedError:
            log.error(f"No se pudo conectar al broker {config.MQTT_BROKER_IP}:{config.MQTT_BROKER_PORT}")
            log.error("Verificar que Mosquitto esté corriendo y accesible en la red.")
            sys.exit(1)
        except OSError as e:
            log.error(f"Error de red: {e}")
            sys.exit(1)

        self._running = True

        # Hilo de telemetría
        telemetry_thread = threading.Thread(
            target=self._telemetry_loop,
            daemon=True,
            name="telemetry"
        )
        telemetry_thread.start()

        # Loop principal MQTT (bloqueante)
        log.info("Agente listo. Esperando comandos...")
        try:
            self.client.loop_forever()
        except KeyboardInterrupt:
            log.info("\nApagando agente...")
            self._running = False
            self.radio.release_radio("Agente detenido por el usuario")
            self.client.disconnect()
            log.info("Agente detenido.")

    # ── Callbacks MQTT ──

    def _on_connect(self, client, userdata, flags, reason_code, properties):
        """Callback cuando se conecta al broker."""
        if reason_code == 0:
            log.info("✓ Conectado al broker MQTT")
            # Suscribirse al tópico de comandos
            client.subscribe(self.topic_comando, qos=1)
            log.info(f"✓ Suscrito a: {self.topic_comando}")
            # Auto-registrarse
            self._publish_registration()
        else:
            log.error(f"Error de conexión MQTT, código: {reason_code}")

    def _on_disconnect(self, client, userdata, flags, reason_code, properties):
        """Callback cuando se pierde la conexión."""
        log.warning(f"Desconectado del broker (rc={reason_code}). Reconectando...")

    def _on_message(self, client, userdata, msg):
        """Callback cuando se recibe un mensaje MQTT."""
        try:
            payload = msg.payload.decode("utf-8")
            log.info("")
            log.info("╔═══════════════════════════════════════════")
            log.info("║ COMANDO RECIBIDO")
            log.info(f"║ Tópico:  {msg.topic}")
            log.info(f"║ Payload: {payload}")
            log.info("╚═══════════════════════════════════════════")

            self._handle_command(payload)

        except Exception as e:
            log.error(f"Error procesando mensaje: {e}", exc_info=True)

    # ── Interpretación de comandos ──

    def _handle_command(self, payload: str):
        """
        Parsea el JSON del controlador y ejecuta la acción.

        Formato esperado:
        {
            "sessionId": "abc12345",
            "action": "PREPARE_BT" | "SWITCH_WIFI" | "RELEASE_RADIO",
            "ssid": "SDN_HIGH_SPEED",       // solo en SWITCH_WIFI
            "password": "sdn_secure_pass",   // solo en SWITCH_WIFI
            "reason": "Sesión abc12345: ..."
        }
        """
        try:
            cmd = json.loads(payload)
        except json.JSONDecodeError as e:
            log.error(f"JSON inválido: {e}")
            return

        action     = cmd.get("action", "UNKNOWN")
        session_id = cmd.get("sessionId", "???")
        reason     = cmd.get("reason", "")

        log.info(f"Acción: {action} | Sesión: {session_id}")

        if action == "PREPARE_BT":
            self.radio.prepare_bluetooth(reason)

        elif action == "SWITCH_WIFI":
            ssid = cmd.get("ssid", "")
            password = cmd.get("password", "")
            self.radio.switch_wifi(ssid, password, reason)

        elif action == "RELEASE_RADIO":
            self.radio.release_radio(reason)

        else:
            log.warning(f"⚠ Acción desconocida: {action}")

    # ── Telemetría ──

    def _telemetry_loop(self):
        """Hilo que publica telemetría periódica."""
        # Esperar un poco para que la conexión MQTT se establezca
        time.sleep(3)

        while self._running:
            try:
                self._publish_telemetry()
            except Exception as e:
                log.error(f"Error en telemetría: {e}")

            time.sleep(config.TELEMETRY_INTERVAL)

    def _publish_telemetry(self):
        """
        Publica métricas al tópico `dispositivo/{MAC}/metrics`.

        Formato:
        {
            "mac": "DC:A6:32:XX:XX:XX",
            "rssi": -45,
            "technology": "wifi",
            "batteryLevel": 100,
            "ipAddress": "192.168.18.30"
        }
        """
        metrics = {
            "mac":          self.device_mac,
            "rssi":         self.radio.get_wifi_rssi(),
            "technology":   self.radio.get_active_technology(),
            "batteryLevel": self._get_battery_level(),
            "ipAddress":    self.radio.get_ip_address()
        }

        payload = json.dumps(metrics)
        result = self.client.publish(self.topic_metrics, payload, qos=0)

        rssi = metrics["rssi"]
        tech = metrics["technology"]
        status = "publicado" if result.rc == mqtt.MQTT_ERR_SUCCESS else "ERROR"
        log.info(f"[Telemetría] RSSI={rssi} dBm, Radio={tech} → {status}")

    def _publish_registration(self):
        """
        Publica registro al tópico `dispositivo/{MAC}/registro`.

        Formato:
        {
            "mac": "DC:A6:32:XX:XX:XX",
            "name": "RaspberryPi-CDN-01",
            "deviceType": "LAPTOP",
            "ipAddress": "192.168.18.30"
        }
        """
        registration = {
            "mac":        self.device_mac,
            "name":       config.DEVICE_NAME,
            "deviceType": config.DEVICE_TYPE,
            "ipAddress":  self.radio.get_ip_address()
        }

        payload = json.dumps(registration)
        result = self.client.publish(self.topic_registro, payload, qos=1)

        status = "publicado" if result.rc == mqtt.MQTT_ERR_SUCCESS else "ERROR"
        log.info(f"[Registro] {self.device_mac} como {config.DEVICE_NAME} → {status}")

    def _get_battery_level(self) -> int:
        """
        Obtiene nivel de batería.
        RPi con fuente de poder = 100%.
        Si tiene UPS HAT, lee de /sys/class/power_supply/.
        """
        try:
            # Intentar leer batería (para RPi con UPS HAT)
            for supply in os.listdir("/sys/class/power_supply/"):
                cap_file = f"/sys/class/power_supply/{supply}/capacity"
                if os.path.exists(cap_file):
                    with open(cap_file, "r") as f:
                        return int(f.read().strip())
        except (OSError, ValueError):
            pass

        return 100  # Con fuente de poder = siempre 100%


# ══════════════════════════════════════════════
#  Entry point
# ══════════════════════════════════════════════

if __name__ == "__main__":
    # Verificar permisos de root (necesario para rfkill y bluetoothctl)
    if os.geteuid() != 0:
        log.warning("⚠ Ejecutar como root (sudo) para control completo de radios.")
        log.warning("  Sin sudo, algunos comandos de rfkill/bluetoothctl fallarán.")
        log.warning("")

    agent = SdnAgent()
    agent.start()
