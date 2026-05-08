package software.medusa.git

import org.eclipse.jgit.lib.ObjectId

@JvmInline
value class GitCommitHash(
    val raw: String,
) {
  val objectId: ObjectId
    get() = ObjectId.fromString(raw)
}
