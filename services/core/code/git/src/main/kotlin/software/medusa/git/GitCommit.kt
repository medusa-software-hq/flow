package software.medusa.git

import org.eclipse.jgit.revwalk.RevCommit
import software.medusa.git.tree.GitProperTree

class GitCommit(
    private val session: GitSession,
    internal val jRevCommit: RevCommit,
) {
  val hash: GitCommitHash
    get() = GitCommitHash(jRevCommit.name)

  val parentCommitHashes: Set<GitCommitHash>
    get() = jRevCommit.parents.mapTo(mutableSetOf()) { GitCommitHash(it.id.name) }

  val details: GitCommitDetails
    get() =
        GitCommitDetails(
            authorDetails = GitPersonalDetails.from(jRevCommit.authorIdent),
            committerDetails = jRevCommit.committerIdent?.let { GitPersonalDetails.from(it) },
            message = jRevCommit.fullMessage,
        )

  val tree: GitProperTree
    get() =
        GitProperTree.load(
            jObjectReader = session.jObjectReader,
            jTreeId = jRevCommit.tree.id,
        )
}

data class GitCommitDetails(
    val authorDetails: GitPersonalDetails,
    val committerDetails: GitPersonalDetails?,
    val message: String,
)
