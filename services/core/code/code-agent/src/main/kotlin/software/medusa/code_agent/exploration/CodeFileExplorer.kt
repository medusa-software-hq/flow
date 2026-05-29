package software.medusa.code_agent.exploration

import software.medusa.code_agent.structure.CodeFileStructure
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.commons.paths.UnixPath
import software.medusa.markdown.MarkdownBlock

interface CodeFileExplorer {
  data class ExplorationResult(
      /** A single-paragraph natural language summary of the code file content. */
      val fileSummary: MarkdownBlock.Paragraph,
      /** The structure of the code file. */
      val fileStructure: CodeFileStructure,
  ) {
    companion object
  }

  suspend fun exploreFile(
      fileName: UnixPath.Name.Literal,
      fileContent: TechFileContent.Code,
  ): ExplorationResult
}
