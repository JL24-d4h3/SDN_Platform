package org.sdn_controller_app.model

/**
 * DTO para registrar un dispositivo manualmente via REST.
 */
data class DeviceRegistrationRequest(
    val mac: String,
    val name: String = "Dispositivo",
    val deviceType: String = "UNKNOWN",
    val ipAddress: String? = null
)
