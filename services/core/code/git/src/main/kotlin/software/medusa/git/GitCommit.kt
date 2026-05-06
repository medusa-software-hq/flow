package software.medusa.git

import software.medusa.git.tree.GitProperTree

data class GitCommit(
    val parentCommitHashes: Set<GitCommitHash>,
    val details: GitCommitDetails,
    val tree: GitProperTree,
)

data class GitCommitDetails(
    val authorDetails: GitPersonalDetails,
    val committerDetails: GitPersonalDetails?,
    val message: String,
)
