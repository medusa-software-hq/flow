package software.medusa.flow.harness

import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.flow.physical_workspace.PhwWorkspace

class HrsPhysicalTemporaryWorkspace(
    private val physicalWorkspace: PhwWorkspace,
) : HrsMutableTemporaryWorkspace {
  override val rootDirectory: UfsMutableDirectory
    get() = physicalWorkspace.rootDirectory

  override fun close() {
    physicalWorkspace.close()
  }
}
