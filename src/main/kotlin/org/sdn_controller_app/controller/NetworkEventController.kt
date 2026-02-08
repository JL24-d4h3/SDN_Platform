package org.sdn_controller_app.controller

import org.sdn_controller_app.model.CarrierCommand
import org.sdn_controller_app.model.NetworkEventRequest
import org.sdn_controller_app.service.CarrierSwitchingService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * API REST de disparo del Controlador SDN.
 *
 * Endpoint principal:
 * ```
 * POST /network/event
 * Content-Type: application/json
 *
 * {
 *   "deviceMac": "AA:BB:CC:DD:EE:FF",
 *   "contentType": "video/mp4",
 *   "fileSize": 15728640
 * }
 * ```
 */
@RestController
@RequestMapping("/network")
class NetworkEventController(
    private val carrierSwitchingService: CarrierSwitchingService
) {

    private val log = LoggerFactory.getLogger(NetworkEventController::class.java)

    @PostMapping("/event")
    fun handleNetworkEvent(@RequestBody request: NetworkEventRequest): ResponseEntity<CarrierCommand> {
        log.info(
            "Evento de red recibido: device={}, contentType={}, fileSize={} bytes",
            request.deviceMac, request.contentType, request.fileSize
        )

        val command = carrierSwitchingService.evaluateAndDispatch(request)
        return ResponseEntity.ok(command)
    }
}
