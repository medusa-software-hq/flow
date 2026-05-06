package software.medusa.git

import org.eclipse.jgit.lib.PersonIdent

data class GitPersonalDetails(
    val name: String,
    val email: String,
) {
  companion object {
    internal fun from(
        personIdent: PersonIdent,
    ): GitPersonalDetails =
        GitPersonalDetails(
            name = personIdent.name,
            email = personIdent.emailAddress,
        )
  }

  val personIdent: PersonIdent
    get() = PersonIdent(name, email)
}
