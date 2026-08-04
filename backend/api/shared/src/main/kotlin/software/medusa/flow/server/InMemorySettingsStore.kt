package software.medusa.flow.server

/** An in-memory [SettingsStore] for tests and local runs, following [InMemoryWorkerStore]. */
class InMemorySettingsStore : SettingsStore {
  private val lock = Any()
  private var settings: FlowSettings = SettingsStore.defaults

  override suspend fun get(): FlowSettings = synchronized(lock) { settings }

  override suspend fun update(
      settings: FlowSettings,
  ): FlowSettings = synchronized(lock) { this.settings = settings }.let { settings }
}
