package org.sdn_controller_app.service

import org.sdn_controller_app.model.Device
import org.sdn_controller_app.model.DeviceRegistrationRequest
import org.sdn_controller_app.model.DeviceType
import org.sdn_controller_app.repository.DeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Servicio de gestión de dispositivos.
 *
 * Responsable de:
 * - Registrar dispositivos (manualmente vía REST o automáticamente vía telemetría MQTT).
 * - Actualizar estado y métricas de los dispositivos.
 * - Consultar el inventario de dispositivos conocidos.
 */
@Service
class DeviceService(
    private val deviceRepository: DeviceRepository
) {

    private val log = LoggerFactory.getLogger(DeviceService::class.java)

    // ─── Registro ───────────────────────────────────────────

    /**
     * Registra un nuevo dispositivo o actualiza uno existente.
     * @return El dispositivo registrado/actualizado.
     */
    fun registerDevice(request: DeviceRegistrationRequest): Device {
        val normalizedMac = request.mac.uppercase().trim()

        val existing = deviceRepository.findById(normalizedMac)
        if (existing.isPresent) {
            val device = existing.get()
            device.name = request.name
            device.deviceType = parseDeviceType(request.deviceType)
            device.ipAddress = request.ipAddress
            device.lastSeen = Instant.now()
            device.online = true
            val saved = deviceRepository.save(device)
            log.info("Dispositivo actualizado: {} ({})", saved.mac, saved.name)
            return saved
        }

        val device = Device(
            mac = normalizedMac,
            name = request.name,
            deviceType = parseDeviceType(request.deviceType),
            ipAddress = request.ipAddress,
            online = true,
            firstSeen = Instant.now(),
            lastSeen = Instant.now()
        )
        val saved = deviceRepository.save(device)
        log.info("▶ Nuevo dispositivo registrado: {} ({}) — tipo: {}", saved.mac, saved.name, saved.deviceType)
        return saved
    }

    /**
     * Auto-registro: se invoca cuando llegan métricas MQTT de un dispositivo desconocido.
     * Crea un registro mínimo para que el controlador lo reconozca.
     */
    fun autoRegisterFromTelemetry(mac: String, metrics: Map<String, Any>): Device {
        val normalizedMac = mac.uppercase().trim()

        val device = deviceRepository.findById(normalizedMac).orElse(null)
            ?: Device(
                mac = normalizedMac,
                name = "Auto-descubierto ($normalizedMac)",
                deviceType = DeviceType.UNKNOWN,
                firstSeen = Instant.now()
            )

        // Actualizar métricas
        device.activeTechnology = (metrics["technology"] as? String) ?: device.activeTechnology
        device.lastRssi = (metrics["rssi"] as? Number)?.toInt()
        device.batteryLevel = (metrics["battery"] as? Number)?.toInt()
        device.ipAddress = (metrics["ip"] as? String) ?: device.ipAddress
        device.online = true
        device.lastSeen = Instant.now()

        // Si el dispositivo reporta su tipo
        val reportedType = metrics["deviceType"] as? String
        if (reportedType != null) {
            device.deviceType = parseDeviceType(reportedType)
        }

        val saved = deviceRepository.save(device)

        if (device.firstSeen == device.lastSeen) {
            log.info("▶ Dispositivo auto-descubierto por telemetría: {} ({})", saved.mac, saved.activeTechnology)
        }

        return saved
    }

    // ─── Consultas ──────────────────────────────────────────

    fun findAll(): List<Device> = deviceRepository.findAll()

    fun findByMac(mac: String): Device? = deviceRepository.findById(mac.uppercase().trim()).orElse(null)

    fun findOnlineDevices(): List<Device> = deviceRepository.findByOnlineTrue()

    fun isKnownDevice(mac: String): Boolean = deviceRepository.existsByMac(mac.uppercase().trim())

    fun deleteDevice(mac: String): Boolean {
        val normalizedMac = mac.uppercase().trim()
        if (deviceRepository.existsByMac(normalizedMac)) {
            deviceRepository.deleteById(normalizedMac)
            log.info("Dispositivo eliminado: {}", normalizedMac)
            return true
        }
        return false
    }

    /**
     * Marca como offline dispositivos que no han enviado telemetría
     * en los últimos `timeoutSeconds` segundos.
     */
    fun markStaleDevicesOffline(timeoutSeconds: Long = 120) {
        val threshold = Instant.now().minusSeconds(timeoutSeconds)
        val stale = deviceRepository.findByOnlineTrue()
            .filter { it.lastSeen.isBefore(threshold) }

        stale.forEach {
            it.online = false
            deviceRepository.save(it)
            log.info("Dispositivo marcado offline (sin telemetría): {}", it.mac)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────

    private fun parseDeviceType(type: String): DeviceType {
        return try {
            DeviceType.valueOf(type.uppercase().trim())
        } catch (_: Exception) {
            DeviceType.UNKNOWN
        }
    }
}
