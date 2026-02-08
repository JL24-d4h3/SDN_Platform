package org.sdn_controller_app.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Comando JSON enviado al dispositivo vía MQTT al tópico `dispositivo/{MAC}/comando`.
 *
 * - ACTION = "PREPARE_BT"  → El agente debe preparar Bluetooth para recepción.
 * - ACTION = "SWITCH_WIFI" → El agente debe conectarse a la red WiFi de alta velocidad.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CarrierCommand(
    val action: String,
    val ssid: String? = null,
    val password: String? = null,
    val reason: String
)
