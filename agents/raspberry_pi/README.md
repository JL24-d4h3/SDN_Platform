# ═══════════════════════════════════════════════════════════════
#  SDN Data Plane Agent — Raspberry Pi — README
# ═══════════════════════════════════════════════════════════════

## Descripción

Agente del plano de datos para **Raspberry Pi**, actuando como
nodo de almacenamiento / CDN local en la red SDN.

Manipula las interfaces físicas de red (WiFi y Bluetooth) mediante
comandos del sistema operativo (`rfkill`, `bluetoothctl`, `nmcli`).

## Hardware compatible

| Modelo          | WiFi          | Bluetooth      | Notas                       |
|-----------------|---------------|----------------|-----------------------------|
| RPi 3B+        | 2.4/5 GHz    | BT 4.2 + BLE  | Suficiente para prototipo   |
| RPi 4B         | 2.4/5 GHz    | BT 5.0 + BLE  | Recomendado                 |
| RPi 5          | 2.4/5 GHz    | BT 5.2 + BLE  | Mejor rendimiento           |
| RPi + dongle BT 5.0 | —       | BT 5.0 Coded PHY | Para Long Range (~100m) |

### Recomendación para BT Long Range

Para conseguir Bluetooth de largo alcance en la RPi, un dongle USB
BT 5.0 como el **TP-Link UB500** (~$12 USD) soporta Coded PHY
(Long Range). Se conecta por USB y aparece como `hci1`.

## Instalación

```bash
# En la Raspberry Pi:

# 1. Instalar Python 3 (ya viene en Raspberry Pi OS)
python3 --version

# 2. Instalar dependencias
pip install -r requirements.txt

# 3. Editar configuración
nano config.py
# → Cambiar MQTT_BROKER_IP a la IP de tu laptop
# → Cambiar DEVICE_NAME si quieres

# 4. Ejecutar (con sudo para control de radios)
sudo python3 sdn_agent.py
```

## Configuración

Editar `config.py`:

```python
# IP de la laptop con Mosquitto
MQTT_BROKER_IP   = "192.168.18.20"

# Nombre del dispositivo (aparece en el controlador)
DEVICE_NAME      = "RaspberryPi-CDN-01"

# Interfaz WiFi (verificar con: ip link show)
WIFI_INTERFACE   = "wlan0"

# Método de control: "nmcli" para Desktop, "rfkill" para Lite
NETWORK_MANAGER  = "nmcli"
```

## Verificar funcionamiento

```bash
# 1. Verificar que Mosquitto es accesible desde la RPi
mosquitto_pub -h 192.168.18.20 -t "test" -m "hola"

# 2. Ejecutar agente
sudo python3 sdn_agent.py

# Debe verse:
# ═══════════════════════════════════════════════
#   SDN Data Plane Agent — Raspberry Pi
# ═══════════════════════════════════════════════
#   MAC:     DC:A6:32:XX:XX:XX
#   Nombre:  RaspberryPi-CDN-01
#   Broker:  192.168.18.20:1883
# ═══════════════════════════════════════════════
# ✓ Conectado al broker MQTT
# ✓ Suscrito a: dispositivo/DC:A6:32:XX:XX:XX/comando
# [Registro] DC:A6:32:XX:XX:XX como RaspberryPi-CDN-01 → publicado
```

## Probar comandos

Desde otra terminal (en la laptop o en la misma RPi):

```bash
MAC="DC:A6:32:XX:XX:XX"  # ← Reemplazar con la MAC real

# Activar Bluetooth
mosquitto_pub -h 192.168.18.20 -t "dispositivo/$MAC/comando" \
  -m '{"sessionId":"test01","action":"PREPARE_BT","reason":"prueba BT"}'

# Conectar a WiFi de alta velocidad
mosquitto_pub -h 192.168.18.20 -t "dispositivo/$MAC/comando" \
  -m '{"sessionId":"test02","action":"SWITCH_WIFI","ssid":"SDN_HIGH_SPEED","password":"pass123","reason":"prueba WiFi"}'

# Liberar radios
mosquitto_pub -h 192.168.18.20 -t "dispositivo/$MAC/comando" \
  -m '{"sessionId":"test03","action":"RELEASE_RADIO","reason":"liberar"}'
```

## Ejecutar como servicio systemd

Para que el agente se inicie automáticamente al encender la RPi:

```bash
sudo nano /etc/systemd/system/sdn-agent.service
```

Contenido:
```ini
[Unit]
Description=SDN Data Plane Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=/home/pi/sdn_agent
ExecStart=/usr/bin/python3 /home/pi/sdn_agent/sdn_agent.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Activar:
```bash
sudo systemctl daemon-reload
sudo systemctl enable sdn-agent.service
sudo systemctl start sdn-agent.service

# Ver logs en tiempo real
sudo journalctl -u sdn-agent.service -f
```

## Verificar que las radios funcionan

```bash
# Ver estado de radios
rfkill list

# Ver interfaz WiFi
ip link show wlan0

# Ver Bluetooth
bluetoothctl show

# Verificar señal WiFi
nmcli device wifi list
```
