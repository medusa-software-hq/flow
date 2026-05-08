package software.medusa.git.utils

fun <T> Sequence<T>.contentEquals(other: Sequence<T>): Boolean {
  val it1 = this.iterator()
  val it2 = other.iterator()

  while (it1.hasNext() && it2.hasNext()) {
    if (it1.next() != it2.next()) return false
  }

  // Ensure both reached the end (same length)
  return !it1.hasNext() && !it2.hasNext()
}
