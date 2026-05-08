package software.medusa.git

import java.nio.file.Path
import kotlin.io.path.isDirectory
import software.medusa.commons.process.ExecutableHandle
import software.medusa.commons.process.ProcessSpawner

class GitCliEngine(
    private val processSpawner: ProcessSpawner,
    private val gitExecutableHandle: ExecutableHandle,
) : GitEngine {
  override fun openRepository(repoPath: Path): GitEngineRepository {
    require(repoPath.isAbsolute) { "Expected an absolute repository path, but got: $repoPath" }
    require(repoPath.isDirectory()) { "Expected a repository directory path, but got: $repoPath" }

    return GitCliRepository(
        path = repoPath,
        gitExecutableHandle = gitExecutableHandle,
        processSpawner = processSpawner,
    )
  }
}
