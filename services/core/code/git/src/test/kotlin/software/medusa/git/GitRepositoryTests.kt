package software.medusa.git

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import software.medusa.git.GitRepository.Companion.createCommit
import software.medusa.git.GitRepository.Companion.createCommitRef
import software.medusa.git.GitRepository.Companion.readCommit
import software.medusa.git.GitRepository.Companion.resolveCommitRef
import software.medusa.git.tree.GitProperTree
import software.medusa.git.worktree.TestGitTreeFile
import software.medusa.git.worktree.TestGitTreeGroup

class GitRepositoryTests {
  @Test
  fun openResolvesCommitRefsAndCreatesNewRefs() {
    val repoPath = Files.createTempDirectory("git-repository-")

    Git.init().setDirectory(repoPath.toFile()).call().use { git ->
      val initialCommitHash =
          git.repository.createInitialCommit(
              details =
                  GitCommitDetails(
                      authorDetails =
                          GitPersonalDetails(name = "Test Author", email = "author@example.com"),
                      committerDetails =
                          GitPersonalDetails(
                              name = "Test Committer",
                              email = "committer@example.com",
                          ),
                      message = "initial",
                  ),
          )

      val repository = GitRepository.open(repoPath)
      val headRef = GitRefPath.of("HEAD")
      val featureRef = GitRefPath.of("refs", "heads", "feature")

      repository.process {
        assertEquals(
            expected = initialCommitHash,
            actual = resolveCommitRef(headRef),
        )

        assertNull(
            resolveCommitRef(GitRefPath.of("refs", "heads", "missing")),
        )

        val createdRef =
            createCommitRef(
                commitHash = initialCommitHash,
                newRefPath = featureRef,
            )

        assertEquals(GitRef(featureRef), createdRef)

        assertEquals(
            expected = initialCommitHash,
            actual = resolveCommitRef(featureRef),
        )
      }
    }
  }

  @Test
  fun createCommitAndReadCommitRoundTrip() {
    val repoPath = Files.createTempDirectory("git-repository-commit-")

    Git.init().setDirectory(repoPath.toFile()).call().use { git ->
      val initialCommitHash =
          git.repository.createInitialCommit(
              details =
                  GitCommitDetails(
                      authorDetails =
                          GitPersonalDetails(name = "Base Author", email = "base@example.com"),
                      committerDetails =
                          GitPersonalDetails(
                              name = "Base Committer",
                              email = "base-committer@example.com",
                          ),
                      message = "initial",
                  ),
          )

      val repository = GitRepository.open(repoPath)

      val details =
          GitCommitDetails(
              authorDetails =
                  GitPersonalDetails(name = "Test Author", email = "author@example.com"),
              committerDetails =
                  GitPersonalDetails(name = "Test Committer", email = "committer@example.com"),
              message = "add file",
          )

      val tree =
          GitProperTree(
              rootGroup =
                  TestGitTreeGroup(
                      sequenceOf(
                          software.medusa.git.tree.GitTreeGroup.ChildEntry(
                              name = "hello.txt",
                              child = TestGitTreeFile(content = "hello"),
                          ),
                      ),
                  ),
          )

      repository.process {
        val commitHash =
            createCommit(
                parentCommitId = initialCommitHash,
                details = details,
                tree = tree,
            )

        val commit = readCommit(commitHash)

        val helloFile = assertNotNull(commit.tree.rootGroup.childByName["hello.txt"])

        assertEquals(setOf(initialCommitHash), commit.parentCommitHashes)

        assertEquals(details, commit.details)

        assertEquals(
            "hello",
            (helloFile as software.medusa.git.tree.GitTreeFile).read().bufferedReader().readText(),
        )
      }
    }
  }
}

private fun Repository.createInitialCommit(
    details: GitCommitDetails,
): GitCommitHash {
  val commitId =
      newObjectInserter().use { objectInserter ->
        val emptyTreeId = objectInserter.insert(Constants.OBJ_TREE, ByteArray(0))

        objectInserter.insert(
            CommitBuilder().apply {
              author = details.authorDetails.personIdent
              committer = details.committerDetails?.personIdent ?: details.authorDetails.personIdent
              message = details.message
              setTreeId(emptyTreeId)
            },
        )
      }

  val headTargetRefName = exactRef(Constants.HEAD).target.name
  val refUpdate = updateRef(headTargetRefName)
  refUpdate.setNewObjectId(commitId)

  when (val updateResult = refUpdate.update()) {
    RefUpdate.Result.NEW,
    RefUpdate.Result.FAST_FORWARD,
    RefUpdate.Result.NO_CHANGE,
    -> return GitCommitHash(commitId.name)

    else -> error("Failed to create initial commit ref $headTargetRefName: $updateResult")
  }
}
