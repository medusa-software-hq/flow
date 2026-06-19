package software.medusa.flow.integration.nodejs

import software.medusa.commons.unix.path.UfsName

/**
 * Represents an active connection to a Node.js project that can resolve commands and install
 * tooling.
 */
interface NjsPackageConnection {
  /** Installs the dependencies declared by this project using its package manager lockfile. */
  suspend fun installDependencies()

  /**
   * Resolves [name] similarly to how a project-local command runner would search
   * `node_modules/.bin`.
   *
   * Returns `null` when the command cannot be resolved for this project.
   */
  suspend fun resolveCommand(name: UfsName.Literal): NjsCommand?
}
