package software.medusa.flow.server

import com.google.protobuf.Timestamp
import java.time.Instant
import software.medusa.flow.v1.Engine as ProtoEngine
import software.medusa.flow.v1.WorkerInfo as ProtoWorkerInfo
import software.medusa.flow.v1.workerInfo

/** Conversions for the worker registry (M5) between the storage domain and the generated proto. */
private fun Instant.toProtoTimestamp(): Timestamp =
    Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()

private fun Engine.toProto(): ProtoEngine =
    when (this) {
      Engine.Unspecified -> ProtoEngine.ENGINE_UNSPECIFIED
      Engine.Builtin -> ProtoEngine.ENGINE_BUILTIN
      Engine.Claude -> ProtoEngine.ENGINE_CLAUDE
    }

fun RegisteredWorker.toProto(): ProtoWorkerInfo {
  val domain = this
  return workerInfo {
    workerId = domain.workerId
    workerVersion = domain.workerVersion
    imageDigest = domain.imageDigest
    supportedEngines.addAll(domain.supportedEngines.map { it.toProto() })
    lastSeenAt = domain.lastSeenAt.toProtoTimestamp()
    firstSeenAt = domain.firstSeenAt.toProtoTimestamp()
  }
}
