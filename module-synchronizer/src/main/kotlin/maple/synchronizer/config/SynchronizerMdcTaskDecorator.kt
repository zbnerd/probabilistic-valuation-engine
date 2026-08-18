package maple.synchronizer.config

import org.slf4j.MDC
import org.springframework.core.task.TaskDecorator

class SynchronizerMdcTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        val captured = MDC.getCopyOfContextMap()
        return Runnable {
            val previous = MDC.getCopyOfContextMap()
            restore(captured)
            runCatching(runnable::run)
                .also { restore(previous) }
                .getOrThrow()
        }
    }

    private fun restore(context: Map<String, String>?) {
        if (context == null) {
            MDC.clear()
        } else {
            MDC.setContextMap(context)
        }
    }
}
