package software.medusa.flow.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.flow.v1.GetSettingsRequest
import software.medusa.flow.v1.settings
import software.medusa.flow.v1.updateSettingsRequest

class SettingsServiceImpl_tests {
  @Test
  fun `getSettings reflects the store default before anything is written`() = runBlocking {
    val service = SettingsServiceImpl(InMemorySettingsStore())

    val response = service.getSettings(GetSettingsRequest.getDefaultInstance())

    assertFalse(response.settings.autoMerge)
  }

  @Test
  fun `updateSettings persists and getSettings then reflects it`() = runBlocking {
    val store = InMemorySettingsStore()
    val service = SettingsServiceImpl(store)

    val response =
        service.updateSettings(updateSettingsRequest { settings = settings { autoMerge = true } })

    assertTrue(response.settings.autoMerge)
    assertTrue(service.getSettings(GetSettingsRequest.getDefaultInstance()).settings.autoMerge)
    assertTrue(store.get().autoMerge)
  }
}
