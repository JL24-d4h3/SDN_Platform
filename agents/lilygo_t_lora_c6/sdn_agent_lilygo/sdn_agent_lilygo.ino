/**
 * ═══════════════════════════════════════════════════════════════
 *  SDN Data Plane Agent — LILYGO T-Lora C6 (ESP32-C6)
 * ═══════════════════════════════════════════════════════════════
 *
 * Agente del plano de datos para el LILYGO T-Lora C6.
 * Hardware objetivo para la tesis: BLE 5.0 Long Range + WiFi 6 + LoRa.
 *
 * ┌─────────────────────────────────────────────────────────┐
 * │  ESP32-C6 — Radios disponibles                         │
 * ├──────────┬──────────────┬──────────────────────────────┤
 * │ Radio    │ Estándar     │ Uso en la red SDN            │
 * ├──────────┼──────────────┼──────────────────────────────┤
 * │ WiFi 6   │ 802.11ax     │ Canal de alta velocidad      │
 * │          │ 2.4 GHz      │ (video, web, archivos >10MB) │
 * ├──────────┼──────────────┼──────────────────────────────┤
 * │ BLE 5.0  │ Coded PHY    │ Canal de bajo consumo        │
 * │          │ S=8, 125kbps │ (texto, IA, control)         │
 * │          │ ~100m+       │ ← LONG RANGE ★              │
 * ├──────────┼──────────────┼──────────────────────────────┤
 * │ LoRa     │ SX1262       │ Señalización y wake-up       │
 * │          │ ~2-15 km     │ (futuro: despertar nodos)    │
 * └──────────┴──────────────┴──────────────────────────────┘
 *
 * Librerías necesarias:
 * - PubSubClient (Nick O'Leary) — MQTT
 * - ArduinoJson (Benoit Blanchon) v7+ — JSON
 * - NimBLE-Arduino (h2zero) — BLE 5.0 con Coded PHY
 * - RadioLib (Jan Gromeš) — LoRa SX1262
 *
 * Framework: Arduino (ESP32-C6 board package)
 *
 * Board package para Arduino IDE:
 * https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
 * Seleccionar: ESP32C6 Dev Module
 */

#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>

// ── BLE 5.0 con NimBLE (soporta Coded PHY / Long Range) ──
#include <NimBLEDevice.h>

// ── LoRa con RadioLib (SX1262) ──
#include <RadioLib.h>

#include "config.h"

// ══════════════════════════════════════════════
//  Objetos globales
// ══════════════════════════════════════════════

WiFiClient     wifiClient;
PubSubClient   mqttClient(wifiClient);

// BLE Server (el LILYGO actúa como periférico BLE)
NimBLEServer*         bleServer = nullptr;
NimBLECharacteristic* bleTxChar = nullptr;
NimBLECharacteristic* bleRxChar = nullptr;
bool                  bleClientConnected = false;

// LoRa (SX1262)
SX1262 loraRadio = new Module(LORA_NSS_PIN, LORA_DIO1_PIN, LORA_RST_PIN, LORA_BUSY_PIN);
bool   loraInitialized = false;

// Estado del dispositivo
String deviceMac = "";

enum RadioState { RADIO_OFF, RADIO_BLE_LR, RADIO_WIFI, RADIO_BOTH, RADIO_LORA };
RadioState currentRadio = RADIO_OFF;

// Tópicos MQTT
String topicComando  = "";
String topicMetrics  = "";
String topicRegistro = "";

// Timestamps
unsigned long lastTelemetry = 0;
unsigned long lastReconnect = 0;

// ══════════════════════════════════════════════
//  BLE 5.0 Coded PHY — Server Callbacks
// ══════════════════════════════════════════════

/**
 * Callbacks del servidor BLE.
 *
 * Cuando un celular u otro nodo se conecta por BLE Long Range,
 * estas callbacks se activan.
 */
class SdnBleCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
        bleClientConnected = true;
        Serial.println("  ★ BLE: Cliente conectado (Long Range)");
        Serial.print("    Dirección: ");
        Serial.println(connInfo.getAddress().toString().c_str());

        // ── CONFIGURAR CODED PHY (S=8) para Long Range ──
        // Esto es lo que activa el modo de largo alcance real.
        // S=8 = 125 kbps, máximo alcance (~100m+ en línea de vista).
        pServer->updatePHY(connInfo.getConnHandle(), BLE_PHY_CODED, BLE_PHY_CODED);
        Serial.println("    ★ PHY actualizado a CODED (S=8) — LONG RANGE ACTIVADO");
    }

    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
        bleClientConnected = false;
        Serial.print("  BLE: Cliente desconectado (razón: ");
        Serial.print(reason);
        Serial.println(")");

        // Reiniciar advertising para aceptar nueva conexión
        NimBLEDevice::startAdvertising();
        Serial.println("  BLE: Advertising reiniciado");
    }
};

/**
 * Callback para datos recibidos por BLE.
 *
 * Cuando el celular envía datos por BLE Long Range al nodo,
 * se procesan aquí.
 */
class SdnBleRxCallback : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pChar, NimBLEConnInfo& connInfo) override {
        std::string value = pChar->getValue();
        if (value.length() > 0) {
            Serial.print("  BLE RX: ");
            Serial.println(value.c_str());
            // Aquí se procesaría la solicitud del celular
            // y se podría reenviar al gateway.
        }
    }
};

// ══════════════════════════════════════════════
//  Forward declarations
// ══════════════════════════════════════════════
void connectWifi();
void connectMqtt();
void mqttCallback(char* topic, byte* payload, unsigned int length);
void handleCommand(const char* json);
void prepareBleLongRange(const char* reason);
void switchToWifi(const char* ssid, const char* pass, const char* reason);
void releaseRadio(const char* reason);
void initLoRa();
void publishTelemetry();
void publishRegistration();
String getFormattedMac();

// ══════════════════════════════════════════════
//  SETUP
// ══════════════════════════════════════════════

void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println();
    Serial.println("═══════════════════════════════════════════════");
    Serial.println("  SDN Data Plane Agent — LILYGO T-Lora C6");
    Serial.println("  ★ BLE 5.0 Coded PHY (Long Range)");
    Serial.println("  ★ WiFi 6 (802.11ax)");
    Serial.println("  ★ LoRa SX1262");
    Serial.println("═══════════════════════════════════════════════");

    // ── 1. WiFi de control ──
    connectWifi();

    // ── 2. MAC ──
    deviceMac = getFormattedMac();
    Serial.print("MAC: ");
    Serial.println(deviceMac);

    // ── 3. Tópicos MQTT ──
    topicComando  = "dispositivo/" + deviceMac + "/comando";
    topicMetrics  = "dispositivo/" + deviceMac + "/metrics";
    topicRegistro = "dispositivo/" + deviceMac + "/registro";

    // ── 4. MQTT ──
    mqttClient.setServer(MQTT_BROKER_IP, MQTT_BROKER_PORT);
    mqttClient.setCallback(mqttCallback);
    mqttClient.setBufferSize(1024);
    connectMqtt();

    // ── 5. Inicializar LoRa (en background, no activa hasta que se use) ──
    initLoRa();

    // ── 6. Auto-registro ──
    publishRegistration();

    Serial.println("═══════════════════════════════════════════════");
    Serial.println("  Agente listo. Radios: WiFi(ctrl) + LoRa(standby)");
    Serial.println("  BLE Long Range: activar con PREPARE_BT");
    Serial.println("═══════════════════════════════════════════════");
}

// ══════════════════════════════════════════════
//  LOOP PRINCIPAL
// ══════════════════════════════════════════════

void loop() {
    if (!mqttClient.connected()) {
        unsigned long now = millis();
        if (now - lastReconnect > MQTT_RECONNECT_DELAY_MS) {
            lastReconnect = now;
            connectMqtt();
        }
    }
    mqttClient.loop();

    unsigned long now = millis();
    if (now - lastTelemetry > TELEMETRY_INTERVAL_MS) {
        lastTelemetry = now;
        publishTelemetry();
    }
}

// ══════════════════════════════════════════════
//  WiFi & MQTT (igual que ESP32 estándar)
// ══════════════════════════════════════════════

void connectWifi() {
    Serial.print("Conectando WiFi 6: ");
    Serial.println(WIFI_SSID_CONTROL);

    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID_CONTROL, WIFI_PASS_CONTROL);

    int retries = 0;
    while (WiFi.status() != WL_CONNECTED && retries < 40) {
        delay(500);
        Serial.print(".");
        retries++;
    }

    if (WiFi.status() == WL_CONNECTED) {
        Serial.println();
        Serial.print("✓ WiFi 6 conectado. IP: ");
        Serial.println(WiFi.localIP());
    } else {
        Serial.println("\nERROR: WiFi no conectado. Reiniciando...");
        delay(5000);
        ESP.restart();
    }
}

void connectMqtt() {
    if (mqttClient.connected()) return;

    Serial.print("Conectando MQTT...");
    if (mqttClient.connect(MQTT_CLIENT_ID)) {
        Serial.println(" ✓");
        mqttClient.subscribe(topicComando.c_str(), 1);
        Serial.println("✓ Suscrito a: " + topicComando);
    } else {
        Serial.print(" ✗ rc=");
        Serial.println(mqttClient.state());
    }
}

void mqttCallback(char* topic, byte* payload, unsigned int length) {
    char json[length + 1];
    memcpy(json, payload, length);
    json[length] = '\0';

    Serial.println();
    Serial.println("╔═══════════════════════════════════════════");
    Serial.println("║ COMANDO RECIBIDO");
    Serial.print("║ Payload: ");
    Serial.println(json);
    Serial.println("╚═══════════════════════════════════════════");

    handleCommand(json);
}

void handleCommand(const char* json) {
    JsonDocument doc;
    DeserializationError error = deserializeJson(doc, json);
    if (error) {
        Serial.print("ERROR JSON: ");
        Serial.println(error.c_str());
        return;
    }

    const char* action    = doc["action"] | "UNKNOWN";
    const char* sessionId = doc["sessionId"] | "???";
    const char* reason    = doc["reason"] | "";

    Serial.print("Acción: ");
    Serial.print(action);
    Serial.print(" | Sesión: ");
    Serial.println(sessionId);

    if (strcmp(action, "PREPARE_BT") == 0) {
        prepareBleLongRange(reason);
    } else if (strcmp(action, "SWITCH_WIFI") == 0) {
        const char* ssid = doc["ssid"] | "";
        const char* pass = doc["password"] | "";
        switchToWifi(ssid, pass, reason);
    } else if (strcmp(action, "RELEASE_RADIO") == 0) {
        releaseRadio(reason);
    } else {
        Serial.print("⚠ Acción desconocida: ");
        Serial.println(action);
    }
}

// ══════════════════════════════════════════════════════════════
//  ★ BLE 5.0 CODED PHY — LONG RANGE (~100m+)
// ══════════════════════════════════════════════════════════════
//
//  Esta es la función clave que diferencia al LILYGO T-Lora C6
//  del ESP32 estándar.
//
//  BLE Coded PHY (S=8):
//  - Data rate: 125 kbps (suficiente para texto/IA/control)
//  - Alcance: ~100m en línea de vista, ~50m con obstáculos
//  - Consumo: ~10x menor que WiFi activo
//  - Ideal para: solicitudes de texto/IA, señalización, control
//
//  Flujo:
//  1. El controlador envía PREPARE_BT → este nodo inicia BLE server
//  2. El celular (con app Android) se conecta como BLE client
//  3. Se negocia Coded PHY (S=8) para máximo alcance
//  4. El celular envía la solicitud por BLE
//  5. El nodo recibe y la reenvía al gateway por WiFi/LoRa
//
// ══════════════════════════════════════════════════════════════

void prepareBleLongRange(const char* reason) {
    Serial.println("──── PREPARE_BT (BLE 5.0 Long Range) ──────");
    Serial.print("  Razón: ");
    Serial.println(reason);

    if (currentRadio == RADIO_BLE_LR || currentRadio == RADIO_BOTH) {
        Serial.println("  BLE Long Range ya está activo.");
        return;
    }

    // ── Inicializar NimBLE ──
    NimBLEDevice::init(BLE_SERVICE_NAME);

    // ── Configurar potencia de transmisión al máximo ──
    NimBLEDevice::setPower(ESP_PWR_LVL_P21);  // +21 dBm (máximo para C6)

    // ── Crear servidor BLE ──
    bleServer = NimBLEDevice::createServer();
    bleServer->setCallbacks(new SdnBleCallbacks());

    // ── Crear servicio SDN ──
    NimBLEService* service = bleServer->createService(SDN_SERVICE_UUID);

    // Característica TX (nodo → celular)
    bleTxChar = service->createCharacteristic(
        SDN_CHAR_TX_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY
    );

    // Característica RX (celular → nodo)
    bleRxChar = service->createCharacteristic(
        SDN_CHAR_RX_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
    );
    bleRxChar->setCallbacks(new SdnBleRxCallback());

    // ── Iniciar servicio ──
    service->start();

    // ── Configurar Advertising con soporte Coded PHY ──
    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(SDN_SERVICE_UUID);
    adv->setMinPreferred(0x06);

    // ★ CLAVE: Habilitar Extended Advertising con Coded PHY
    // Esto es lo que le dice a los clientes BLE que soportamos Long Range.
    NimBLEExtAdvertisement extAdv(BLE_HCI_LE_PHY_CODED, BLE_HCI_LE_PHY_CODED);
    extAdv.setName(BLE_SERVICE_NAME);
    extAdv.setCompleteServices16({NimBLEUUID((uint16_t)0x1234)});
    extAdv.setConnectable(true);
    extAdv.setScannable(false);  // Coded PHY no soporta scan responses
    extAdv.setTxPower(21);       // Máxima potencia

    // Iniciar Extended Advertising (soporta Coded PHY)
    NimBLEDevice::getAdvertising()->setInstanceData(0, extAdv);
    NimBLEDevice::getAdvertising()->start(0, 0);

    if (currentRadio == RADIO_WIFI) {
        currentRadio = RADIO_BOTH;
    } else {
        currentRadio = RADIO_BLE_LR;
    }

    Serial.println("  ★ BLE 5.0 Coded PHY ACTIVADO");
    Serial.println("    PHY: Coded (S=8) — 125 kbps");
    Serial.println("    Alcance: ~100m+ (línea de vista)");
    Serial.println("    Potencia TX: +21 dBm");
    Serial.print("    Servicio UUID: ");
    Serial.println(SDN_SERVICE_UUID);
    Serial.println("    Esperando conexión de cliente BLE...");
    Serial.println("────────────────────────────────────────");
}

// ══════════════════════════════════════════════
//  SWITCH_WIFI — WiFi 6 de alta velocidad
// ══════════════════════════════════════════════

void switchToWifi(const char* ssid, const char* pass, const char* reason) {
    Serial.println("──── SWITCH_WIFI (WiFi 6) ──────────────");
    Serial.print("  Razón: ");
    Serial.println(reason);
    Serial.print("  SSID: ");
    Serial.println(ssid);

    if (WiFi.status() == WL_CONNECTED) {
        Serial.println("  ✓ WiFi ya activa (usando conexión existente)");
        Serial.print("    IP: ");
        Serial.println(WiFi.localIP());
    } else {
        WiFi.begin(ssid, pass);
        int retries = 0;
        while (WiFi.status() != WL_CONNECTED && retries < 20) {
            delay(500);
            Serial.print(".");
            retries++;
        }
        if (WiFi.status() == WL_CONNECTED) {
            Serial.println("\n  ✓ WiFi 6 conectada a " + String(ssid));
        } else {
            Serial.println("\n  ✗ Error conectando a " + String(ssid));
        }
    }

    if (currentRadio == RADIO_BLE_LR) {
        currentRadio = RADIO_BOTH;
    } else {
        currentRadio = RADIO_WIFI;
    }

    Serial.println("────────────────────────────────────────");
}

// ══════════════════════════════════════════════
//  RELEASE_RADIO — Apagar radios de datos
// ══════════════════════════════════════════════

void releaseRadio(const char* reason) {
    Serial.println("──── RELEASE_RADIO ─────────────────────");
    Serial.print("  Razón: ");
    Serial.println(reason);

    // Apagar BLE si estaba activo
    if (currentRadio == RADIO_BLE_LR || currentRadio == RADIO_BOTH) {
        NimBLEDevice::getAdvertising()->stop();
        NimBLEDevice::deinit(true);
        bleServer = nullptr;
        bleTxChar = nullptr;
        bleRxChar = nullptr;
        bleClientConnected = false;
        Serial.println("  ✓ BLE Long Range apagado");
    }

    // WiFi de control se mantiene
    if (currentRadio == RADIO_WIFI || currentRadio == RADIO_BOTH) {
        Serial.println("  ✓ WiFi de datos liberada (control mantenido)");
    }

    currentRadio = RADIO_OFF;
    Serial.println("  Estado: radios de datos OFF");
    Serial.println("────────────────────────────────────────");
}

// ══════════════════════════════════════════════
//  LoRa SX1262 — Inicialización
// ══════════════════════════════════════════════

/**
 * Inicializa el módulo LoRa SX1262 del LILYGO T-Lora C6.
 *
 * LoRa se usa como canal de señalización de ultra-largo alcance
 * (2-15 km). Ideal para:
 * - Wake-up de nodos dormidos
 * - Señalización cuando no hay WiFi ni BLE al alcance
 * - Mensajes de control pequeños (<256 bytes)
 *
 * NO se usa para transferencia de datos (muy baja velocidad).
 */
void initLoRa() {
    Serial.print("Inicializando LoRa SX1262...");

    int state = loraRadio.begin(
        LORA_FREQUENCY,
        LORA_BANDWIDTH,
        LORA_SPREADING,
        LORA_CODING_RATE,
        LORA_SYNC_WORD,
        LORA_TX_POWER,
        LORA_PREAMBLE
    );

    if (state == RADIOLIB_ERR_NONE) {
        loraInitialized = true;
        Serial.println(" ✓");
        Serial.print("    Frecuencia: ");
        Serial.print(LORA_FREQUENCY);
        Serial.println(" MHz");
        Serial.print("    SF: ");
        Serial.print(LORA_SPREADING);
        Serial.print(", BW: ");
        Serial.print(LORA_BANDWIDTH);
        Serial.println(" kHz");
        Serial.print("    TX Power: ");
        Serial.print(LORA_TX_POWER);
        Serial.println(" dBm");

        // Poner LoRa en modo receive (escuchar señalización)
        loraRadio.startReceive();
        Serial.println("    LoRa en modo escucha (standby)");
    } else {
        loraInitialized = false;
        Serial.print(" ✗ Error: ");
        Serial.println(state);
        Serial.println("    LoRa no disponible. Continuando sin LoRa.");
    }
}

/**
 * Envía un mensaje corto por LoRa (señalización).
 *
 * Ejemplo: despertar un nodo dormido, broadcast de presencia.
 */
void loraSendSignal(const char* message) {
    if (!loraInitialized) {
        Serial.println("LoRa no inicializado");
        return;
    }

    Serial.print("[LoRa TX] ");
    Serial.println(message);

    int state = loraRadio.transmit(message);
    if (state == RADIOLIB_ERR_NONE) {
        Serial.println("  ✓ LoRa: mensaje enviado");
    } else {
        Serial.print("  ✗ LoRa error: ");
        Serial.println(state);
    }

    // Volver a modo recepción
    loraRadio.startReceive();
}

// ══════════════════════════════════════════════
//  TELEMETRÍA y REGISTRO
// ══════════════════════════════════════════════

void publishTelemetry() {
    if (!mqttClient.connected()) return;

    JsonDocument doc;
    doc["mac"]          = deviceMac;
    doc["rssi"]         = WiFi.RSSI();
    doc["batteryLevel"] = 100;
    doc["ipAddress"]    = WiFi.localIP().toString();

    // Informar radio activa
    switch (currentRadio) {
        case RADIO_BLE_LR: doc["technology"] = "ble_coded_phy"; break;
        case RADIO_WIFI:   doc["technology"] = "wifi6"; break;
        case RADIO_BOTH:   doc["technology"] = "wifi6+ble_coded"; break;
        case RADIO_LORA:   doc["technology"] = "lora"; break;
        case RADIO_OFF:    doc["technology"] = "wifi6"; break;
    }

    // Extras: información de BLE y LoRa
    doc["bleConnected"] = bleClientConnected;
    doc["loraActive"]   = loraInitialized;

    char buffer[512];
    serializeJson(doc, buffer, sizeof(buffer));

    bool ok = mqttClient.publish(topicMetrics.c_str(), buffer, false);

    Serial.print("[Telemetría] RSSI=");
    Serial.print(WiFi.RSSI());
    Serial.print(", BLE=");
    Serial.print(bleClientConnected ? "conectado" : "off");
    Serial.print(", LoRa=");
    Serial.print(loraInitialized ? "ok" : "off");
    Serial.println(ok ? " → publicado" : " → ERROR");
}

void publishRegistration() {
    if (!mqttClient.connected()) return;

    JsonDocument doc;
    doc["mac"]        = deviceMac;
    doc["name"]       = DEVICE_NAME;
    doc["deviceType"] = DEVICE_TYPE;
    doc["ipAddress"]  = WiFi.localIP().toString();

    char buffer[256];
    serializeJson(doc, buffer, sizeof(buffer));

    bool ok = mqttClient.publish(topicRegistro.c_str(), buffer, false);
    Serial.print("[Registro] ");
    Serial.print(deviceMac);
    Serial.println(ok ? " → publicado" : " → ERROR");
}

String getFormattedMac() {
    String mac = WiFi.macAddress();
    mac.toUpperCase();
    return mac;
}
