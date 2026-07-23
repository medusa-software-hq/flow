package software.medusa.flow.server

import com.google.protobuf.Timestamp
import java.time.Instant

internal fun Instant.toProtoTimestamp(): Timestamp =
    Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()
