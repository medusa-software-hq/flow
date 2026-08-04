package software.medusa.flow.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

/**
 * [InMemorySettingsStore] behaviour every [SettingsStore] impl must satisfy: default + round-trip.
 */
class InMemorySettingsStore_tests {
  @Test
  fun `defaults to auto-merge off before anything is written`() = runBlocking {
    val store = InMemorySettingsStore()

    assertFalse(store.get().autoMerge)
  }

  @Test
  fun `update persists and is reflected by a subsequent get`() = runBlocking {
    val store = InMemorySettingsStore()

    val updated = store.update(FlowSettings(autoMerge = true))

    assertEquals(FlowSettings(autoMerge = true), updated)
    assertEquals(FlowSettings(autoMerge = true), store.get())
  }

  @Test
  fun `update can flip the setting back off`() = runBlocking {
    val store = InMemorySettingsStore()
    store.update(FlowSettings(autoMerge = true))

    store.update(FlowSettings(autoMerge = false))

    assertFalse(store.get().autoMerge)
  }
}
