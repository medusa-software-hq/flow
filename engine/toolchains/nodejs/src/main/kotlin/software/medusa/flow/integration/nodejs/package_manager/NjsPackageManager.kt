package software.medusa.flow.integration.nodejs.package_manager

sealed class NjsPackageManager {
  data object Npm : NjsPackageManager() {
    override fun selectConnector(
        connectorHub: NjsPackageManagerConnectorHub,
    ): NjsPackageManagerConnector = connectorHub.npmConnector
  }

  data object Yarn : NjsPackageManager() {
    override fun selectConnector(
        connectorHub: NjsPackageManagerConnectorHub,
    ): NjsPackageManagerConnector = connectorHub.yarnConnector
  }

  internal abstract fun selectConnector(
      connectorHub: NjsPackageManagerConnectorHub,
  ): NjsPackageManagerConnector
}
