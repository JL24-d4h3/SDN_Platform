package org.sdn_controller_app.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Tarea programada que cada 60 segundos revisa si hay dispositivos
 * que no han enviado telemetría en los últimos 2 minutos y los marca offline.
 */
@Component
@EnableScheduling
class DeviceHealthChecker(
    private val deviceService: DeviceService
) {

    private val log = LoggerFactory.getLogger(DeviceHealthChecker::class.java)

    @Scheduled(fixedRate = 60_000) // cada 60 segundos
    fun checkDeviceHealth() {
        deviceService.markStaleDevicesOffline(timeoutSeconds = 120)
    }
}
