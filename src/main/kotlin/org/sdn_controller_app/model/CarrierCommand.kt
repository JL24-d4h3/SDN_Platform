package org.sdn_controller_app.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Comando JSON enviado al dispositivo vía MQTT al tópico `dispositivo/{MAC}/comando`.
 *
 * Acciones soportadas:
 * - "PREPARE_BT"   → El agente debe activar Bluetooth para enviar/recibir datos.
 * - "SWITCH_WIFI"  → El agente debe conectarse a la red WiFi de alta velocidad.
 * - "RELEASE_RADIO"→ El agente debe apagar la radio que fue activada para esta sesión.
 *                     Se envía al finalizar una sesión para liberar recursos de radio.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CarrierCommand(
    val action: String,
    val ssid: String? = null,
    val password: String? = null,
    val reason: String
)
