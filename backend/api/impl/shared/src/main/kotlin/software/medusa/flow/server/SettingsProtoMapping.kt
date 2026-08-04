package software.medusa.flow.server

import software.medusa.flow.v1.Settings as ProtoSettings
import software.medusa.flow.v1.settings

/** Conversions between the storage [FlowSettings] domain type and its generated proto type. */
fun FlowSettings.toProto(): ProtoSettings = settings { autoMerge = this@toProto.autoMerge }

fun ProtoSettings.toDomain(): FlowSettings = FlowSettings(autoMerge = autoMerge)
