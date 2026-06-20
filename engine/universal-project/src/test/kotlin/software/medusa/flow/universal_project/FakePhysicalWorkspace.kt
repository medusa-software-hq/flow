package software.medusa.flow.universal_project

import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.filesystem.extractDeepMutable
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.integration.gradle.GrdProjectConnection
import software.medusa.flow.integration.nodejs.NjsPackageConnection
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager
import software.medusa.flow.physical_workspace.PhwWorkspace

/**
 * An in-memory [PhwWorkspace] whose toolchains genuinely operate on the workspace tree, so tests
 * can assert real effects — a generated file appearing, a lint reading the actual sources — instead
 * of canned return values.
 */
class FakePhysicalWorkspace(
    override val rootDirectory: UfsMutableDirectory,
) : PhwWorkspace {
  override suspend fun connectNodeJs(
      packageManager: NjsPackageManager,
      packagePath: UfsLiteralAbsolutePath,
  ): NjsPackageConnection =
      FakeNodeJsPackageConnection(moduleDirectory = moduleDirectoryAt(packagePath))

  override suspend fun connectGradle(
      projectPath: UfsLiteralAbsolutePath,
  ): GrdProjectConnection =
      FakeGradleProjectConnection(moduleDirectory = moduleDirectoryAt(projectPath))

  private suspend fun moduleDirectoryAt(
      path: UfsLiteralAbsolutePath,
  ): UfsMutableDirectory =
      rootDirectory.extractDeepMutable(relativePath = path.innerPath) as? UfsMutableDirectory
          ?: error("No module directory at ${path.toUnixAbsolutePathString()}")

  override fun close() = Unit
}
