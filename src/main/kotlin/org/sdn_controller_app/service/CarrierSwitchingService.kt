package org.sdn_controller_app.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.sdn_controller_app.model.CarrierCommand
import org.sdn_controller_app.model.NetworkEventRequest
import org.sdn_controller_app.mqtt.MqttGateway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Servicio de Carrier Switching (Plano de Control SDN).
 *
 * Evalúa cada solicitud de contenido y decide el canal de transmisión óptimo:
 *
 * | Condición                                         | Acción       |
 * |---------------------------------------------------|--------------|
 * | Respuesta de texto / IA Llama / archivo < 10 MB   | PREPARE_BT   |
 * | Video / navegación web / archivo >= 10 MB          | SWITCH_WIFI  |
 *
 * El comando resultante se publica por MQTT al tópico `dispositivo/{MAC}/comando`.
 */
@Service
class CarrierSwitchingService(
    private val mqttGateway: MqttGateway,
    private val objectMapper: ObjectMapper,
    private val deviceService: DeviceService
) {

    private val log = LoggerFactory.getLogger(CarrierSwitchingService::class.java)

    @Value("\${wifi.ssid}")
    private lateinit var wifiSsid: String

    @Value("\${wifi.password}")
    private lateinit var wifiPassword: String

    @Value("\${carrier.file-size-threshold-mb}")
    private var thresholdMb: Long = 10

    companion object {
        private const val BYTES_PER_MB = 1_048_576L

        private val VIDEO_TYPES = setOf(
            "video", "video/mp4", "video/avi", "video/mkv",
            "video/webm", "streaming"
        )
        private val WEB_TYPES = setOf(
            "web", "navigation", "browser", "http", "html"
        )
        private val TEXT_AI_TYPES = setOf(
            "text", "llama", "ia_llama", "ai_response", "text/plain"
        )
    }

    /**
     * Evalúa la solicitud, decide el canal y publica el comando MQTT.
     * @return El comando generado con la acción decidida.
     */
    fun evaluateAndDispatch(request: NetworkEventRequest): CarrierCommand {
        val mac = request.deviceMac.uppercase().trim()

        // ── Verificar si el dispositivo está registrado ──
        val device = deviceService.findByMac(mac)
        if (device == null) {
            log.warn("⚠ Dispositivo NO registrado: {}. Se procesará pero se recomienda registrar.", mac)
        } else {
            log.info("✓ Dispositivo conocido: {} ({}) — online={}, tech={}",
                device.mac, device.name, device.online, device.activeTechnology)
        }

        val command = decide(request)
        val topic = "dispositivo/${mac}/comando"
        val json = objectMapper.writeValueAsString(command)

        log.info("──── Carrier Switching Decision ─────────────────────")
        log.info("  Device  : {} {}", mac, if (device != null) "(${device.name})" else "(no registrado)")
        log.info("  Content : {} ({} bytes)", request.contentType, request.fileSize)
        log.info("  Action  : {}", command.action)
        log.info("  Topic   : {}", topic)
        log.info("─────────────────────────────────────────────────────")

        mqttGateway.publish(topic, json)
        return command
    }

    private fun decide(request: NetworkEventRequest): CarrierCommand {
        val contentTypeLower = request.contentType.lowercase()
        val fileSizeMb = request.fileSize / BYTES_PER_MB

        // ── Regla 1: Video o navegación web → WiFi ──
        if (VIDEO_TYPES.any { contentTypeLower.contains(it) } ||
            WEB_TYPES.any { contentTypeLower.contains(it) }
        ) {
            return CarrierCommand(
                action = "SWITCH_WIFI",
                ssid = wifiSsid,
                password = wifiPassword,
                reason = "Contenido de alto ancho de banda: ${request.contentType}"
            )
        }

        // ── Regla 2: Texto / IA Llama o archivo < 10 MB → Bluetooth ──
        if (TEXT_AI_TYPES.any { contentTypeLower.contains(it) } || fileSizeMb < thresholdMb) {
            return CarrierCommand(
                action = "PREPARE_BT",
                reason = "Contenido ligero: ${request.contentType}, tamaño: ${fileSizeMb} MB"
            )
        }

        // ── Regla 3: Archivo grande (fallback) → WiFi ──
        return CarrierCommand(
            action = "SWITCH_WIFI",
            ssid = wifiSsid,
            password = wifiPassword,
            reason = "Archivo grande: ${fileSizeMb} MB supera umbral de ${thresholdMb} MB"
        )
    }
}
