package software.medusa.flow.harness

interface HrsTemporaryWorkspaceAllocator {
  suspend fun allocateTemporaryWorkspace(): HrsMutableTemporaryWorkspace
}
