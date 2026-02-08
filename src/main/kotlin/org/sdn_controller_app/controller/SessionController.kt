package org.sdn_controller_app.controller

import org.sdn_controller_app.model.ContentRequest
import org.sdn_controller_app.model.RequestSession
import org.sdn_controller_app.model.ResponseReady
import org.sdn_controller_app.service.SessionService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * API REST para el ciclo de vida de sesiones de solicitud.
 *
 * Flujo completo:
 * ```
 * [Celular]                     [Controlador SDN]               [Gateway/Servidor]
 *    │                                │                                │
 *    │  POST /sessions/request        │                                │
 *    │ ──────────────────────────────>│                                │
 *    │                                │ → PREPARE_BT (MQTT)            │
 *    │  <─────────────────────────────│                                │
 *    │                                │                                │
 *    │  POST /sessions/{id}/processing│                                │
 *    │ ──────────────────────────────>│                                │
 *    │                                │                                │
 *    │                                │  POST /sessions/{id}/response  │
 *    │                                │ <─────────────────────────────│
 *    │                                │ → SWITCH_WIFI o PREPARE_BT     │
 *    │  <─────────────────────────────│                                │
 *    │                                │                                │
 *    │  POST /sessions/{id}/delivered │                                │
 *    │ ──────────────────────────────>│                                │
 *    │                                │ → RELEASE_RADIO                │
 *    │  <─────────────────────────────│                                │
 *    │                                │                                │
 *    │  GET /sessions/{id}            │                                │
 *    │ ──────────────────────────────>│                                │
 * ```
 */
@RestController
@RequestMapping("/sessions")
class SessionController(
    private val sessionService: SessionService
) {

    private val log = LoggerFactory.getLogger(SessionController::class.java)

    // ═════════════════════════════════════════════════════════
    //  POST /sessions/request — El celular inicia una solicitud
    // ═════════════════════════════════════════════════════════

    /**
     * Inicia una nueva sesión de solicitud.
     *
     * Ejemplo:
     * ```
     * POST /sessions/request
     * {
     *   "originMac": "AA:BB:CC:DD:EE:FF",
     *   "accessNodeMac": "11:22:33:44:55:66",
     *   "query": "algoritmo de dijkstra explicación",
     *   "expectedContentType": "text"
     * }
     * ```
     */
    @PostMapping("/request")
    fun initiateRequest(@RequestBody request: ContentRequest): ResponseEntity<RequestSession> {
        log.info("Nueva solicitud de contenido: origin={}, query='{}'",
            request.originMac, request.query.take(50))
        val session = sessionService.initiateRequest(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(session)
    }

    // ═════════════════════════════════════════════════════════
    //  POST /sessions/{id}/processing — Solicitud en tránsito
    // ═════════════════════════════════════════════════════════

    /**
     * Marca la sesión como "en procesamiento" (la solicitud viaja por la red).
     */
    @PostMapping("/{sessionId}/processing")
    fun markProcessing(@PathVariable sessionId: String): ResponseEntity<RequestSession> {
        val session = sessionService.markProcessing(sessionId)
        return ResponseEntity.ok(session)
    }

    // ═════════════════════════════════════════════════════════
    //  POST /sessions/{id}/response — La respuesta está lista
    // ═════════════════════════════════════════════════════════

    /**
     * El gateway notifica que la respuesta está lista.
     * El controlador decide el canal de vuelta.
     *
     * Ejemplo:
     * ```
     * POST /sessions/abc123/response
     * {
     *   "sessionId": "abc123",
     *   "contentType": "video/mp4",
     *   "responseSize": 52428800
     * }
     * ```
     */
    @PostMapping("/{sessionId}/response")
    fun responseReady(
        @PathVariable sessionId: String,
        @RequestBody response: ResponseReady
    ): ResponseEntity<RequestSession> {
        // Asegurar consistencia del sessionId
        val normalized = response.copy(sessionId = sessionId)
        val session = sessionService.handleResponseReady(normalized)
        return ResponseEntity.ok(session)
    }

    // ═════════════════════════════════════════════════════════
    //  POST /sessions/{id}/delivered — Respuesta recibida
    // ═════════════════════════════════════════════════════════

    /**
     * El dispositivo confirma que recibió la respuesta.
     * Esto dispara el cierre automático de sesión y la liberación de radios.
     */
    @PostMapping("/{sessionId}/delivered")
    fun confirmDelivery(@PathVariable sessionId: String): ResponseEntity<RequestSession> {
        val session = sessionService.confirmDelivery(sessionId)
        return ResponseEntity.ok(session)
    }

    // ═════════════════════════════════════════════════════════
    //  Consultas
    // ═════════════════════════════════════════════════════════

    /** Obtener una sesión por su ID. */
    @GetMapping("/{sessionId}")
    fun getSession(@PathVariable sessionId: String): ResponseEntity<RequestSession> {
        val session = sessionService.findById(sessionId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(session)
    }

    /** Listar todas las sesiones activas (no cerradas). */
    @GetMapping("/active")
    fun getActiveSessions(): ResponseEntity<List<RequestSession>> {
        return ResponseEntity.ok(sessionService.findAllActive())
    }

    /** Listar sesiones activas de un dispositivo específico. */
    @GetMapping("/device/{mac}/active")
    fun getActiveSessionsByDevice(@PathVariable mac: String): ResponseEntity<List<RequestSession>> {
        return ResponseEntity.ok(sessionService.findActiveByDevice(mac))
    }

    /** Historial completo de sesiones de un dispositivo. */
    @GetMapping("/device/{mac}")
    fun getSessionsByDevice(@PathVariable mac: String): ResponseEntity<List<RequestSession>> {
        return ResponseEntity.ok(sessionService.findAllByDevice(mac))
    }
}
