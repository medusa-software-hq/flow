package software.medusa.flow.physical_workspace.temp

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.flow.physical_workspace.PhwConnectorHub
import software.medusa.flow.physical_workspace.PhwWorkspace
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator

class PhwTempWorkspaceAllocator(
    private val coroutineScope: CoroutineScope,
    private val connectorHub: PhwConnectorHub,
) : PhwWorkspaceAllocator {
  override suspend fun allocateWorkspace(): PhwWorkspace {
    val tempWorkspacePath =
        withContext(Dispatchers.IO) { Files.createTempDirectory("temp-workspace-") }

    return PhwTempWorkspace(
        coroutineScope = coroutineScope,
        connectorHub = connectorHub,
        tempDirectoryPath = tempWorkspacePath,
    )
  }
}
