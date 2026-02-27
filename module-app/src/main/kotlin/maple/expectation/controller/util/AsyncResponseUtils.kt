package maple.expectation.controller.util

import org.springframework.http.ResponseEntity
import java.util.concurrent.CompletableFuture
import java.util.function.Function

/**
 * Utility class for asynchronous response handling in controllers.
 *
 * Provides common patterns for transforming CompletableFuture results into ResponseEntity
 * instances, reducing code duplication across controller endpoints.
 *
 * ## Usage Example:
 * ```kotlin
 * // Before (duplicated pattern)
 * return service.getDataAsync(id)
 *     .thenApply(ResponseEntity::ok)
 *
 * // After (using utility)
 * return AsyncResponseUtils.ok(service.getDataAsync(id))
 * ```
 *
 * @see org.springframework.http.ResponseEntity
 * @see java.util.concurrent.CompletableFuture
 */
object AsyncResponseUtils {

    /**
     * Wraps a CompletableFuture result in a ResponseEntity with HTTP 200 OK status.
     *
     * This is the most common response pattern for successful async operations.
     *
     * @param T the response body type
     * @param future the CompletableFuture to transform
     * @return a CompletableFuture that completes with ResponseEntity.ok(body)
     */
    @JvmStatic
    fun <T> ok(future: CompletableFuture<T>): CompletableFuture<ResponseEntity<T>> =
        future.thenApply { t: T -> ResponseEntity.ok(t) }

    /**
     * Applies a transformation function to a CompletableFuture result and wraps in ResponseEntity.
     *
     * Useful for post-processing responses before wrapping in ResponseEntity.
     *
     * @param T the input type
     * @param R the output type
     * @param future the CompletableFuture to transform
     * @param mapper the transformation function to apply
     * @return a CompletableFuture that completes with transformed ResponseEntity
     */
    @JvmStatic
    fun <T, R> map(future: CompletableFuture<T>, mapper: Function<T, R>): CompletableFuture<ResponseEntity<R>> =
        future.thenApply(mapper).thenApply { r: R -> ResponseEntity.ok(r) }
}
