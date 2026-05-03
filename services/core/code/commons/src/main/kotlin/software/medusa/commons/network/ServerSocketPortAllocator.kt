package software.medusa.commons.network

import java.net.ServerSocket

data object ServerSocketPortAllocator : PortAllocator {
  override fun allocate(): Int = ServerSocket(0).use { socket -> socket.localPort }
}
