package software.medusa.flow.worker

import software.medusa.flow.v1.Session

/** Processes one claimed session to a terminal state (complete or fail) via [apiClient]. */
fun interface WrkSessionProcessor {
  suspend fun process(
      session: Session,
      apiClient: WrkApiClient,
  )
}
