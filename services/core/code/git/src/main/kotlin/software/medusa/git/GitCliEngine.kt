package software.medusa.git

import java.nio.file.Path
import kotlin.io.path.isDirectory
import software.medusa.commons.process.ExecutableHandle
import software.medusa.commons.process.ProcessSpawner

class GitCliEngine(
    private val gitExecutableHandle: ExecutableHandle,
    private val processSpawner: ProcessSpawner,
) : GitEngine {
  companion object {
    fun create(runtime: Runtime = Runtime.getRuntime()): GitCliEngine =
        GitCliEngine(
            gitExecutableHandle = ExecutableHandle.locate("git"),
            processSpawner = ProcessSpawner.create(runtime),
        )
  }

  override fun openRepository(repoPath: Path): GitRepository {
    require(repoPath.isAbsolute) { "Expected an absolute repository path, but got: $repoPath" }
    require(repoPath.isDirectory()) { "Expected a repository directory path, but got: $repoPath" }

    return GitCliRepository(
        repoPath = repoPath,
        gitExecutableHandle = gitExecutableHandle,
        processSpawner = processSpawner,
    )
  }
}
