package org.sdn_controller_app.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import org.sdn_controller_app.service.DeviceService
import org.slf4j.LoggerFactory
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Suscriptor de telemetría MQTT.
 *
 * Escucha el tópico `dispositivo/+/metrics` y:
 * 1. Auto-registra dispositivos desconocidos en la base de datos.
 * 2. Actualiza métricas (RSSI, tecnología, batería) de dispositivos conocidos.
 * 3. Registra en consola (log) el RSSI y la tecnología activa.
 *
 * Payload esperado (JSON):
 * ```json
 * {
 *   "rssi": -45,
 *   "technology": "bluetooth",
 *   "battery": 78,
 *   "deviceType": "PHONE",
 *   "ip": "192.168.1.50"
 * }
 * ```
 */
@Component
class TelemetrySubscriber(
    private val objectMapper: ObjectMapper,
    private val deviceService: DeviceService
) {

    private val log = LoggerFactory.getLogger(TelemetrySubscriber::class.java)

    @ServiceActivator(inputChannel = "mqttInboundChannel")
    fun handleInboundMessage(message: Message<String>) {
        val topic = message.headers["mqtt_receivedTopic"] as? String ?: "unknown"

        when {
            topic.endsWith("/metrics") -> handleTelemetry(topic, message.payload)
            topic.endsWith("/registro") -> handleRegistration(topic, message.payload)
            else -> log.warn("Tópico MQTT no reconocido: {}", topic)
        }
    }

    /**
     * Procesa telemetría: dispositivo/+/metrics
     */
    private fun handleTelemetry(topic: String, payload: String) {
        val mac = topic.split("/").getOrNull(1) ?: "unknown"

        log.info("═══════════════════════════════════════════════════════")
        log.info("  Telemetría recibida del dispositivo: {}", mac)
        log.info("  Tópico : {}", topic)

        try {
            @Suppress("UNCHECKED_CAST")
            val metrics = objectMapper.readValue(payload, Map::class.java) as Map<String, Any>

            val rssi = metrics["rssi"]
            val technology = metrics["technology"]

            log.info("  RSSI             : {} dBm", rssi)
            log.info("  Tecnología activa: {}", technology)

            metrics.filterKeys { it !in setOf("rssi", "technology") }
                .forEach { (key, value) -> log.info("  {} : {}", key, value) }

            // ── Auto-registro / actualización en BD ──
            val device = deviceService.autoRegisterFromTelemetry(mac, metrics)
            log.info("  Estado BD        : {} — online={}", device.name, device.online)

        } catch (e: Exception) {
            log.warn("  No se pudo parsear el payload de métricas: {}", e.message)
            log.info("  Payload crudo: {}", payload)
        }

        log.info("═══════════════════════════════════════════════════════")
    }

    /**
     * Procesa registro por MQTT: dispositivo/+/registro
     *
     * Un dispositivo puede registrarse publicando en su tópico de registro:
     * ```json
     * {
     *   "name": "Celular de Jorge",
     *   "deviceType": "PHONE",
     *   "ip": "192.168.1.50"
     * }
     * ```
     */
    private fun handleRegistration(topic: String, payload: String) {
        val mac = topic.split("/").getOrNull(1) ?: "unknown"

        log.info("╔═══════════════════════════════════════════════════════")
        log.info("║  Solicitud de registro MQTT del dispositivo: {}", mac)

        try {
            @Suppress("UNCHECKED_CAST")
            val data = objectMapper.readValue(payload, Map::class.java) as Map<String, Any>

            val request = org.sdn_controller_app.model.DeviceRegistrationRequest(
                mac = mac,
                name = (data["name"] as? String) ?: "Agente MQTT ($mac)",
                deviceType = (data["deviceType"] as? String) ?: "UNKNOWN",
                ipAddress = data["ip"] as? String
            )

            val device = deviceService.registerDevice(request)
            log.info("║  Registrado: {} ({}) — tipo: {}", device.mac, device.name, device.deviceType)

        } catch (e: Exception) {
            log.warn("║  Error procesando registro: {}", e.message)
            log.info("║  Payload: {}", payload)
        }

        log.info("╚═══════════════════════════════════════════════════════")
    }
}
