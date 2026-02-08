package org.sdn_controller_app.config

import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.integration.channel.DirectChannel
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory
import org.springframework.integration.mqtt.core.MqttPahoClientFactory
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageHandler

/**
 * Configuración central MQTT del Controlador SDN.
 *
 * - Conecta al broker Mosquitto local.
 * - Define un canal de salida para publicar comandos en `dispositivo/{MAC}/comando`.
 * - Define un canal de entrada para recibir telemetría desde `dispositivo/+/metrics`.
 */
@Configuration
class MqttConfig {

    @Value("\${mqtt.broker.url}")
    private lateinit var brokerUrl: String

    @Value("\${mqtt.client.id}")
    private lateinit var clientId: String

    @Value("\${mqtt.qos}")
    private var qos: Int = 1

    @Value("\${mqtt.keep-alive}")
    private var keepAlive: Int = 20

    @Value("\${mqtt.connection-timeout}")
    private var connectionTimeout: Int = 10

    // ─── Client Factory ─────────────────────────────────────

    @Bean
    fun mqttClientFactory(): MqttPahoClientFactory {
        val factory = DefaultMqttPahoClientFactory()
        val options = MqttConnectOptions().apply {
            serverURIs = arrayOf(brokerUrl)
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = this@MqttConfig.connectionTimeout
            keepAliveInterval = this@MqttConfig.keepAlive
        }
        factory.connectionOptions = options
        return factory
    }

    // ─── Outbound (Publicación de comandos) ─────────────────

    @Bean
    fun mqttOutboundChannel(): MessageChannel = DirectChannel()

    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    fun mqttOutbound(): MessageHandler {
        val handler = MqttPahoMessageHandler(
            "${clientId}-publisher",
            mqttClientFactory()
        )
        handler.setAsync(true)
        handler.setDefaultQos(qos)
        handler.setDefaultTopic("dispositivo/default/comando")
        return handler
    }

    // ─── Inbound (Suscripción a telemetría) ─────────────────

    @Bean
    fun mqttInboundChannel(): MessageChannel = DirectChannel()

    @Bean
    fun mqttInbound(): MqttPahoMessageDrivenChannelAdapter {
        val adapter = MqttPahoMessageDrivenChannelAdapter(
            "${clientId}-subscriber",
            mqttClientFactory(),
            "dispositivo/+/metrics",
            "dispositivo/+/registro"
        )
        adapter.setCompletionTimeout(5000)
        adapter.setConverter(DefaultPahoMessageConverter())
        adapter.setQos(qos)
        adapter.setOutputChannel(mqttInboundChannel())
        return adapter
    }
}
