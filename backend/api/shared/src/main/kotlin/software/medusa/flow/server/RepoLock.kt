package software.medusa.flow.server

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes reconcile work per repository so concurrent triggers (scheduler + webhook) don't run
 * the same repo's phases at once.
 */
interface RepoLock {
  suspend fun <T> withRepoLock(
      repoFullName: String,
      block: suspend () -> T,
  ): T
}

/**
 * A per-repo in-process lock — sufficient for M2's single-instance control plane. (Correctness of
 * picking never depends on this lock: the `issue_pipelines` partial unique indexes make a
 * double-pick a SQL constraint violation regardless. The lock just avoids wasted concurrent work
 * and duplicate annotation comments.)
 *
 * Multi-instance safety would need a Postgres advisory lock keyed on the repo; deferred until a
 * hosted, horizontally-scaled control plane exists.
 */
class InMemoryRepoLock : RepoLock {
  private val mutexByRepo = ConcurrentHashMap<String, Mutex>()

  override suspend fun <T> withRepoLock(
      repoFullName: String,
      block: suspend () -> T,
  ): T = mutexByRepo.getOrPut(repoFullName) { Mutex() }.withLock { block() }
}
