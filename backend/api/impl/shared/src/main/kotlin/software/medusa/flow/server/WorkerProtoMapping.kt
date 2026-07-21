package software.medusa.flow.server

import com.google.protobuf.Timestamp
import java.time.Instant
import software.medusa.flow.v1.WorkerInfo as ProtoWorkerInfo
import software.medusa.flow.v1.workerInfo

/** Conversions for the worker registry (M5) between the storage domain and the generated proto. */
private fun Instant.toProtoTimestamp(): Timestamp =
    Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()

fun RegisteredWorker.toProto(): ProtoWorkerInfo {
  val domain = this
  return workerInfo {
    workerId = domain.workerId
    workerVersion = domain.workerVersion
    imageDigest = domain.imageDigest
    lastSeenAt = domain.lastSeenAt.toProtoTimestamp()
    firstSeenAt = domain.firstSeenAt.toProtoTimestamp()
  }
}
