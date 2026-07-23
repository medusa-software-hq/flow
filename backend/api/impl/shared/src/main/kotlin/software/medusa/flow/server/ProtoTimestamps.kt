package software.medusa.flow.server

import com.google.protobuf.Timestamp
import java.time.Instant

/**
 * Shared domain↔proto conversion for timestamps. Every proto mapper routes through this single
 * seam, so a future `kotlin.time` migration only has to change it here.
 */
fun Instant.toProtoTimestamp(): Timestamp =
    Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()
