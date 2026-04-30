package software.medusa.commons.network

/** Utility interface to allocate available ports on the local machine in a best-effort manner. */
interface PortAllocator {
  /**
   * Allocates and returns an available port on the local machine that can be used to launch a
   * server. The allocated port is not guaranteed to remain available by the time it's used to
   * launch a server.
   */
  fun allocate(): Int
}
