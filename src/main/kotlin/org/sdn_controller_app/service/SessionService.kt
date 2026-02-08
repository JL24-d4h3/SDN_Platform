package org.sdn_controller_app.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.sdn_controller_app.model.*
import org.sdn_controller_app.mqtt.MqttGateway
import org.sdn_controller_app.repository.SessionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Servicio de gestión de sesiones de solicitud.
 *
 * Gestiona el ciclo de vida completo de una solicitud:
 *
 * ```
 * Celular presiona Enter
 *       │
 *       ▼
 *  [1] initiateRequest()
 *       → Crea sesión CREATED
 *       → Decide canal de IDA (BT para solicitudes ligeras)
 *       → Envía PREPARE_BT al celular y al nodo de acceso
 *       → Estado pasa a OUTBOUND
 *       │
 *       ▼
 *  [2] markProcessing()
 *       → El nodo de acceso confirma que recibió la solicitud
 *       → Estado pasa a PROCESSING
 *       │
 *       ▼
 *  [3] handleResponseReady()
 *       → La respuesta está lista en el gateway
 *       → Decide canal de VUELTA (BT si < 10MB, WiFi si >= 10MB o video)
 *       → Envía PREPARE_BT o SWITCH_WIFI al celular
 *       → Estado pasa a INBOUND
 *       │
 *       ▼
 *  [4] confirmDelivery()
 *       → El dispositivo confirma que recibió la respuesta
 *       → Estado pasa a DELIVERED
 *       │
 *       ▼
 *  [5] closeSession()
 *       → Envía RELEASE_RADIO a todos los dispositivos involucrados
 *       → Estado pasa a CLOSED
 *       → Las radios se apagan
 * ```
 */
@Service
class SessionService(
    private val sessionRepository: SessionRepository,
    private val deviceService: DeviceService,
    private val mqttGateway: MqttGateway,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(SessionService::class.java)

    @Value("\${wifi.ssid}")
    private lateinit var wifiSsid: String

    @Value("\${wifi.password}")
    private lateinit var wifiPassword: String

    @Value("\${carrier.file-size-threshold-mb}")
    private var thresholdMb: Long = 10

    companion object {
        private const val BYTES_PER_MB = 1_048_576L
    }

    // ═════════════════════════════════════════════════════════
    //  [1] INICIAR SOLICITUD — El celular da Enter
    // ═════════════════════════════════════════════════════════

    /**
     * Crea una nueva sesión de solicitud y envía el comando de activación
     * de radio al dispositivo origen (y al nodo de acceso si se conoce).
     *
     * @return La sesión creada con el canal de ida decidido.
     */
    fun initiateRequest(request: ContentRequest): RequestSession {
        val mac = request.originMac.uppercase().trim()

        // Crear la sesión
        val session = RequestSession(
            originMac = mac,
            accessNodeMac = request.accessNodeMac?.uppercase()?.trim(),
            requestPayload = request.query,
            contentType = request.expectedContentType,
            outboundCarrier = RadioCarrier.BLUETOOTH, // Solicitudes siempre van por BT (ligeras)
            status = SessionStatus.OUTBOUND,
            updatedAt = Instant.now()
        )
        val saved = sessionRepository.save(session)

        log.info("╔═══════════════════════════════════════════════════════")
        log.info("║ NUEVA SESIÓN: {}", saved.sessionId)
        log.info("║ Origen     : {}", mac)
        log.info("║ Query      : {}", request.query.take(80))
        log.info("║ Canal IDA  : {}", saved.outboundCarrier)
        log.info("╚═══════════════════════════════════════════════════════")

        // Comando al celular: "enciende Bluetooth para enviar tu solicitud"
        sendCommand(mac, CarrierCommand(
            action = "PREPARE_BT",
            reason = "Sesión ${saved.sessionId}: activar BT para enviar solicitud"
        ), saved.sessionId)

        // Si hay nodo de acceso conocido, también despertarlo
        if (request.accessNodeMac != null) {
            val nodeMac = request.accessNodeMac.uppercase().trim()
            sendCommand(nodeMac, CarrierCommand(
                action = "PREPARE_BT",
                reason = "Sesión ${saved.sessionId}: nodo de acceso, preparar BT para recibir solicitud"
            ), saved.sessionId)
        }

        return saved
    }

    // ═════════════════════════════════════════════════════════
    //  [2] SOLICITUD EN TRÁNSITO
    // ═════════════════════════════════════════════════════════

    fun markProcessing(sessionId: String): RequestSession {
        val session = findSessionOrThrow(sessionId)
        session.status = SessionStatus.PROCESSING
        session.updatedAt = Instant.now()
        val saved = sessionRepository.save(session)

        log.info("║ Sesión {} → PROCESSING (solicitud viajando por la red)", sessionId)

        return saved
    }

    // ═════════════════════════════════════════════════════════
    //  [3] RESPUESTA LISTA — Decidir canal de vuelta
    // ═════════════════════════════════════════════════════════

    /**
     * La respuesta está lista. El controlador decide si vuelve por BT o WiFi.
     */
    fun handleResponseReady(response: ResponseReady): RequestSession {
        val session = findSessionOrThrow(response.sessionId)

        // ── Decidir canal de vuelta ──
        val inboundCarrier = decideInboundCarrier(response.contentType, response.responseSize)

        session.responseSize = response.responseSize
        session.inboundCarrier = inboundCarrier
        session.contentType = response.contentType
        session.status = SessionStatus.INBOUND
        session.updatedAt = Instant.now()
        val saved = sessionRepository.save(session)

        val responseSizeMb = response.responseSize / BYTES_PER_MB

        log.info("╔═══════════════════════════════════════════════════════")
        log.info("║ RESPUESTA LISTA — Sesión: {}", session.sessionId)
        log.info("║ Tipo       : {}", response.contentType)
        log.info("║ Tamaño     : {} bytes ({} MB)", response.responseSize, responseSizeMb)
        log.info("║ Canal VUELTA: {}", inboundCarrier)
        log.info("╚═══════════════════════════════════════════════════════")

        // Enviar comando al dispositivo origen según canal de vuelta
        when (inboundCarrier) {
            RadioCarrier.WIFI -> {
                sendCommand(session.originMac, CarrierCommand(
                    action = "SWITCH_WIFI",
                    ssid = wifiSsid,
                    password = wifiPassword,
                    reason = "Sesión ${session.sessionId}: recibir respuesta de ${responseSizeMb} MB por WiFi"
                ), session.sessionId)
            }
            RadioCarrier.BLUETOOTH -> {
                sendCommand(session.originMac, CarrierCommand(
                    action = "PREPARE_BT",
                    reason = "Sesión ${session.sessionId}: recibir respuesta ligera por BT"
                ), session.sessionId)
            }
            RadioCarrier.LORA -> {
                // LoRa no se usa para datos, solo señalización (futuro)
                log.info("║ LoRa: solo señalización, no se envía comando de datos")
            }
        }

        // Si hay nodo de acceso, comandarle el canal de vuelta también
        session.accessNodeMac?.let { nodeMac ->
            when (inboundCarrier) {
                RadioCarrier.WIFI -> sendCommand(nodeMac, CarrierCommand(
                    action = "SWITCH_WIFI",
                    ssid = wifiSsid,
                    password = wifiPassword,
                    reason = "Sesión ${session.sessionId}: nodo acceso, entregar respuesta por WiFi"
                ), session.sessionId)
                RadioCarrier.BLUETOOTH -> sendCommand(nodeMac, CarrierCommand(
                    action = "PREPARE_BT",
                    reason = "Sesión ${session.sessionId}: nodo acceso, entregar respuesta por BT"
                ), session.sessionId)
                else -> {}
            }
        }

        return saved
    }

    // ═════════════════════════════════════════════════════════
    //  [4] ENTREGA CONFIRMADA
    // ═════════════════════════════════════════════════════════

    fun confirmDelivery(sessionId: String): RequestSession {
        val session = findSessionOrThrow(sessionId)
        session.status = SessionStatus.DELIVERED
        session.updatedAt = Instant.now()
        val saved = sessionRepository.save(session)

        log.info("║ Sesión {} → DELIVERED (respuesta recibida por el dispositivo)", sessionId)

        // Auto-cerrar después de confirmar entrega
        return closeSession(sessionId)
    }

    // ═════════════════════════════════════════════════════════
    //  [5] CERRAR SESIÓN — Apagar radios
    // ═════════════════════════════════════════════════════════

    /**
     * Envía RELEASE_RADIO a todos los dispositivos involucrados
     * para que apaguen las radios que se activaron para esta sesión.
     */
    fun closeSession(sessionId: String): RequestSession {
        val session = findSessionOrThrow(sessionId)
        session.status = SessionStatus.CLOSED
        session.updatedAt = Instant.now()
        session.closedAt = Instant.now()
        val saved = sessionRepository.save(session)

        log.info("╔═══════════════════════════════════════════════════════")
        log.info("║ CERRANDO SESIÓN: {}", sessionId)

        // Liberar radio del dispositivo origen
        val radioToRelease = session.inboundCarrier ?: session.outboundCarrier
        sendCommand(session.originMac, CarrierCommand(
            action = "RELEASE_RADIO",
            reason = "Sesión ${sessionId}: solicitud completada, liberar ${radioToRelease.name}"
        ), sessionId)

        // Liberar radio del nodo de acceso si existe
        session.accessNodeMac?.let { nodeMac ->
            sendCommand(nodeMac, CarrierCommand(
                action = "RELEASE_RADIO",
                reason = "Sesión ${sessionId}: nodo acceso, liberar radio"
            ), sessionId)
        }

        log.info("║ Radios liberadas. Sesión cerrada.")
        log.info("╚═══════════════════════════════════════════════════════")

        return saved
    }

    // ═════════════════════════════════════════════════════════
    //  Consultas
    // ═════════════════════════════════════════════════════════

    fun findById(sessionId: String): RequestSession? =
        sessionRepository.findById(sessionId).orElse(null)

    fun findActiveByDevice(mac: String): List<RequestSession> =
        sessionRepository.findByOriginMacAndStatusNot(mac.uppercase().trim(), SessionStatus.CLOSED)

    fun findAllByDevice(mac: String): List<RequestSession> =
        sessionRepository.findByOriginMacOrderByCreatedAtDesc(mac.uppercase().trim())

    fun findAllActive(): List<RequestSession> =
        sessionRepository.findByStatusNot(SessionStatus.CLOSED)

    // ═════════════════════════════════════════════════════════
    //  Helpers internos
    // ═════════════════════════════════════════════════════════

    private fun decideInboundCarrier(contentType: String, responseSize: Long): RadioCarrier {
        val ct = contentType.lowercase()
        val sizeMb = responseSize / BYTES_PER_MB

        // Video o web → WiFi
        if (ct.contains("video") || ct.contains("web") || ct.contains("html") || ct.contains("streaming")) {
            return RadioCarrier.WIFI
        }

        // Archivo grande → WiFi
        if (sizeMb >= thresholdMb) {
            return RadioCarrier.WIFI
        }

        // Texto, IA, archivos pequeños → BT
        return RadioCarrier.BLUETOOTH
    }

    private fun sendCommand(mac: String, command: CarrierCommand, sessionId: String) {
        val topic = "dispositivo/$mac/comando"
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "sessionId" to sessionId,
                "action" to command.action,
                "ssid" to command.ssid,
                "password" to command.password,
                "reason" to command.reason
            ).filterValues { it != null }
        )
        mqttGateway.publish(topic, payload)
        log.info("║ → Comando {} enviado a {} (sesión {})", command.action, mac, sessionId)
    }

    private fun findSessionOrThrow(sessionId: String): RequestSession =
        sessionRepository.findById(sessionId).orElseThrow {
            IllegalArgumentException("Sesión no encontrada: $sessionId")
        }
}
