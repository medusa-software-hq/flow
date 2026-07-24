package software.medusa.flow.server

import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * The `Instant` <-> `OffsetDateTime` (UTC) seam every Postgres store crosses to talk to a
 * `TIMESTAMPTZ` column. Shared so the four Postgres stores don't each redefine it, and so the
 * `kotlin.time ↔ java.time` boundary lives here rather than at each call site.
 */
internal fun Instant.toOffsetDateTime(): OffsetDateTime = toJavaInstant().atOffset(ZoneOffset.UTC)

internal fun OffsetDateTime.toKotlinInstant(): Instant = toInstant().toKotlinInstant()
