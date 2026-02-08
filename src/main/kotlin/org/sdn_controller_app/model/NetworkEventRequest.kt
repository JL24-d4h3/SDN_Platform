package org.sdn_controller_app.model

/**
 * DTO de entrada para el endpoint POST /network/event.
 *
 * @property deviceMac  Dirección MAC del dispositivo móvil (Agente).
 * @property contentType Tipo de contenido solicitado (ej: "video/mp4", "text", "llama", "web").
 * @property fileSize   Tamaño del archivo en bytes.
 */
data class NetworkEventRequest(
    val deviceMac: String,
    val contentType: String,
    val fileSize: Long
)
