package software.medusa.flow.systemtest

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach

/**
 * Base for every system test: resolves the staging config and builds the authenticated clients
 * before each test, and — crucially — **skips** (JUnit assumption) rather than fails when the
 * environment is not configured. That keeps `./gradlew :system-tests:systemTest` harmless on a
 * laptop or CI job without staging wiring (story 01's "gated on config being present"), while a
 * fully-wired run exercises the real environment.
 */
abstract class SystemTestBase {
  protected lateinit var config: StagingConfig
  protected lateinit var clients: StagingClients

  @BeforeEach
  fun setUpStagingEnvironment() {
    val resolved = StagingConfig.fromEnvironment()
    Assumptions.assumeTrue(resolved != null) {
      "system-test skipped: API_URL is not set (no staging environment configured)"
    }
    config = resolved!!
    clients = StagingClients.forConfig(config)
    log(
        "staging: api=${config.apiUrl}, web=${config.webUrl}, " +
            "identity=${config.workerSaEmail ?: "ADC"}",
    )
  }

  protected fun log(
      message: String,
  ) {
    println("[system-test] $message")
  }
}
