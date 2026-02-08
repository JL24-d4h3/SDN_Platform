# ═══════════════════════════════════════════════════════════════
#  SDN Data Plane Agent — Raspberry Pi — Configuración
# ═══════════════════════════════════════════════════════════════

# ──────────────────────────────────────────────
#  Broker MQTT (IP de la laptop con Mosquitto)
# ──────────────────────────────────────────────
MQTT_BROKER_IP   = "192.168.18.20"
MQTT_BROKER_PORT = 1883
MQTT_CLIENT_ID   = "rpi-agent-01"

# ──────────────────────────────────────────────
#  Identificación del dispositivo
# ──────────────────────────────────────────────
# Si está vacío, se auto-detecta la MAC de la interfaz WiFi.
DEVICE_MAC_OVERRIDE = ""       # Ej: "DC:A6:32:XX:XX:XX"
DEVICE_NAME         = "RaspberryPi-CDN-01"
DEVICE_TYPE         = "LAPTOP"  # PHONE, LAPTOP, TABLET, IOT

# ──────────────────────────────────────────────
#  Interfaces de red (verificar con `ip link show`)
# ──────────────────────────────────────────────
WIFI_INTERFACE = "wlan0"       # Interfaz WiFi
BT_INTERFACE   = "hci0"        # Interfaz Bluetooth

# ──────────────────────────────────────────────
#  Intervalos (segundos)
# ──────────────────────────────────────────────
TELEMETRY_INTERVAL = 30        # Enviar métricas cada 30 seg

# ──────────────────────────────────────────────
#  Método de control de red
# ──────────────────────────────────────────────
# "nmcli"  → Usa NetworkManager (Raspberry Pi OS Desktop)
# "rfkill" → Usa rfkill directamente (Raspberry Pi OS Lite)
NETWORK_MANAGER = "nmcli"

# ──────────────────────────────────────────────
#  Logging
# ──────────────────────────────────────────────
LOG_LEVEL = "INFO"             # DEBUG, INFO, WARNING, ERROR
LOG_FILE  = ""                 # Vacío = solo consola. Ej: "/var/log/sdn_agent.log"
