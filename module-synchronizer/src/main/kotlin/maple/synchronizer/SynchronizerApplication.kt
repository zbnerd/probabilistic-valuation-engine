package maple.synchronizer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication(scanBasePackages = ["maple.synchronizer", "maple.expectation.infrastructure.executor"])
@Import(maple.expectation.infrastructure.config.ExecutorConfig::class)
class SynchronizerApplication

fun main(args: Array<String>) {
    runApplication<SynchronizerApplication>(*args)
}
