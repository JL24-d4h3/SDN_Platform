package org.sdn_controller_app.config

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuración de Jackson 2.x ObjectMapper.
 *
 * Spring Boot 4.x migró la serialización HTTP a Jackson 3.x (paquete tools.jackson),
 * por lo que ya NO auto-configura un bean de com.fasterxml.jackson.databind.ObjectMapper.
 *
 * Este bean lo proporciona explícitamente para la serialización MQTT interna.
 */
@Configuration
class JacksonConfig {

    @Bean
    fun objectMapper(): ObjectMapper {
        return ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            setSerializationInclusion(JsonInclude.Include.NON_NULL)
        }
    }
}
