package software.medusa.flow.harness

import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory

interface HrsReadonlyTemporaryWorkspace : AutoCloseable {
  val rootDirectory: UfsReadonlyDirectory
}
