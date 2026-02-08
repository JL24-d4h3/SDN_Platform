# ═══════════════════════════════════════════════════════════════
#  SDN Data Plane Agent — ESP32 — README
# ═══════════════════════════════════════════════════════════════

## Descripción

Agente del plano de datos para **ESP32 estándar** (ESP-WROOM-32).
Se conecta al broker MQTT del controlador SDN y ejecuta comandos
de activación/desactivación de radios BT/WiFi.

## Hardware compatible

- ESP32 DevKit V1 (ESP-WROOM-32)
- ESP32-S3 (con ajustes menores)
- Cualquier módulo con ESP32 + WiFi + BT Classic

## Capacidades de radio del ESP32 estándar

| Radio     | Estándar      | Alcance típico |
|-----------|---------------|----------------|
| WiFi      | 802.11 b/g/n  | ~50m interior  |
| Bluetooth | Classic + BLE 4.2 | ~10-30m   |

⚠️ **El ESP32 estándar NO soporta BT 5.0 Coded PHY (Long Range).**
Para BT de largo alcance, usar el LILYGO T-Lora C6 (ESP32-C6).

## Librerías necesarias

Instalar desde **Arduino IDE → Library Manager**:

1. **PubSubClient** (Nick O'Leary) — Cliente MQTT
2. **ArduinoJson** (Benoit Blanchon) v7+ — Parser JSON

## Configuración

Editar `config.h` con:

1. **SSID y password** de tu red WiFi (para señalización MQTT)
2. **IP del broker MQTT** (IP de tu laptop con Mosquitto)
3. **Nombre y tipo** del dispositivo

```c
#define WIFI_SSID_CONTROL    "MiRedWiFi"
#define WIFI_PASS_CONTROL    "MiPassword123"
#define MQTT_BROKER_IP       "192.168.18.20"
```

## Flashear

1. Abrir `sdn_agent_esp32.ino` en Arduino IDE
2. Seleccionar placa: **ESP32 Dev Module**
3. Seleccionar puerto COM del ESP32
4. Click en **Upload**

## Verificar funcionamiento

1. Abrir Serial Monitor (115200 baud)
2. Debe verse:
   ```
   Conectando a WiFi: MiRedWiFi
   WiFi conectado. IP: 192.168.18.X
   ✓ Conectado al broker MQTT
   ✓ Suscrito a: dispositivo/AA:BB:CC:DD:EE:FF/comando
   [Registro] AA:BB:CC:DD:EE:FF como ESP32-Nodo-01 → publicado
   ```
3. En el controlador, el dispositivo aparecerá en `GET /devices`

## Probar comandos desde terminal

```bash
# Enviar PREPARE_BT al ESP32
mosquitto_pub -h 192.168.18.20 -t "dispositivo/AA:BB:CC:DD:EE:FF/comando" \
  -m '{"sessionId":"test01","action":"PREPARE_BT","reason":"prueba manual"}'

# Enviar SWITCH_WIFI
mosquitto_pub -h 192.168.18.20 -t "dispositivo/AA:BB:CC:DD:EE:FF/comando" \
  -m '{"sessionId":"test02","action":"SWITCH_WIFI","ssid":"SDN_HIGH_SPEED","password":"sdn_secure_pass","reason":"prueba WiFi"}'

# Enviar RELEASE_RADIO
mosquitto_pub -h 192.168.18.20 -t "dispositivo/AA:BB:CC:DD:EE:FF/comando" \
  -m '{"sessionId":"test03","action":"RELEASE_RADIO","reason":"liberar radios"}'
```

## Limitaciones del ESP32 estándar

1. **Radio WiFi compartida**: La misma radio WiFi se usa para MQTT (control) y datos.
   No hay radio de datos separada como en un teléfono.
2. **BT sin Coded PHY**: Solo alcance de 10-30m con Bluetooth Classic/BLE 4.2.
3. **Sin LoRa**: Necesita módulo externo o usar el LILYGO T-Lora C6.
