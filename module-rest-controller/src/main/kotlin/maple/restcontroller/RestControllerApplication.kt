package maple.restcontroller

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan

@SpringBootApplication
@ComponentScan(basePackages = ["maple.restcontroller", "maple.auth"])
class RestControllerApplication

fun main(args: Array<String>) {
	runApplication<RestControllerApplication>(*args)
}
