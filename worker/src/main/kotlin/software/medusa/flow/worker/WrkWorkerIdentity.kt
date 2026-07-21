package software.medusa.flow.worker

import java.net.InetAddress
import java.util.UUID

/**
 * How a worker identifies itself to the control-plane fleet registry (M5): a stable [workerId] plus
 * the build it runs ([workerVersion] / [imageDigest]).
 *
 * Version surfacing is deliberately a thin stub in story 01 — [workerVersion] and [imageDigest]
 * default to empty ("unknown"). Story 04 wires the real build-stamped version/digest through
 * [fromEnvironment] without touching the RPC or registration loop.
 */
data class WrkWorkerIdentity(
    val workerId: String,
    val workerVersion: String,
    val imageDigest: String,
) {
  companion object {
    private const val workerIdEnvVarName = "FLOW_WORKER_ID"
    // Story 04 populates these from the build stamp / container runtime.
    private const val workerVersionEnvVarName = "FLOW_WORKER_VERSION"
    private const val imageDigestEnvVarName = "FLOW_WORKER_IMAGE_DIGEST"

    /**
     * Resolves the identity from the environment. [workerId] prefers an explicit `FLOW_WORKER_ID`
     * (so an admin can pin a stable identity across restarts), else the host name, else a random
     * per-process UUID — always non-blank, so registration never fails on a missing id.
     */
    fun fromEnvironment(
        lookup: (String) -> String? = System::getenv,
    ): WrkWorkerIdentity =
        WrkWorkerIdentity(
            workerId =
                lookup(workerIdEnvVarName)?.takeIf { it.isNotBlank() }
                    ?: hostNameOrNull()
                    ?: "worker-${UUID.randomUUID()}",
            workerVersion = lookup(workerVersionEnvVarName)?.takeIf { it.isNotBlank() }.orEmpty(),
            imageDigest = lookup(imageDigestEnvVarName)?.takeIf { it.isNotBlank() }.orEmpty(),
        )

    private fun hostNameOrNull(): String? =
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
  }
}
