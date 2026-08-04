package software.medusa.flow.server

import software.medusa.flow.githubapp.GitHubAppConfig

private const val portEnvVarName = "PORT"
private const val clientIdEnvVarName = "GOOGLE_CLIENT_ID"
private const val cliClientIdEnvVarName = "CLI_OAUTH_CLIENT_ID"
private const val allowedDomainEnvVarName = "GOOGLE_ALLOWED_DOMAIN"
private const val corsOriginRegexEnvVarName = "CORS_ALLOWED_ORIGIN_REGEX"
private const val databaseUrlEnvVarName = "DATABASE_URL"
private const val gitHubAppClientIdEnvVarName = "GITHUB_APP_CLIENT_ID"
private const val gitHubAppPemContentEnvVarName = "GITHUB_APP_PEM_CONTENT"
private const val gitHubRepoOwnerEnvVarName = "GITHUB_REPO_OWNER"
private const val gitHubRepoNameEnvVarName = "GITHUB_REPO_NAME"
private const val workerSaEmailsEnvVarName = "WORKER_SA_EMAILS"
private const val schedulerSaEmailsEnvVarName = "SCHEDULER_SA_EMAILS"
private const val workerTokenAudienceEnvVarName = "WORKER_TOKEN_AUDIENCE"
private const val gitHubWebhookSecretEnvVarName = "GITHUB_WEBHOOK_SECRET"
private const val gitHubIssueFieldsEnabledEnvVarName = "GITHUB_ISSUE_FIELDS_ENABLED"

fun main() {
  val port =
      System.getenv(portEnvVarName)?.toIntOrNull()
          ?: error("$portEnvVarName environment variable must be set to a valid integer")

  val clientId =
      System.getenv(clientIdEnvVarName)
          ?: error("$clientIdEnvVarName environment variable must be set")

  // The `flow` CLI signs in with its own Desktop OAuth client (Google only permits the
  // loopback/PKCE flow for Desktop clients), so its ID token's `aud` differs from the browser SPA's
  // Web client. Optional/empty until that client is provisioned; while empty only the SPA audience
  // is accepted, so nothing regresses.
  val cliClientId = System.getenv(cliClientIdEnvVarName).orEmpty()

  val userTokenAudiences = buildSet {
    add(clientId)
    if (cliClientId.isNotBlank()) add(cliClientId)
  }

  val allowedDomain =
      System.getenv(allowedDomainEnvVarName)
          ?: error("$allowedDomainEnvVarName environment variable must be set")

  val corsOriginRegex =
      System.getenv(corsOriginRegexEnvVarName)
          ?: error("$corsOriginRegexEnvVarName environment variable must be set")

  val databaseUrl =
      System.getenv(databaseUrlEnvVarName)
          ?: error("$databaseUrlEnvVarName environment variable must be set")

  // The API's own public URL — the `aud` a worker's service-account ID token is minted with (see
  // WrkGrpcApiClient), distinct from GOOGLE_CLIENT_ID (the browser sign-in flow's audience).
  val workerTokenAudience =
      System.getenv(workerTokenAudienceEnvVarName)
          ?: error("$workerTokenAudienceEnvVarName environment variable must be set")

  // Optional and empty by default (denies every worker) rather than required: the SA that will
  // populate this (story 05, infra) is provisioned after this service already depends on it, and a
  // missing/unconfigured allowlist should not crash the whole API.
  val workerSaEmails = System.getenv(workerSaEmailsEnvVarName).orEmpty()

  // Reconcile is callable by the worker SA and the scheduler SA. The scheduler SA env var is
  // populated by the scheduler infra (story 11); optional/empty until then.
  val schedulerSaEmails = System.getenv(schedulerSaEmailsEnvVarName).orEmpty()

  // GitHub webhook HMAC secret (Secret Manager → env). Optional/empty until the App webhook is
  // configured; while empty the webhook route rejects every request, degrading to scheduler
  // cadence.
  val gitHubWebhookSecret = System.getenv(gitHubWebhookSecretEnvVarName).orEmpty()

  // Reads the `Priority` Issue Field alongside `priority:*` labels (see IssuePriority /
  // GitHubAppCandidateClient's `readPriorityField` KDoc for the preview-API caveat). Off by
  // default — flip on per-environment only after confirming the GraphQL fragment against a live
  // repo's schema.
  val gitHubIssueFieldsEnabled =
      System.getenv(gitHubIssueFieldsEnabledEnvVarName).orEmpty().toBoolean()

  // One database (pool + migrations) shared by every Postgres-backed store.
  val database = buildFlowDatabase(databaseUrl)

  // One authenticated GitHub App client shared by every GitHubApp*Store.
  val gitHubAppClient = buildGitHubAppClient()

  buildServer(
          originRegex = corsOriginRegex,
          port = port,
          auth =
              GoogleIdTokenAuthDecorator(
                  userTokenAudiences = userTokenAudiences,
                  allowedDomain = allowedDomain,
                  workerTokenAudience = workerTokenAudience,
              ),
          gitHubIssueStore = GitHubAppIssueStore(gitHubAppClient),
          gitHubRepositoryStore = GitHubAppRepositoryStore(gitHubAppClient),
          sessionStore = PostgresSessionStore(database),
          workerAuthorizer = buildWorkerAuthorizer(workerSaEmails),
          workerStore = PostgresWorkerStore(database),
          issuePipelineStore = PostgresIssuePipelineStore(database),
          githubOutboxStore = PostgresGithubOutboxStore(database),
          settingsStore = PostgresSettingsStore(database),
          gitHubIssueClient = GitHubAppIssueClient(gitHubAppClient),
          gitHubPrClient = GitHubAppPrClient(gitHubAppClient),
          gitHubCandidateClient =
              GitHubAppCandidateClient(
                  gitHubAppClient,
                  readPriorityField = gitHubIssueFieldsEnabled,
              ),
          reconcileAuthorizer = buildWorkerAuthorizer("$workerSaEmails,$schedulerSaEmails"),
          gitHubWebhookSecret = gitHubWebhookSecret,
          // Fence the reconciler to our org — it must never act on a repo outside it.
          reconcileOrgOwner = gitHubAppClient.repoOwner,
      )
      .start()
      .join()
}

/**
 * Builds a [GitHubAppClient] from the GitHub App environment variables.
 *
 * All variables are required, and an invalid private key fails fast: the service crashes on startup
 * rather than silently serving stale data.
 */
private fun buildGitHubAppClient(): GitHubAppClient {
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

  return GitHubAppClient(
      GitHubAppConfig(
          clientId = clientId,
          pemContent = pemContent,
          repoOwner = repoOwner,
          repoName = repoName,
      )
  )
}

/**
 * Parses [workerSaEmails] as a comma-separated allowlist
 * ([WORKER_SA_EMAILS][workerSaEmailsEnvVarName]).
 */
private fun buildWorkerAuthorizer(
    workerSaEmails: String,
): WorkerAuthorizer =
    WorkerAuthorizer.allowlist(
        workerSaEmails.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    )
