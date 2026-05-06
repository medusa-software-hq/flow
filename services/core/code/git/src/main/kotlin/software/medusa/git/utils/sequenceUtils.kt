package software.medusa.git.utils

fun <T> Sequence<T>.checkIfEmpty(): Boolean = !iterator().hasNext()

fun <T> Sequence<T>.checkIfNotEmpty(): Boolean = iterator().hasNext()
