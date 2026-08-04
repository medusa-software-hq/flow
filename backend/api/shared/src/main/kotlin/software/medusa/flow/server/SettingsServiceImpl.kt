package software.medusa.flow.server

import software.medusa.flow.v1.GetSettingsRequest
import software.medusa.flow.v1.GetSettingsResponse
import software.medusa.flow.v1.SettingsServiceGrpcKt
import software.medusa.flow.v1.UpdateSettingsRequest
import software.medusa.flow.v1.UpdateSettingsResponse
import software.medusa.flow.v1.getSettingsResponse
import software.medusa.flow.v1.updateSettingsResponse

/**
 * [SettingsService][SettingsServiceGrpcKt]: the Quick Settings mechanism's Web/CLI read+write
 * surface. Runs behind the shared Google-ID-token auth decorator (any signed-in user), like
 * [PipelineServiceImpl] — Quick Settings is global and human-editable, not gated by the worker SA
 * allowlist.
 *
 * `UpdateSettings` is a full replace (no field mask): fine for now with one field, and the natural
 * place to add a mask if/when Quick Settings grows enough fields that partial updates matter.
 */
class SettingsServiceImpl(
    private val settingsStore: SettingsStore,
) : SettingsServiceGrpcKt.SettingsServiceCoroutineImplBase() {
  override suspend fun getSettings(
      request: GetSettingsRequest,
  ): GetSettingsResponse = getSettingsResponse { settings = settingsStore.get().toProto() }

  override suspend fun updateSettings(
      request: UpdateSettingsRequest,
  ): UpdateSettingsResponse = updateSettingsResponse {
    settings = settingsStore.update(request.settings.toDomain()).toProto()
  }
}
