package software.medusa.code_agent

import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory

interface CodeTaskCompleter {
  suspend fun completeTask(
      codeDirectory: ReadonlyCompatFsDirectory,
      taskDescription: CodeTaskDescription,
  )
}
