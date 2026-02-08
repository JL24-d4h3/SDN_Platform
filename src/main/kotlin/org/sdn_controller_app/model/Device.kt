package org.sdn_controller_app.model

import jakarta.persistence.*
import java.time.Instant

/**
 * Entidad JPA que representa un dispositivo (Agente) registrado en la red SDN.
 *
 * Se persiste en la base de datos H2 y contiene toda la información
 * necesaria para que el controlador reconozca y gestione el dispositivo.
 */
@Entity
@Table(name = "devices")
data class Device(

    /** Dirección MAC del dispositivo — clave primaria natural. */
    @Id
    @Column(length = 17)
    val mac: String,

    /** Nombre descriptivo (ej: "Celular de Jorge", "Laptop Lab"). */
    @Column(nullable = false)
    var name: String = "Dispositivo desconocido",

    /** Tipo de dispositivo: PHONE, LAPTOP, TABLET, IOT, UNKNOWN. */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var deviceType: DeviceType = DeviceType.UNKNOWN,

    /** Tecnología de comunicación activa actualmente. */
    @Column(nullable = false)
    var activeTechnology: String = "none",

    /** Último RSSI reportado (dBm). */
    var lastRssi: Int? = null,

    /** Nivel de batería reportado (%). */
    var batteryLevel: Int? = null,

    /** IP del dispositivo en la red local (si la reporta). */
    @Column(length = 45)
    var ipAddress: String? = null,

    /** ¿Está actualmente conectado (ha enviado telemetría recientemente)? */
    @Column(nullable = false)
    var online: Boolean = false,

    /** Timestamp de primera vez que se vio el dispositivo. */
    @Column(nullable = false, updatable = false)
    var firstSeen: Instant = Instant.now(),

    /** Timestamp de la última actividad (telemetría o comando). */
    @Column(nullable = false)
    var lastSeen: Instant = Instant.now()
)

enum class DeviceType {
    PHONE, LAPTOP, TABLET, IOT, UNKNOWN
}
