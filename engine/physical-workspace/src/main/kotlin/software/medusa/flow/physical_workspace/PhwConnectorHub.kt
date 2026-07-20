package software.medusa.flow.physical_workspace

import software.medusa.flow.integration.gradle.GrdProjectConnector
import software.medusa.flow.integration.nodejs.NjsPackageConnector

data class PhwConnectorHub(
    val gradleProjectConnector: GrdProjectConnector,
    val nodeJsPackageConnector: NjsPackageConnector,
)
