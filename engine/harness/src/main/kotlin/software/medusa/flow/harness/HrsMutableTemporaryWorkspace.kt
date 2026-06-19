package software.medusa.flow.harness

import software.medusa.commons.unix.filesystem.UfsMutableDirectory

interface HrsMutableTemporaryWorkspace : HrsReadonlyTemporaryWorkspace {
  override val rootDirectory: UfsMutableDirectory
}
