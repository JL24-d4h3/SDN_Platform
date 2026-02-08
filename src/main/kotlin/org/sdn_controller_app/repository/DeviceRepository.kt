package org.sdn_controller_app.repository

import org.sdn_controller_app.model.Device
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * Repositorio JPA para la entidad Device.
 * Spring Data genera la implementación automáticamente.
 */
@Repository
interface DeviceRepository : JpaRepository<Device, String> {

    /** Buscar todos los dispositivos actualmente en línea. */
    fun findByOnlineTrue(): List<Device>

    /** Buscar por tipo de dispositivo. */
    fun findByDeviceType(deviceType: org.sdn_controller_app.model.DeviceType): List<Device>

    /** Verificar si un dispositivo con esta MAC existe. */
    fun existsByMac(mac: String): Boolean
}
