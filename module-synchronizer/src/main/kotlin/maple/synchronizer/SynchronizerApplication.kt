package maple.synchronizer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["maple.synchronizer"])
class SynchronizerApplication

fun main(args: Array<String>) {
    runApplication<SynchronizerApplication>(*args)
}
