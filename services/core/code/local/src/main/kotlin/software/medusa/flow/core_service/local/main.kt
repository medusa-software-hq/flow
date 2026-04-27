package software.medusa.flow.core_service.local

import software.medusa.flow.core_service.Configurator
import software.medusa.flow.core_service.runServer

suspend fun main() {
  runServer(
      configurator = Configurator(),
  )
}
