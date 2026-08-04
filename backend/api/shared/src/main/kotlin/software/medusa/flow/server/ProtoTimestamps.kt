package software.medusa.flow.server

import com.google.protobuf.Timestamp
import kotlin.time.Instant

/**
 * Shared domain↔proto conversion for timestamps. Every proto mapper routes through this single
 * seam, so the `kotlin.time ↔ java.time` boundary lives here rather than at each call site.
 */
fun Instant.toProtoTimestamp(): Timestamp =
    Timestamp.newBuilder().setSeconds(epochSeconds).setNanos(nanosecondsOfSecond).build()
