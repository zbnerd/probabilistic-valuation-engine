package maple.restcontroller.read

import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class InflightRequestRegistry {

    private val registry = ConcurrentHashMap<String, CopyOnWriteArrayList<DeferredResult<ResponseEntity<*>>>>()

    fun register(userIgn: String, presetNo: Int, deferred: DeferredResult<ResponseEntity<*>>): Boolean {
        val list = registry.computeIfAbsent(key(userIgn, presetNo)) { CopyOnWriteArrayList() }
        list.add(deferred)
        return list.size == 1
    }

    fun getAndRemove(userIgn: String, presetNo: Int): List<DeferredResult<ResponseEntity<*>>> {
        return registry.remove(key(userIgn, presetNo)) ?: emptyList()
    }

    fun cleanup(userIgn: String, presetNo: Int, deferred: DeferredResult<ResponseEntity<*>>) {
        registry.computeIfPresent(key(userIgn, presetNo)) { _, list ->
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

    private fun key(userIgn: String, presetNo: Int): String = "$userIgn:$presetNo"
}
