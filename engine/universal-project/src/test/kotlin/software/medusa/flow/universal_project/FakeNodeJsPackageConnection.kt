package software.medusa.flow.universal_project

import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.integration.nodejs.NjsCommand
import software.medusa.flow.integration.nodejs.NjsPackageConnection

/**
 * A package connection whose [installDependencies] makes the module's tools resolvable — just as a
 * real `yarn install` populates `node_modules/.bin` — after which [resolveCommand] hands them out.
 * Commands referenced before installation (or that were never "installed") do not resolve.
 */
class FakeNodeJsPackageConnection(
    private val moduleDirectory: UfsMutableDirectory,
) : NjsPackageConnection {
  private val installedCommandByName = mutableMapOf<String, NjsCommand>()

  override suspend fun installDependencies() {
    installedCommandByName["codegen"] = FakeNjsCommand.GenerateFile(moduleDirectory)
    installedCommandByName["lint"] = FakeNjsCommand.CheckLowercaseSources(moduleDirectory)
    installedCommandByName["test"] = FakeNjsCommand.AlwaysSucceed
  }

  override suspend fun resolveCommand(
      name: UfsName.Literal,
  ): NjsCommand? = installedCommandByName[name.content]
}
