package software.medusa.flow.worker

/**
 * Produces a GitHub token supplier scoped to one repository. The worker mints its own short-lived
 * GitHub App **installation token** per session (refreshing it while the session runs) instead of
 * carrying a static token — see `WorkCommand` for the production wiring against the App key, and
 * the test fakes for a trivial constant supplier. The supplier is `suspend` so each git invocation
 * and the PR-create call resolve a *current* (possibly just-refreshed) token.
 */
fun interface WrkGitHubTokenSupplierFactory {
  fun forRepo(repoFullName: String): suspend () -> String
}
