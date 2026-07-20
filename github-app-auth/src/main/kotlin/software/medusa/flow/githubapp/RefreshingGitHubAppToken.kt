package software.medusa.flow.githubapp

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches a [GitHubAppTokenMinter]'s installation token and re-mints when it nears expiry, so a
 * long-running caller — a worker session that clones, runs the engine (possibly for a while), then
 * pushes — always presents a valid token without minting on every git invocation. Coroutine-safe.
 */
class RefreshingGitHubAppToken(
    private val minter: GitHubAppTokenMinter,
    private val refreshMargin: Duration = Duration.ofMinutes(5),
    private val now: () -> Instant = Instant::now,
) {
  private val mutex = Mutex()
  private var cached: MintedGitHubAppToken? = null

  /** The current installation token, minting or refreshing it if absent or near expiry. */
  suspend fun current(): String = mutex.withLock {
    val existing = cached
    if (existing == null || !now().isBefore(existing.expiresAt.minus(refreshMargin))) {
      cached = minter.mint()
    }
    cached!!.token
  }
}
