package software.medusa.flow.server

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The `Instant` <-> `OffsetDateTime` (UTC) seam every Postgres store crosses to talk to a
 * `TIMESTAMPTZ` column. Shared so the four Postgres stores don't each redefine it, and so a later
 * `kotlin.time` migration only has to change it here.
 */
internal fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
