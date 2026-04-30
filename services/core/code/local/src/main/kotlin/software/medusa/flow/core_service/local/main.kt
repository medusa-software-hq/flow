package software.medusa.flow.core_service.local

import software.medusa.flow.core_service.control.Configurator
import software.medusa.flow.core_service.control.runServer

suspend fun main() {
  runServer(
      configurator = Configurator(),
  )
}
