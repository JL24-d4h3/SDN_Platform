package org.sdn_controller_app.model

/**
 * DTO para crear una nueva solicitud desde el celular.
 *
 * Ejemplo de uso real: el celular envía esta solicitud cuando el usuario
 * da Enter en la app de búsqueda.
 */
data class ContentRequest(
    /** MAC del celular que origina la solicitud. */
    val originMac: String,
    /** MAC del nodo de acceso híbrido más cercano (opcional, puede auto-descubrirse). */
    val accessNodeMac: String? = null,
    /** Contenido de la solicitud (ej: "el algoritmo más importante de todos los tiempos"). */
    val query: String,
    /** Tipo de contenido esperado: text, llama, video, web. */
    val expectedContentType: String = "text"
)

/**
 * DTO para notificar que la respuesta está lista para ser entregada.
 *
 * Lo invoca el gateway o servicio cuando tiene la respuesta.
 */
data class ResponseReady(
    /** ID de la sesión que se está respondiendo. */
    val sessionId: String,
    /** Tipo de contenido de la respuesta. */
    val contentType: String,
    /** Tamaño de la respuesta en bytes. */
    val responseSize: Long
)
