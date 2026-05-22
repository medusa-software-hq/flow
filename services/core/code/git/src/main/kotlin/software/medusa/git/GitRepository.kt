package software.medusa.git

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import software.medusa.commons.filesystem.compat.impl.nio.NioCompatFsDirectory
import software.medusa.commons.filesystem.compat.materializeIn
import software.medusa.git.tree.GitTree
import software.medusa.git.worktree.GitWorktreeFilter
import software.medusa.git.worktree.filtered

class GitSession(
    internal val jRepository: Repository,
    internal val jObjectReader: ObjectReader,
    internal val jObjectInserter: ObjectInserter,
) {}

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
                FileRepositoryBuilder().setWorkTree(path.toFile()).setMustExist(true).build(),
        )

    context(session: GitSession)
    fun resolveHead(): GitCommitHash =
        resolveCommitRef(GitRefPath.of(Constants.HEAD))
            ?: error("Expected HEAD to resolve to a commit")

    context(session: GitSession)
    fun resolveCommitRef(
        commitRef: GitRefPath,
    ): GitCommitHash? =
        session.jRepository.resolve(commitRef.toRefString())?.let { resolvedObjectId ->
          GitCommitHash(raw = resolvedObjectId.name)
        }

    context(session: GitSession)
    fun createCommitRef(
        commitHash: GitCommitHash,
        newRefPath: GitRefPath,
    ): GitRef {
      val refUpdate = session.jRepository.updateRef(newRefPath.toRefString())

      refUpdate.setNewObjectId(commitHash.objectId)

      when (val updateResult = refUpdate.update()) {
        RefUpdate.Result.NEW,
        RefUpdate.Result.FAST_FORWARD,
        RefUpdate.Result.FORCED,
        RefUpdate.Result.NO_CHANGE,
        -> return GitRef(path = newRefPath)

        else -> error("Failed to update ref ${newRefPath.toRefString()}: $updateResult")
      }
    }

    context(session: GitSession)
    fun readCommit(
        commitHash: GitCommitHash,
    ): GitCommit =
        RevWalk(session.jObjectReader).use { revWalk ->
          val revCommit = revWalk.parseCommit(commitHash.objectId)

          revCommit.wrap(session = session)
        }

    context(session: GitSession)
    fun createCommit(
        parentCommitId: GitCommitHash,
        details: GitCommitDetails,
        tree: GitTree,
    ): GitCommitHash {
      val jTreeId: ObjectId =
          tree.store(
              jObjectInserter = session.jObjectInserter,
          )

      val jCommitId =
          session.jObjectInserter.insert(
              CommitBuilder().apply {
                setParentId(parentCommitId.objectId)

                author = details.authorDetails.personIdent
                committer = details.committerDetails?.personIdent
                message = details.message

                setTreeId(jTreeId)
              },
          )

      session.jObjectInserter.flush()

      return GitCommitHash(raw = jCommitId.name)
    }

    context(session: GitSession)
    fun createMergeCommit(
        parentCommitHashes: Set<GitCommitHash>,
        personalDetails: GitPersonalDetails,
    ): GitCommitHash {
      val parentTrees = parentCommitHashes.map { commitHash -> readCommit(commitHash = commitHash) }

      TODO("Not yet implemented")
    }

    context(session: GitSession)
    private fun findMergeBase(
        commitHashes: Set<GitCommitHash>,
    ): Set<GitCommit> =
        RevWalk(session.jObjectReader).use { revWalk ->
          revWalk.setRevFilter(RevFilter.MERGE_BASE)

          commitHashes.forEach { commitHash ->
            val jStartCommit = revWalk.parseCommit(commitHash.objectId)

            revWalk.markStart(jStartCommit)
          }

          revWalk.commits.map { mergeBaseCommit -> mergeBaseCommit.wrap(session = session) }.toSet()
        }

    context(session: GitSession)
    fun checkOut(
        sourceCommitId: GitCommitHash,
        targetWorktreePath: Path,
    ) {
      val sourceCommit = readCommit(commitHash = sourceCommitId)

      val realizedWorktree = sourceCommit.tree.realize()

      runBlocking {
        realizedWorktree.materializeIn(
            targetDirectory = NioCompatFsDirectory(directoryPath = targetWorktreePath),
        )
      }
    }

    context(session: GitSession)
    fun checkIn(
        parentCommitId: GitCommitHash,
        sourceWorktreePath: Path,
        commitDetails: GitCommitDetails,
    ): GitCommitHash {
      val sourceWorktree = NioCompatFsDirectory(directoryPath = sourceWorktreePath)

      val filteredSourceWorktree = runBlocking {
        sourceWorktree.filtered(
            baseFilter = GitWorktreeFilter.Passive,
        )
      }

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
  }

  fun <T> process(
      block:
          context(GitSession)
          () -> T,
  ): T =
      jRepository.newObjectReader().use { jObjectReader ->
        jRepository.newObjectInserter().use { jObjectInserter ->
          with(
              GitSession(
                  jRepository = jRepository,
                  jObjectReader = jObjectReader,
                  jObjectInserter = jObjectInserter,
              ),
          ) {
            block()
          }
        }
      }

  private fun currentHeadRefPath(): GitRefPath {
    val targetRefName = jRepository.exactRef(Constants.HEAD).target.name

    return GitRefPath(targetRefName.split("/"))
  }
}

private val RevWalk.commits: Sequence<RevCommit>
  get() = sequence {
    var nextCommit: RevCommit? = next()

    while (nextCommit != null) {
      yield(nextCommit)

      nextCommit = next()
    }
  }

private fun RevCommit.wrap(session: GitSession): GitCommit =
    GitCommit(
        session = session,
        jRevCommit = this,
    )
