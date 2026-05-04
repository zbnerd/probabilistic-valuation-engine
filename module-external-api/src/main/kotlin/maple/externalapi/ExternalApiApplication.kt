package maple.externalapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class ExternalApiApplication

fun main(args: Array<String>) {
    org.springframework.boot.runApplication<ExternalApiApplication>(*args)
}
