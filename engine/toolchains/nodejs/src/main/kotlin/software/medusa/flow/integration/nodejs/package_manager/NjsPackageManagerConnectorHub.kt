package software.medusa.flow.integration.nodejs.package_manager

internal data class NjsPackageManagerConnectorHub(
    val npmConnector: NjsNpmConnector,
    val yarnConnector: NjsYarnConnector,
)
