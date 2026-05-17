package maple.restcontroller.read

import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class InflightRequestRegistry {

    private val registry = ConcurrentHashMap<String, CopyOnWriteArrayList<DeferredResult<ResponseEntity<*>>>>()

    fun register(userIgn: String, deferred: DeferredResult<ResponseEntity<*>>): Boolean {
        val list = registry.computeIfAbsent(userIgn) { CopyOnWriteArrayList() }
        list.add(deferred)
        return list.size == 1
    }

    fun getAndRemove(userIgn: String): List<DeferredResult<ResponseEntity<*>>> {
        return registry.remove(userIgn) ?: emptyList()
    }

    fun cleanup(userIgn: String, deferred: DeferredResult<ResponseEntity<*>>) {
        registry.computeIfPresent(userIgn) { _, list ->
            list.remove(deferred)
            if (list.isEmpty()) null else list
        }
    }

    fun size(): Int = registry.size

    fun failAll(response: ResponseEntity<*>) {
        registry.keys.toList().forEach { userIgn ->
            val deferreds = registry.remove(userIgn)
            deferreds?.forEach { deferred ->
                deferred.setErrorResult(response)
            }
        }
    }
}
