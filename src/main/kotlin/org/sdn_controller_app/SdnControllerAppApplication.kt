package org.sdn_controller_app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.integration.annotation.IntegrationComponentScan

@SpringBootApplication
@IntegrationComponentScan
class SdnControllerAppApplication

fun main(args: Array<String>) {
    runApplication<SdnControllerAppApplication>(*args)
}
