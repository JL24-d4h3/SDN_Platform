package org.sdn_controller_app.mqtt

import org.springframework.integration.annotation.MessagingGateway
import org.springframework.integration.mqtt.support.MqttHeaders
import org.springframework.messaging.handler.annotation.Header

/**
 * Gateway de salida MQTT.
 * Permite publicar mensajes en tópicos dinámicos (ej: `dispositivo/{MAC}/comando`).
 */
@MessagingGateway(defaultRequestChannel = "mqttOutboundChannel")
interface MqttGateway {

    fun publish(
        @Header(MqttHeaders.TOPIC) topic: String,
        payload: String
    )
}
