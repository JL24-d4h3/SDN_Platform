/**
 * ═══════════════════════════════════════════════════════════════
 *  SDN Data Plane Agent — LILYGO T-Lora C6 — Configuración
 * ═══════════════════════════════════════════════════════════════
 *
 * El LILYGO T-Lora C6 usa el chip ESP32-C6 que soporta:
 *   - WiFi 6 (802.11ax) — 2.4 GHz
 *   - BLE 5.0 con Coded PHY (Long Range ~100m+)
 *   - IEEE 802.15.4 (Thread/Zigbee)
 *   - LoRa SX1262 (largo alcance ~2-15 km)
 *
 * NOTA: El ESP32-C6 NO soporta Bluetooth Classic (SPP).
 *       Solo BLE 5.0. Para transferencia de datos, usamos
 *       BLE con Coded PHY (S=8) que alcanza 100m+ a 125 kbps.
 */

#ifndef CONFIG_H
#define CONFIG_H

// ──────────────────────────────────────────────
//  Red WiFi para conectarse al broker MQTT
// ──────────────────────────────────────────────
#define WIFI_SSID_CONTROL    "TU_RED_WIFI"
#define WIFI_PASS_CONTROL    "TU_PASSWORD_WIFI"

// ──────────────────────────────────────────────
//  Broker MQTT
// ──────────────────────────────────────────────
#define MQTT_BROKER_IP       "192.168.18.20"
#define MQTT_BROKER_PORT     1883
#define MQTT_CLIENT_ID       "lilygo-tlora-c6-01"

// ──────────────────────────────────────────────
//  Identificación del dispositivo
// ──────────────────────────────────────────────
#define DEVICE_NAME          "LILYGO-TLora-C6-01"
#define DEVICE_TYPE          "IOT"

// ──────────────────────────────────────────────
//  Intervalos (milisegundos)
// ──────────────────────────────────────────────
#define TELEMETRY_INTERVAL_MS   30000
#define MQTT_RECONNECT_DELAY_MS 5000

// ──────────────────────────────────────────────
//  BLE 5.0 Coded PHY (Long Range)
// ──────────────────────────────────────────────
// Nombre del servicio BLE para la red SDN
#define BLE_SERVICE_NAME     "SDN-TLoraC6-01"

// UUID del servicio SDN (generado: puedes cambiarlo)
#define SDN_SERVICE_UUID     "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define SDN_CHAR_TX_UUID     "beb5483e-36e1-4688-b7f5-ea07361b26a8"  // Envío
#define SDN_CHAR_RX_UUID     "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  // Recepción

// PHY para Long Range:
// BLE_PHY_CODED    → S=8 (125 kbps, máximo alcance ~100m+)
// BLE_PHY_2M       → 2M PHY (alta velocidad, menor alcance)
// BLE_PHY_1M       → 1M PHY (estándar BLE)
#define BLE_PREFERRED_PHY    BLE_PHY_CODED  // ← LONG RANGE

// ──────────────────────────────────────────────
//  LoRa SX1262 — Configuración
// ──────────────────────────────────────────────
// Frecuencia según tu región:
// 915.0 MHz → América (US, LATAM)
// 868.0 MHz → Europa (EU)
// 433.0 MHz → Asia
#define LORA_FREQUENCY       915.0   // MHz (ajustar para Perú)
#define LORA_BANDWIDTH       125.0   // kHz
#define LORA_SPREADING       12      // SF7-SF12 (12 = máximo alcance)
#define LORA_CODING_RATE     8       // 4/5 a 4/8 (8 = más robusto)
#define LORA_TX_POWER        22      // dBm (máximo del SX1262)
#define LORA_PREAMBLE        8
#define LORA_SYNC_WORD       0x12    // Red privada LoRa

// Pines del SX1262 en LILYGO T-Lora C6
// (verificar con la documentación de tu placa específica)
#define LORA_NSS_PIN         18
#define LORA_RST_PIN         23
#define LORA_DIO1_PIN        33
#define LORA_BUSY_PIN        34

#endif // CONFIG_H
