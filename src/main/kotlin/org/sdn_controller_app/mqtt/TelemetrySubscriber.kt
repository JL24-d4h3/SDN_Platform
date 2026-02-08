package org.sdn_controller_app.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Suscriptor de telemetría MQTT.
 *
 * Escucha el tópico `dispositivo/+/metrics` y registra en consola
 * el RSSI y la tecnología activa reportada por cada agente.
 *
 * Payload esperado (JSON):
 * ```json
 * {
 *   "rssi": -45,
 *   "technology": "bluetooth",
 *   "battery": 78
 * }
 * ```
 */
@Component
class TelemetrySubscriber(
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(TelemetrySubscriber::class.java)

    @ServiceActivator(inputChannel = "mqttInboundChannel")
    fun handleTelemetry(message: Message<String>) {
        val topic = message.headers["mqtt_receivedTopic"] as? String ?: "unknown"
        val payload = message.payload

        // Extraer MAC del tópico: dispositivo/{MAC}/metrics
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

            // Registrar métricas adicionales si existen
            metrics.filterKeys { it !in setOf("rssi", "technology") }
                .forEach { (key, value) -> log.info("  {} : {}", key, value) }
        } catch (e: Exception) {
            log.warn("  No se pudo parsear el payload de métricas: {}", e.message)
            log.info("  Payload crudo: {}", payload)
        }

        log.info("═══════════════════════════════════════════════════════")
    }
}
