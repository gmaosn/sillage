package sillage

import java.util.concurrent.CompletableFuture

// Where there are Java threads (a desktop's JVM, Android).

internal actual fun <T> platformBackground(work: () -> T): Pending<T> {
    val f = CompletableFuture.supplyAsync(work)
    return object : Pending<T> { override val isDone get() = f.isDone; override fun get(): T = f.get() }
}

internal actual fun <T, R> parallelMap(items: List<T>, f: (T) -> R): List<R> = items.parallelStream().map { f(it) }.toList()

internal actual class PerThread<T : Any> actual constructor(make: () -> T) {
    private val local = object : ThreadLocal<T>() { override fun initialValue(): T = make() }
    actual fun get(): T = local.get()!!
}
