# ═══════════════════════════════════════════════════════════════
#  SDN Data Plane Agent — LILYGO T-Lora C6 — README
# ═══════════════════════════════════════════════════════════════

## El dispositivo ideal para la tesis

El **LILYGO T-Lora C6** usa el chip ESP32-C6 que integra tres radios:

| Radio    | Estándar      | Tasa de datos | Alcance típico   | Uso en SDN           |
|----------|---------------|---------------|------------------|----------------------|
| WiFi 6   | 802.11ax      | ~100+ Mbps    | ~50m interior    | Datos pesados (video)|
| BLE 5.0  | Coded PHY S=8 | 125 kbps      | **~100m+ LOS**   | Datos ligeros (texto)|
| LoRa     | SX1262        | 0.3-50 kbps   | **2-15 km**      | Señalización/wake-up |

### ¿Por qué Coded PHY (S=8) y no BLE estándar?

| Modo BLE       | Data Rate | Alcance     | Uso                          |
|----------------|-----------|-------------|------------------------------|
| 1M PHY         | 1 Mbps    | ~30m        | BLE estándar                 |
| 2M PHY         | 2 Mbps    | ~15m        | Alta velocidad, corto alcance|
| **Coded S=2**  | 500 kbps  | ~70m        | Alcance medio                |
| **Coded S=8**  | **125 kbps** | **~100m+** | **★ Máximo alcance**      |

El modo Coded PHY usa **Forward Error Correction (FEC)** que duplica
la sensibilidad del receptor (+12 dB). Esto se traduce en ~4x más
alcance vs BLE estándar con el mismo consumo de energía.

## Cómo funciona el Long Range en el código

La activación de BLE Coded PHY ocurre en dos momentos:

### 1. En el Advertising (para que el celular lo detecte a distancia)

```cpp
// Extended Advertising con Coded PHY
NimBLEExtAdvertisement extAdv(BLE_HCI_LE_PHY_CODED, BLE_HCI_LE_PHY_CODED);
extAdv.setConnectable(true);
extAdv.setScannable(false);  // Coded PHY no soporta scan responses
extAdv.setTxPower(21);       // Máxima potencia: +21 dBm

NimBLEDevice::getAdvertising()->setInstanceData(0, extAdv);
NimBLEDevice::getAdvertising()->start(0, 0);
```

### 2. En la conexión (después de que el celular se conecta)

```cpp
void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
    // ★ CLAVE: Negociar Coded PHY para la conexión actual
    pServer->updatePHY(
        connInfo.getConnHandle(),
        BLE_PHY_CODED,   // TX PHY: Coded
        BLE_PHY_CODED    // RX PHY: Coded
    );
    // Ahora la conexión es Long Range: 125 kbps a ~100m+
}
```

### Requisito del lado del celular

Para que el Long Range funcione, el **celular también debe soportar BLE 5.0**:

| Celular         | BLE 5.0 | Coded PHY | Notas                      |
|-----------------|---------|-----------|----------------------------|
| Samsung S10+    | ✓       | ✓         | Soporta Coded PHY          |
| Pixel 3+        | ✓       | ✓         | Soporta Coded PHY          |
| iPhone 12+      | ✓       | ✗         | Apple no expone Coded PHY  |
| Samsung A-series| Varía   | Varía     | Verificar specs del modelo |

La **app Android** que desarrollaremos después debe usar la API de Android
`BluetoothLeScanner` con `ScanSettings.PHY_LE_CODED` para buscar dispositivos
Long Range.

## Librerías necesarias

### Arduino IDE

1. **PubSubClient** (Nick O'Leary) — MQTT
2. **ArduinoJson** (Benoit Blanchon) v7+ — JSON
3. **NimBLE-Arduino** (h2zero) — BLE 5.0 con Coded PHY
4. **RadioLib** (Jan Gromeš) — LoRa SX1262

### Board Package

En Arduino IDE → Preferences → Additional Boards Manager URLs:
```
https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
```

Luego en Boards Manager, instalar **esp32** (by Espressif).
Seleccionar placa: **ESP32C6 Dev Module**.

### PlatformIO (alternativa recomendada)

```ini
; platformio.ini
[env:esp32c6]
platform = espressif32
board = esp32-c6-devkitc-1
framework = arduino
lib_deps =
    knolleary/PubSubClient@^2.8
    bblanchon/ArduinoJson@^7.0
    h2zero/NimBLE-Arduino@^2.0
    jgromes/RadioLib@^7.0

monitor_speed = 115200
```

## Configuración

Editar `config.h`:

```c
// WiFi de tu lab
#define WIFI_SSID_CONTROL    "MiRedWiFi"
#define WIFI_PASS_CONTROL    "MiPassword123"

// IP del broker MQTT
#define MQTT_BROKER_IP       "192.168.18.20"

// Frecuencia LoRa (915 MHz para Perú/LATAM)
#define LORA_FREQUENCY       915.0

// PHY para BLE Long Range (no cambiar)
#define BLE_PREFERRED_PHY    BLE_PHY_CODED
```

## Pines del LILYGO T-Lora C6

⚠️ **Los pines varían según la versión de la placa.** Verificar con la
documentación oficial de LILYGO o el esquemático de tu versión.

```
SX1262 (LoRa):
  NSS  → GPIO 18
  RST  → GPIO 23
  DIO1 → GPIO 33
  BUSY → GPIO 34

OLED (si incluye pantalla):
  SDA  → GPIO 17
  SCL  → GPIO 18
  RST  → GPIO 21

LED:
  LED  → GPIO 7
```

## Probar Bluetooth Long Range

1. Flashear el LILYGO T-Lora C6
2. Enviar comando PREPARE_BT:
   ```bash
   mosquitto_pub -h 192.168.18.20 -t "dispositivo/XX:XX:XX:XX:XX:XX/comando" \
     -m '{"sessionId":"lr-test","action":"PREPARE_BT","reason":"test long range"}'
   ```
3. En el Serial Monitor, debe verse:
   ```
   ★ BLE 5.0 Coded PHY ACTIVADO
     PHY: Coded (S=8) — 125 kbps
     Alcance: ~100m+ (línea de vista)
     Potencia TX: +21 dBm
   ```
4. Desde un celular con BLE 5.0, usar la app **nRF Connect** (Nordic)
   para buscar dispositivos con "Coded PHY" y conectarse.

## Comparación: ESP32 vs LILYGO T-Lora C6

| Característica     | ESP32 estándar     | LILYGO T-Lora C6 (ESP32-C6) |
|--------------------|--------------------|------------------------------|
| WiFi               | 802.11 b/g/n       | **802.11ax (WiFi 6)**        |
| Bluetooth          | Classic + BLE 4.2   | **BLE 5.0 Coded PHY**       |
| BT alcance         | ~10-30m            | **~100m+ (Coded S=8)**       |
| LoRa               | ✗ (necesita módulo)| **✓ SX1262 integrado**       |
| LoRa alcance       | —                  | **2-15 km**                  |
| BT Classic (SPP)   | ✓                  | ✗ (solo BLE)                 |
| Consumo BLE        | ~10 mA             | **~5 mA (Coded PHY)**        |
| Precio aprox.      | ~$5-8 USD          | ~$15-20 USD                  |
