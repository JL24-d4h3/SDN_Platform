/**
 * ═══════════════════════════════════════════════════════════════
 *  SDN Data Plane Agent — ESP32
 * ═══════════════════════════════════════════════════════════════
 *
 * Agente del plano de datos para ESP32 estándar (ESP-WROOM-32).
 *
 * Funciones:
 * 1. Conecta al broker MQTT por WiFi (canal de señalización).
 * 2. Se suscribe a `dispositivo/{MAC}/comando`.
 * 3. Parsea comandos JSON del controlador SDN:
 *      - PREPARE_BT   → Enciende Bluetooth
 *      - SWITCH_WIFI   → Conecta a WiFi de alta velocidad
 *      - RELEASE_RADIO → Apaga la radio activa
 * 4. Publica telemetría periódica a `dispositivo/{MAC}/metrics`.
 * 5. Se auto-registra al broker a `dispositivo/{MAC}/registro`.
 *
 * Librerías necesarias (instalar desde Arduino Library Manager):
 * - PubSubClient (Nick O'Leary)
 * - ArduinoJson (Benoit Blanchon) v7+
 *
 * Hardware: ESP32 DevKit V1 o cualquier ESP-WROOM-32
 * Framework: Arduino
 */

#include <WiFi.h>
#include <BluetoothSerial.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include "config.h"

// ══════════════════════════════════════════════
//  Objetos globales
// ══════════════════════════════════════════════

WiFiClient   wifiClient;
PubSubClient mqttClient(wifiClient);
BluetoothSerial btSerial;

// MAC address del dispositivo (se llena en setup)
String deviceMac = "";

// Estado actual de las radios
enum RadioState { RADIO_OFF, RADIO_BT, RADIO_WIFI, RADIO_BOTH };
RadioState currentRadio = RADIO_OFF;

// Tópicos MQTT (se construyen con la MAC)
String topicComando   = "";
String topicMetrics   = "";
String topicRegistro  = "";

// Timestamps
unsigned long lastTelemetry    = 0;
unsigned long lastReconnect    = 0;

// WiFi de alta velocidad (se llena desde el comando SWITCH_WIFI)
String highSpeedSsid     = "";
String highSpeedPassword = "";

// ══════════════════════════════════════════════
//  Forward declarations
// ══════════════════════════════════════════════
void connectWifi();
void connectMqtt();
void mqttCallback(char* topic, byte* payload, unsigned int length);
void handleCommand(const char* json);
void prepareBluetooth(const char* reason);
void switchToWifi(const char* ssid, const char* pass, const char* reason);
void releaseRadio(const char* reason);
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
    Serial.println("  SDN Data Plane Agent — ESP32");
    Serial.println("═══════════════════════════════════════════════");

    // ── 1. Conectar WiFi de control ──
    connectWifi();

    // ── 2. Obtener MAC ──
    #ifdef DEVICE_MAC_OVERRIDE
        deviceMac = DEVICE_MAC_OVERRIDE;
    #else
        deviceMac = getFormattedMac();
    #endif

    Serial.print("MAC del dispositivo: ");
    Serial.println(deviceMac);

    // ── 3. Construir tópicos MQTT ──
    topicComando  = "dispositivo/" + deviceMac + "/comando";
    topicMetrics  = "dispositivo/" + deviceMac + "/metrics";
    topicRegistro = "dispositivo/" + deviceMac + "/registro";

    Serial.println("Tópicos MQTT:");
    Serial.println("  Suscripción: " + topicComando);
    Serial.println("  Telemetría:  " + topicMetrics);
    Serial.println("  Registro:    " + topicRegistro);

    // ── 4. Configurar MQTT ──
    mqttClient.setServer(MQTT_BROKER_IP, MQTT_BROKER_PORT);
    mqttClient.setCallback(mqttCallback);
    mqttClient.setBufferSize(1024); // Buffer más grande para JSONs

    // ── 5. Conectar al broker ──
    connectMqtt();

    // ── 6. Auto-registrarse ──
    publishRegistration();

    Serial.println("═══════════════════════════════════════════════");
    Serial.println("  Agente listo. Esperando comandos...");
    Serial.println("═══════════════════════════════════════════════");
}

// ══════════════════════════════════════════════
//  LOOP PRINCIPAL
// ══════════════════════════════════════════════

void loop() {
    // Mantener conexión MQTT
    if (!mqttClient.connected()) {
        unsigned long now = millis();
        if (now - lastReconnect > MQTT_RECONNECT_DELAY_MS) {
            lastReconnect = now;
            connectMqtt();
        }
    }
    mqttClient.loop();

    // Telemetría periódica
    unsigned long now = millis();
    if (now - lastTelemetry > TELEMETRY_INTERVAL_MS) {
        lastTelemetry = now;
        publishTelemetry();
    }
}

// ══════════════════════════════════════════════
//  CONEXIÓN WiFi (canal de señalización/control)
// ══════════════════════════════════════════════

void connectWifi() {
    Serial.print("Conectando a WiFi: ");
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
        Serial.print("WiFi conectado. IP: ");
        Serial.println(WiFi.localIP());
    } else {
        Serial.println();
        Serial.println("ERROR: No se pudo conectar a WiFi. Reiniciando en 5s...");
        delay(5000);
        ESP.restart();
    }
}

// ══════════════════════════════════════════════
//  CONEXIÓN MQTT
// ══════════════════════════════════════════════

void connectMqtt() {
    if (mqttClient.connected()) return;

    Serial.print("Conectando al broker MQTT ");
    Serial.print(MQTT_BROKER_IP);
    Serial.print(":");
    Serial.println(MQTT_BROKER_PORT);

    if (mqttClient.connect(MQTT_CLIENT_ID)) {
        Serial.println("✓ Conectado al broker MQTT");

        // Suscribirse al tópico de comandos
        bool subscribed = mqttClient.subscribe(topicComando.c_str(), 1);
        if (subscribed) {
            Serial.println("✓ Suscrito a: " + topicComando);
        } else {
            Serial.println("✗ Error al suscribir a: " + topicComando);
        }
    } else {
        Serial.print("✗ Error MQTT, rc=");
        Serial.println(mqttClient.state());
    }
}

// ══════════════════════════════════════════════
//  CALLBACK MQTT — Recibe comandos del controlador
// ══════════════════════════════════════════════

void mqttCallback(char* topic, byte* payload, unsigned int length) {
    // Copiar payload a string
    char json[length + 1];
    memcpy(json, payload, length);
    json[length] = '\0';

    Serial.println();
    Serial.println("╔═══════════════════════════════════════════");
    Serial.println("║ COMANDO RECIBIDO");
    Serial.print("║ Tópico: ");
    Serial.println(topic);
    Serial.print("║ Payload: ");
    Serial.println(json);
    Serial.println("╚═══════════════════════════════════════════");

    handleCommand(json);
}

// ══════════════════════════════════════════════
//  INTERPRETACIÓN DE COMANDOS JSON
// ══════════════════════════════════════════════

/**
 * Parsea el JSON del controlador y ejecuta la acción.
 *
 * Formato esperado del JSON:
 * {
 *   "sessionId": "abc12345",
 *   "action": "PREPARE_BT" | "SWITCH_WIFI" | "RELEASE_RADIO",
 *   "ssid": "SDN_HIGH_SPEED",       // solo en SWITCH_WIFI
 *   "password": "sdn_secure_pass",   // solo en SWITCH_WIFI
 *   "reason": "Sesión abc12345: activar BT para enviar solicitud"
 * }
 */
void handleCommand(const char* json) {
    JsonDocument doc;
    DeserializationError error = deserializeJson(doc, json);

    if (error) {
        Serial.print("ERROR parseando JSON: ");
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

    // ── Ejecutar acción ──
    if (strcmp(action, "PREPARE_BT") == 0) {
        prepareBluetooth(reason);

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

// ══════════════════════════════════════════════
//  CONTROL DE RADIOS
// ══════════════════════════════════════════════

/**
 * PREPARE_BT — Activar Bluetooth Classic (SPP) para transferencia de datos.
 *
 * En ESP32 estándar usamos BluetoothSerial (SPP) que permite
 * streaming de datos sobre Bluetooth Classic.
 *
 * Nota: El ESP32 estándar soporta BT Classic + BLE 4.2.
 * Para BT 5.0 Coded PHY (Long Range), usar el LILYGO T-Lora C6.
 */
void prepareBluetooth(const char* reason) {
    Serial.println("──── PREPARE_BT ────────────────────────");
    Serial.print("  Razón: ");
    Serial.println(reason);

    if (currentRadio == RADIO_BT || currentRadio == RADIO_BOTH) {
        Serial.println("  Bluetooth ya está activo.");
        return;
    }

    // Iniciar Bluetooth Serial (SPP)
    if (btSerial.begin(DEVICE_NAME)) {
        Serial.println("  ✓ Bluetooth activado (SPP)");
        Serial.print("  Nombre BT: ");
        Serial.println(DEVICE_NAME);

        if (currentRadio == RADIO_WIFI) {
            currentRadio = RADIO_BOTH;
        } else {
            currentRadio = RADIO_BT;
        }
    } else {
        Serial.println("  ✗ ERROR: No se pudo activar Bluetooth");
    }

    Serial.println("────────────────────────────────────────");
}

/**
 * SWITCH_WIFI — Conectar a la red WiFi de alta velocidad para datos.
 *
 * NOTA IMPORTANTE: En el ESP32, la radio WiFi es compartida entre
 * la conexión de control (MQTT) y la de datos. Si ya estamos
 * conectados a la WiFi de control, "switch" implica que el
 * dispositivo ya tiene WiFi activa. En un escenario real con
 * dos radios, aquí se conectaría a una segunda red.
 *
 * Para el prototipo: marcamos el estado y logueamos la decisión.
 * La transferencia real de datos se haría sobre la conexión existente.
 */
void switchToWifi(const char* ssid, const char* pass, const char* reason) {
    Serial.println("──── SWITCH_WIFI ───────────────────────");
    Serial.print("  Razón: ");
    Serial.println(reason);
    Serial.print("  SSID solicitado: ");
    Serial.println(ssid);

    // Guardar credenciales para referencia
    highSpeedSsid = String(ssid);
    highSpeedPassword = String(pass);

    // En el ESP32, si la WiFi de control y la de datos son la misma red,
    // simplemente marcamos que WiFi está lista para datos.
    // Si son redes diferentes, habría que reconectar (perdiendo MQTT temporalmente).

    if (WiFi.status() == WL_CONNECTED) {
        Serial.println("  ✓ WiFi ya activa (usando conexión existente para datos)");
        Serial.print("    IP actual: ");
        Serial.println(WiFi.localIP());
    } else {
        Serial.println("  Reconectando WiFi...");
        WiFi.begin(ssid, pass);
        int retries = 0;
        while (WiFi.status() != WL_CONNECTED && retries < 20) {
            delay(500);
            Serial.print(".");
            retries++;
        }
        if (WiFi.status() == WL_CONNECTED) {
            Serial.println("\n  ✓ WiFi conectada a " + String(ssid));
        } else {
            Serial.println("\n  ✗ ERROR: No se pudo conectar a " + String(ssid));
        }
    }

    if (currentRadio == RADIO_BT) {
        currentRadio = RADIO_BOTH;
    } else {
        currentRadio = RADIO_WIFI;
    }

    Serial.println("────────────────────────────────────────");
}

/**
 * RELEASE_RADIO — Apagar las radios de datos (liberar recursos).
 *
 * Apaga Bluetooth si estaba activo. NO apaga WiFi porque
 * la necesitamos para el canal de señalización MQTT.
 *
 * En un escenario con radio de datos separada, aquí se apaga
 * la radio de datos específica.
 */
void releaseRadio(const char* reason) {
    Serial.println("──── RELEASE_RADIO ─────────────────────");
    Serial.print("  Razón: ");
    Serial.println(reason);
    Serial.print("  Radio actual: ");

    switch (currentRadio) {
        case RADIO_BT:   Serial.println("BLUETOOTH"); break;
        case RADIO_WIFI:  Serial.println("WIFI"); break;
        case RADIO_BOTH:  Serial.println("AMBAS"); break;
        case RADIO_OFF:   Serial.println("NINGUNA"); break;
    }

    // Apagar Bluetooth si estaba activo
    if (currentRadio == RADIO_BT || currentRadio == RADIO_BOTH) {
        btSerial.end();
        Serial.println("  ✓ Bluetooth apagado");
    }

    // WiFi de datos: solo marcar como liberada
    // (mantener WiFi de control para MQTT)
    if (currentRadio == RADIO_WIFI || currentRadio == RADIO_BOTH) {
        Serial.println("  ✓ WiFi de datos liberada (WiFi de control mantenida)");
    }

    currentRadio = RADIO_OFF;
    Serial.println("  Estado de radio: OFF");
    Serial.println("────────────────────────────────────────");
}

// ══════════════════════════════════════════════
//  TELEMETRÍA — Publicar métricas al controlador
// ══════════════════════════════════════════════

/**
 * Publica métricas periódicas al tópico `dispositivo/{MAC}/metrics`.
 *
 * Formato esperado por el controlador:
 * {
 *   "mac": "AA:BB:CC:DD:EE:FF",
 *   "rssi": -45,
 *   "technology": "wifi",
 *   "batteryLevel": 100,
 *   "ipAddress": "192.168.18.50"
 * }
 */
void publishTelemetry() {
    if (!mqttClient.connected()) return;

    JsonDocument doc;
    doc["mac"]           = deviceMac;
    doc["rssi"]          = WiFi.RSSI();
    doc["batteryLevel"]  = 100;  // ESP32 con USB = siempre 100%
    doc["ipAddress"]     = WiFi.localIP().toString();

    // Informar cuál radio está activa
    switch (currentRadio) {
        case RADIO_BT:   doc["technology"] = "bluetooth"; break;
        case RADIO_WIFI:  doc["technology"] = "wifi"; break;
        case RADIO_BOTH:  doc["technology"] = "wifi+bluetooth"; break;
        case RADIO_OFF:   doc["technology"] = "wifi"; break; // WiFi de control
    }

    char buffer[512];
    serializeJson(doc, buffer, sizeof(buffer));

    bool published = mqttClient.publish(topicMetrics.c_str(), buffer, false);

    Serial.print("[Telemetría] RSSI=");
    Serial.print(WiFi.RSSI());
    Serial.print(" dBm, Radio=");
    switch (currentRadio) {
        case RADIO_BT:   Serial.print("BT"); break;
        case RADIO_WIFI:  Serial.print("WiFi"); break;
        case RADIO_BOTH:  Serial.print("WiFi+BT"); break;
        case RADIO_OFF:   Serial.print("OFF"); break;
    }
    Serial.println(published ? " → publicado" : " → ERROR");
}

// ══════════════════════════════════════════════
//  AUTO-REGISTRO — Enviar datos del dispositivo
// ══════════════════════════════════════════════

/**
 * Publica registro al tópico `dispositivo/{MAC}/registro`.
 *
 * Formato:
 * {
 *   "mac": "AA:BB:CC:DD:EE:FF",
 *   "name": "ESP32-Nodo-01",
 *   "deviceType": "IOT",
 *   "ipAddress": "192.168.18.50"
 * }
 */
void publishRegistration() {
    if (!mqttClient.connected()) return;

    JsonDocument doc;
    doc["mac"]        = deviceMac;
    doc["name"]       = DEVICE_NAME;
    doc["deviceType"] = DEVICE_TYPE;
    doc["ipAddress"]  = WiFi.localIP().toString();

    char buffer[256];
    serializeJson(doc, buffer, sizeof(buffer));

    bool published = mqttClient.publish(topicRegistro.c_str(), buffer, false);

    Serial.print("[Registro] ");
    Serial.print(deviceMac);
    Serial.print(" como ");
    Serial.print(DEVICE_NAME);
    Serial.println(published ? " → publicado" : " → ERROR");
}

// ══════════════════════════════════════════════
//  UTILIDADES
// ══════════════════════════════════════════════

/**
 * Obtiene la MAC WiFi del ESP32 en formato "AA:BB:CC:DD:EE:FF".
 */
String getFormattedMac() {
    String mac = WiFi.macAddress();
    mac.toUpperCase();
    return mac;
}
