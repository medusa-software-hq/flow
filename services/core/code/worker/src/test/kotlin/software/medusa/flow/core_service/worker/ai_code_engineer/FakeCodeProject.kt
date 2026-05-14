package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.impl.memory.MemoryCompatFsDirectory
import software.medusa.flow.core_service.worker.code_project.CodeProject

class FakeCodeProject : CodeProject {
  override val workingDirectory: MutableCompatFsDirectory = MemoryCompatFsDirectory()

  override suspend fun format(): CodeProject.FormattingResult =
      CodeProject.FormattingResult.Formatted

  override suspend fun analyze(): CodeProject.AnalysisResult = CodeProject.AnalysisResult.Accepted

  override suspend fun test(): CodeProject.TestingResult = CodeProject.TestingResult.AllPassed
}
