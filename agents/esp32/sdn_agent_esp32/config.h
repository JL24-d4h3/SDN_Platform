/**
 * ═══════════════════════════════════════════════════════════════
 *  SDN Data Plane Agent — ESP32 — Configuración
 * ═══════════════════════════════════════════════════════════════
 *
 * Ajustar estos valores según tu red LAN antes de flashear.
 *
 * IMPORTANTE: El DEVICE_MAC debe coincidir con la MAC real del ESP32
 *             o con la MAC que registraste en el controlador SDN.
 *             Puedes obtenerla con WiFi.macAddress() en el setup().
 */

#ifndef CONFIG_H
#define CONFIG_H

// ──────────────────────────────────────────────
//  Red WiFi para conectarse al broker MQTT
//  (esta es la WiFi de control/señalización, NO la de datos)
// ──────────────────────────────────────────────
#define WIFI_SSID_CONTROL    "TU_RED_WIFI"        // ← Tu WiFi de casa/lab
#define WIFI_PASS_CONTROL    "TU_PASSWORD_WIFI"    // ← Password de tu WiFi

// ──────────────────────────────────────────────
//  Broker MQTT (IP de la laptop con Mosquitto)
// ──────────────────────────────────────────────
#define MQTT_BROKER_IP       "192.168.18.20"       // ← IP de tu laptop
#define MQTT_BROKER_PORT     1883
#define MQTT_CLIENT_ID       "esp32-agent-01"

// ──────────────────────────────────────────────
//  Identificación del dispositivo
// ──────────────────────────────────────────────
// Se auto-detecta en runtime (WiFi.macAddress()).
// Si necesitas forzar una MAC específica, descomenta:
// #define DEVICE_MAC_OVERRIDE "AA:BB:CC:DD:EE:FF"

#define DEVICE_NAME          "ESP32-Nodo-01"
#define DEVICE_TYPE          "IOT"                 // PHONE, LAPTOP, TABLET, IOT

// ──────────────────────────────────────────────
//  Intervalos (milisegundos)
// ──────────────────────────────────────────────
#define TELEMETRY_INTERVAL_MS   30000   // Enviar métricas cada 30 seg
#define MQTT_RECONNECT_DELAY_MS 5000    // Reintentar conexión cada 5 seg

// ──────────────────────────────────────────────
//  Tópicos MQTT (coinciden con el controlador)
//  {MAC} se reemplaza en runtime
// ──────────────────────────────────────────────
// Suscripción: dispositivo/{MAC}/comando
// Publicación: dispositivo/{MAC}/metrics
// Publicación: dispositivo/{MAC}/registro

#endif // CONFIG_H
