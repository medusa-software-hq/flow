package software.medusa.flow.server

/**
 * Decides whether a verified caller email is allowed to act as the worker.
 *
 * The auth decorator only verifies that a token is a valid Google-signed ID token (user or service
 * account); [WorkerServiceImpl] additionally checks the caller against this policy, so a regular
 * user's token — however validly signed — cannot call `WorkerService`.
 */
fun interface WorkerAuthorizer {
  fun isAuthorized(
      email: String,
  ): Boolean

  companion object {
    /** Accepts any verified caller. For local development only. */
    val permissive = WorkerAuthorizer { true }

    /** Accepts only callers whose email is in [allowedEmails]. */
    fun allowlist(
        allowedEmails: Set<String>,
    ): WorkerAuthorizer = WorkerAuthorizer { email -> email in allowedEmails }
  }
}
