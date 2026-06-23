package software.medusa.flow.integration.nodejs.package_manager

data class NjsPackageManagerConnectorHub(
    val npmConnector: NjsNpmConnector,
    val yarnConnector: NjsYarnConnector,
)
