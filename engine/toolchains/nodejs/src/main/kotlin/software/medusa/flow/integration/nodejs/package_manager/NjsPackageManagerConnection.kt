package software.medusa.flow.integration.nodejs.package_manager

import java.nio.file.Path
import software.medusa.commons.system.SysProcessSpawner

internal interface NjsPackageManagerConnection {
  val packagePath: Path

  suspend fun installDependencies(
      processSpawner: SysProcessSpawner,
  )
}
