package software.medusa.flow.server

private const val portEnvVarName = "PORT"
private const val clientIdEnvVarName = "GOOGLE_CLIENT_ID"
private const val allowedDomainEnvVarName = "GOOGLE_ALLOWED_DOMAIN"
private const val corsOriginRegexEnvVarName = "CORS_ALLOWED_ORIGIN_REGEX"
private const val databaseUrlEnvVarName = "DATABASE_URL"
private const val gitHubAppClientIdEnvVarName = "GITHUB_APP_CLIENT_ID"
private const val gitHubAppPemContentEnvVarName = "GITHUB_APP_PEM_CONTENT"
private const val gitHubRepoOwnerEnvVarName = "GITHUB_REPO_OWNER"
private const val gitHubRepoNameEnvVarName = "GITHUB_REPO_NAME"

fun main() {
  val port =
      System.getenv(portEnvVarName)?.toIntOrNull()
          ?: error("$portEnvVarName environment variable must be set to a valid integer")

  val clientId =
      System.getenv(clientIdEnvVarName)
          ?: error("$clientIdEnvVarName environment variable must be set")

  val allowedDomain =
      System.getenv(allowedDomainEnvVarName)
          ?: error("$allowedDomainEnvVarName environment variable must be set")

  val corsOriginRegex =
      System.getenv(corsOriginRegexEnvVarName)
          ?: error("$corsOriginRegexEnvVarName environment variable must be set")

  val databaseUrl =
      System.getenv(databaseUrlEnvVarName)
          ?: error("$databaseUrlEnvVarName environment variable must be set")

  // One database (pool + migrations) shared by every Postgres-backed store.
  val database = buildFlowDatabase(databaseUrl)

  buildServer(
          originRegex = corsOriginRegex,
          port = port,
          auth = GoogleIdTokenAuthDecorator(clientId, allowedDomain),
          counterStore = PostgresCounterStore(database),
          gitHubIssueStore = buildGitHubIssueStore(),
          sessionStore = PostgresSessionStore(database),
      )
      .start()
      .join()
}

/**
 * Builds a [GitHubIssueStore] from the GitHub App environment variables.
 *
 * All variables are required, and an invalid private key fails fast: the service crashes on startup
 * rather than silently serving stale data.
 */
private fun buildGitHubIssueStore(): GitHubIssueStore {
  val clientId =
      System.getenv(gitHubAppClientIdEnvVarName)
          ?: error("$gitHubAppClientIdEnvVarName environment variable must be set")

  val pemContent =
      System.getenv(gitHubAppPemContentEnvVarName)
          ?: error("$gitHubAppPemContentEnvVarName environment variable must be set")

  val repoOwner =
      System.getenv(gitHubRepoOwnerEnvVarName)
          ?: error("$gitHubRepoOwnerEnvVarName environment variable must be set")

  val repoName =
      System.getenv(gitHubRepoNameEnvVarName)
          ?: error("$gitHubRepoNameEnvVarName environment variable must be set")

  return GitHubAppIssueStore(
      GitHubAppConfig(
          clientId = clientId,
          pemContent = pemContent,
          repoOwner = repoOwner,
          repoName = repoName,
      )
  )
}
