package software.medusa.git

import java.nio.file.Path
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import software.medusa.git.tree.GitProperTree
import software.medusa.git.tree.GitTree
import software.medusa.git.worktree.GitWorktree
import software.medusa.git.worktree.GitWorktreeFilter

class GitRepository(
    private val jRepository: Repository,
) {
  val path: Path
    get() = jRepository.workTree.toPath()

  companion object {
    fun download(
        targetRepoPath: Path,
    ): GitRepository {
      TODO("For now leave unimplemented")
    }

    fun open(
        path: Path,
    ): GitRepository =
        GitRepository(
            jRepository =
                FileRepositoryBuilder().findGitDir(path.toFile()).setMustExist(true).build(),
        )
  }

  fun resolveCommitRef(
      commitRef: GitRefPath,
  ): GitCommitHash? =
      jRepository.resolve(commitRef.refName)?.let { resolvedObjectId ->
        GitCommitHash(raw = resolvedObjectId.name)
      }

  fun createCommitRef(
      commitHash: GitCommitHash,
      newRefPath: GitRefPath,
  ): GitRef {
    val refUpdate = jRepository.updateRef(newRefPath.refName)
    refUpdate.setNewObjectId(commitHash.objectId)

    when (val updateResult = refUpdate.update()) {
      RefUpdate.Result.NEW,
      RefUpdate.Result.FAST_FORWARD,
      RefUpdate.Result.FORCED,
      RefUpdate.Result.NO_CHANGE,
      -> return GitRef(path = newRefPath)

      else -> error("Failed to update ref ${newRefPath.refName}: $updateResult")
    }
  }

  fun commit(
      message: String,
  ): GitCommitHash {
    require(message.isNotBlank()) { "Commit message must not be blank" }

    val parentCommitId =
        resolveCommitRef(commitRef = GitRefPath.of(Constants.HEAD))
            ?: error("Cannot commit without an existing HEAD commit")

    val commitHash =
        checkIn(
            parentCommitId = parentCommitId,
            sourceWorktreePath = path,
            commitDetails =
                GitCommitDetails(
                    authorDetails = FlowPersonalDetails,
                    committerDetails = FlowPersonalDetails,
                    message = message,
                ),
        )

    createCommitRef(
        commitHash = commitHash,
        newRefPath = currentHeadRefPath(),
    )

    return commitHash
  }

  // TODO: Add tests
  fun readCommit(
      commitHash: GitCommitHash,
  ): GitCommit =
      RevWalk(jRepository).use { revWalk ->
        val revCommit = revWalk.parseCommit(commitHash.objectId)

        val parentCommitHashes = revCommit.parents.map { GitCommitHash(it.id.name) }.toSet()

        val details =
            GitCommitDetails(
                authorDetails = GitPersonalDetails.from(revCommit.authorIdent),
                committerDetails = revCommit.committerIdent?.let { GitPersonalDetails.from(it) },
                message = revCommit.fullMessage,
            )

        jRepository.newObjectReader().use { jObjectReader ->
          val tree =
              GitProperTree.load(
                  jObjectReader = jObjectReader,
                  jTreeId = revCommit.tree.id,
              )

          return GitCommit(
              parentCommitHashes = parentCommitHashes,
              details = details,
              tree = tree,
          )
        }
      }

  // TODO: Add tests
  fun createCommit(
      parentCommitId: GitCommitHash,
      details: GitCommitDetails,
      tree: GitTree,
  ): GitCommitHash =
      jRepository.newObjectInserter().use { jObjectInserter ->
        val jTreeId: ObjectId =
            tree.store(
                jObjectInserter = jObjectInserter,
            )

        val jCommitId =
            jObjectInserter.insert(
                CommitBuilder().apply {
                  setParentId(parentCommitId.objectId)

                  author = details.authorDetails.personIdent
                  committer = details.committerDetails?.personIdent
                  message = details.message

                  setTreeId(jTreeId)
                },
            )

        return GitCommitHash(raw = jCommitId.name)
      }

  fun checkOut(
      sourceCommitId: GitCommitHash,
      targetWorktreePath: Path,
  ) {
    val sourceCommit = readCommit(commitHash = sourceCommitId)

    val realizedWorktree = sourceCommit.tree.realize()

    realizedWorktree.write(worktreePath = targetWorktreePath)
  }

  fun checkIn(
      parentCommitId: GitCommitHash,
      sourceWorktreePath: Path,
      commitDetails: GitCommitDetails,
  ): GitCommitHash {
    val sourceWorktree = GitWorktree.read(worktreePath = sourceWorktreePath)

    val filteredSourceWorktree =
        sourceWorktree.filtered(
            globalFilter = GitWorktreeFilter.Passive,
        )

    val sourceTree =
        GitTree.interpret(
            worktree = filteredSourceWorktree,
        )

    return createCommit(
        parentCommitId = parentCommitId,
        details = commitDetails,
        tree = sourceTree,
    )
  }

  private fun currentHeadRefPath(): GitRefPath {
    val targetRefName = jRepository.exactRef(Constants.HEAD).target.name

    return GitRefPath(targetRefName.split("/"))
  }
}

private val FlowPersonalDetails = GitPersonalDetails(name = "Flow", email = "flow@medusa.software")

private val GitRefPath.refName: String
  get() = segments.joinToString("/")
