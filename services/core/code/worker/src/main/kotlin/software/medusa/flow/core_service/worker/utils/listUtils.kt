package software.medusa.flow.core_service.worker.utils

fun <T, K : Comparable<K>> List<T>.isSortedBy(selector: (T) -> K): Boolean {
  for (i in 1 until this.size) {
    if (selector(this[i - 1]) > selector(this[i])) {
      return false
    }
  }

  return true
}
