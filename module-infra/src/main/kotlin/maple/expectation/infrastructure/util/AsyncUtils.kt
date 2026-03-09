package maple.expectation.infrastructure.util

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Supplier
import lombok.extern.slf4j.Slf4j
import maple.expectation.error.exception.ApiTimeoutException
import maple.expectation.error.exception.InternalSystemException
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.util.ExceptionUtils

/**
 * CompletableFuture 비동기 실행 유틸리티
 */
@Slf4j
object AsyncUtils {

    @JvmStatic
    fun unwrapCompletionException(throwable: Throwable): Throwable {
        if (throwable is CompletionException || throwable is ExecutionException) {
            return throwable.cause ?: throwable
        }
        return throwable
    }

    @JvmStatic
    fun <T> withTimeout(
        future: CompletableFuture<T>,
        timeout: Long,
        unit: TimeUnit,
        apiName: String,
    ): CompletableFuture<T> = future
        .orTimeout(timeout, unit)
        .exceptionally { e ->
            val cause = unwrapCompletionException(e)

            when (cause) {
                is TimeoutException -> throw ApiTimeoutException(apiName, cause)
                is RuntimeException -> throw cause
                else -> throw CompletionException(cause)
            }
        }

    @JvmStatic
    fun <T> executeAsync(
        supplier: Supplier<T>,
        executor: Executor?,
        timeout: Long,
        unit: TimeUnit,
        apiName: String,
    ): CompletableFuture<T> {
        val future =
            if (executor != null) {
                CompletableFuture.supplyAsync(supplier, executor)
            } else {
                CompletableFuture.supplyAsync(supplier)
            }

        return withTimeout(future, timeout, unit, apiName)
    }

    @JvmStatic
    fun <T> executeAsync(
        supplier: Supplier<T>,
        timeout: Long,
        unit: TimeUnit,
        apiName: String,
    ): CompletableFuture<T> = executeAsync(supplier, null, timeout, unit, apiName)

    @JvmStatic
    fun <T> handleException(e: Throwable, apiName: String): T {
        val cause = unwrapCompletionException(e)

        when (cause) {
            is TimeoutException -> throw ApiTimeoutException(apiName, cause)
            is RuntimeException -> throw cause
            else -> throw CompletionException(cause)
        }
    }

    @JvmStatic
    fun unwrapAsyncException(throwable: Throwable): Throwable = ExceptionUtils.unwrapAsyncException(throwable) ?: throwable

    @JvmStatic
    fun <T> executeAsync(
        supplier: Callable<T>,
        executor: Executor?,
        context: TaskContext,
    ): CompletableFuture<T> {
        val future =
            if (executor != null) {
                CompletableFuture.supplyAsync({ executeCallableWithExceptionTranslation(supplier, context) }, executor)
            } else {
                CompletableFuture.supplyAsync({ executeCallableWithExceptionTranslation(supplier, context) })
            }

        return future.exceptionally { e ->
            val unwrapped = unwrapCompletionException(e)

            when (unwrapped) {
                is Error -> throw unwrapped
                is RuntimeException -> throw unwrapped
                else -> throw CompletionException(unwrapped)
            }
        }
    }

    @JvmStatic
    private fun <T> executeCallableWithExceptionTranslation(
        supplier: Callable<T>,
        context: TaskContext,
    ): T {
        try {
            return supplier.call()
        } catch (e: Error) {
            throw e
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Throwable) {
            throw InternalSystemException(context.toTaskName(), e)
        }
    }
}
