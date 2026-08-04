package software.medusa.flow.server

/**
 * Quick Settings: a single global, per-deployment configuration object (see
 * `settings_service.proto`). Named `FlowSettings` rather than `Settings` to avoid colliding with
 * the SQLDelight-generated `Settings` row type ([software.medusa.flow.db.Settings]) that
 * [PostgresSettingsStore] queries against.
 */
data class FlowSettings(
    val autoMerge: Boolean,
)

/**
 * Storage for [FlowSettings] — a singleton row, not a per-entity store. [get] never returns null:
 * an unseeded/pre-migration deployment reads as [defaults].
 */
interface SettingsStore {
  /** The current settings, or [defaults] if unseeded. */
  suspend fun get(): FlowSettings

  /** Full replace; returns the persisted value. */
  suspend fun update(
      settings: FlowSettings,
  ): FlowSettings

  companion object {
    /** Auto-merge is off until a human turns it on. */
    val defaults = FlowSettings(autoMerge = false)
  }
}
