package org.sdn_controller_app.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Representa el ciclo de vida completo de una solicitud en la red SDN.
 *
 * Flujo:
 *   1. CREATED  → El celular envía una solicitud (ej: búsqueda "algoritmo más importante")
 *   2. OUTBOUND → El controlador decide canal de ida (siempre BT para solicitudes ligeras)
 *                  y envía PREPARE_BT al dispositivo origen.
 *   3. PROCESSING → La solicitud viaja por la red hacia el gateway/servicio.
 *   4. INBOUND  → La respuesta regresa. El controlador decide canal de vuelta (BT o WiFi)
 *                  según el peso de la respuesta.
 *   5. DELIVERED → La respuesta llegó al dispositivo. Se envía RELEASE_RADIO.
 *   6. CLOSED   → Radios apagadas, sesión cerrada.
 *
 * Esto permite al controlador saber CUÁNDO encender y apagar cada radio
 * en cada dispositivo involucrado.
 */
@Entity
@Table(name = "request_sessions")
data class RequestSession(

    @Id
    val sessionId: String = UUID.randomUUID().toString().substring(0, 8),

    /** MAC del dispositivo que originó la solicitud (el celular). */
    @Column(nullable = false, length = 17)
    val originMac: String,

    /** MAC del nodo de acceso híbrido más cercano (si se conoce). */
    @Column(length = 17)
    var accessNodeMac: String? = null,

    /** Contenido/query de la solicitud (ej: "algoritmo más importante"). */
    @Column(length = 500)
    var requestPayload: String? = null,

    /** Tipo de contenido solicitado. */
    @Column(nullable = false)
    var contentType: String = "text",

    /** Canal elegido para la IDA (solicitud): BLUETOOTH, WIFI, LORA. */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var outboundCarrier: RadioCarrier = RadioCarrier.BLUETOOTH,

    /** Canal elegido para la VUELTA (respuesta): BLUETOOTH, WIFI. */
    @Enumerated(EnumType.STRING)
    var inboundCarrier: RadioCarrier? = null,

    /** Tamaño de la respuesta en bytes (se llena cuando la respuesta regresa). */
    var responseSize: Long? = null,

    /** Estado actual del ciclo de vida. */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: SessionStatus = SessionStatus.CREATED,

    /** Timestamp de creación. */
    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    /** Timestamp de última actualización de estado. */
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),

    /** Timestamp de cierre (cuando se liberan las radios). */
    var closedAt: Instant? = null
)

enum class SessionStatus {
    /** Solicitud creada, aún no se ha decidido canal. */
    CREATED,
    /** Canal de ida elegido, comando enviado al dispositivo. */
    OUTBOUND,
    /** Solicitud viajando por la red hacia el servicio/gateway. */
    PROCESSING,
    /** Respuesta regresando, canal de vuelta elegido. */
    INBOUND,
    /** Respuesta entregada al dispositivo origen. */
    DELIVERED,
    /** Sesión cerrada, radios liberadas. */
    CLOSED
}

enum class RadioCarrier {
    BLUETOOTH,
    WIFI,
    LORA
}
