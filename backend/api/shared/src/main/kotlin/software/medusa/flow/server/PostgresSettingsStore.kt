package software.medusa.flow.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.flow.db.FlowDatabase

/**
 * A Postgres-backed [SettingsStore] (SQLDelight queries over the Flyway-owned schema), following
 * the [PostgresWorkerStore] pattern. The `settings` table is a singleton seeded by V11; [get] falls
 * back to [SettingsStore.defaults] if the row is somehow absent (an unmigrated deployment) rather
 * than throwing.
 */
class PostgresSettingsStore(
    private val database: FlowDatabase,
) : SettingsStore {
  private val queries = database.settingsQueries

  override suspend fun get(): FlowSettings =
      withContext(Dispatchers.IO) {
        queries.getSettings().executeAsOneOrNull()?.let { FlowSettings(autoMerge = it.auto_merge) }
            ?: SettingsStore.defaults
      }

  override suspend fun update(
      settings: FlowSettings,
  ): FlowSettings =
      withContext(Dispatchers.IO) {
        queries.updateSettings(auto_merge = settings.autoMerge)
        get()
      }
}
