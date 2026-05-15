package software.medusa.flow.core_service.worker.utils

data class WithNext<T, R>(
    val element: T,
    val nextElement: R,
) where T : R

fun <T, R> Sequence<T>.withNext(
    buildOuterRight: (T) -> R,
): Sequence<WithNext<T, R>> where T : R = sequence {
  val iterator = iterator()
  if (!iterator.hasNext()) return@sequence

  var current = iterator.next()

  while (iterator.hasNext()) {
    val next = iterator.next()
    yield(WithNext(current, next))
    current = next
  }

  yield(WithNext(current, buildOuterRight(current)))
}

fun <T : Any> Sequence<T>.withNextOrNull(): Sequence<WithNext<T, T?>> =
    this.withNext(
        outerRight = null,
    )

fun <T, R> Sequence<T>.withNext(
    outerRight: R,
): Sequence<WithNext<T, R>> where T : R =
    this.withNext(
        buildOuterRight = { outerRight },
    )

fun <T> Sequence<T>.withNextSaturated(): Sequence<WithNext<T, T>> =
    this.withNext(
        buildOuterRight = { it },
    )

fun <T, R> Iterable<T>.withNext(
    outerRight: R,
): List<WithNext<T, R>> where T : R =
    this.asSequence()
        .withNext(
            outerRight = outerRight,
        )
        .toList()

fun <T : Any> Iterable<T>.withNextOrNull(): List<WithNext<T, T?>> =
    this.asSequence().withNextOrNull().toList()
