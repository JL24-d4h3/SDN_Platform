package org.sdn_controller_app.controller

import org.sdn_controller_app.model.Device
import org.sdn_controller_app.model.DeviceRegistrationRequest
import org.sdn_controller_app.service.DeviceService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * API REST para gestión de dispositivos.
 *
 * Endpoints:
 *   POST   /devices/register         → Registrar un dispositivo manualmente
 *   GET    /devices                   → Listar todos los dispositivos
 *   GET    /devices/online            → Listar solo los que están conectados
 *   GET    /devices/{mac}             → Consultar un dispositivo por MAC
 *   DELETE /devices/{mac}             → Eliminar un dispositivo
 *   POST   /devices/cleanup           → Marcar offline los dispositivos inactivos
 */
@RestController
@RequestMapping("/devices")
class DeviceController(
    private val deviceService: DeviceService
) {

    private val log = LoggerFactory.getLogger(DeviceController::class.java)

    @PostMapping("/register")
    fun register(@RequestBody request: DeviceRegistrationRequest): ResponseEntity<Device> {
        log.info("Solicitud de registro: MAC={}, nombre={}, tipo={}", request.mac, request.name, request.deviceType)
        val device = deviceService.registerDevice(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(device)
    }

    @GetMapping
    fun listAll(): ResponseEntity<List<Device>> {
        return ResponseEntity.ok(deviceService.findAll())
    }

    @GetMapping("/online")
    fun listOnline(): ResponseEntity<List<Device>> {
        return ResponseEntity.ok(deviceService.findOnlineDevices())
    }

    @GetMapping("/{mac}")
    fun getByMac(@PathVariable mac: String): ResponseEntity<Device> {
        val device = deviceService.findByMac(mac)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(device)
    }

    @DeleteMapping("/{mac}")
    fun delete(@PathVariable mac: String): ResponseEntity<Map<String, String>> {
        return if (deviceService.deleteDevice(mac)) {
            ResponseEntity.ok(mapOf("status" to "deleted", "mac" to mac.uppercase()))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/cleanup")
    fun cleanup(@RequestParam(defaultValue = "120") timeoutSeconds: Long): ResponseEntity<Map<String, String>> {
        deviceService.markStaleDevicesOffline(timeoutSeconds)
        return ResponseEntity.ok(mapOf("status" to "cleanup ejecutado", "timeout" to "${timeoutSeconds}s"))
    }
}
