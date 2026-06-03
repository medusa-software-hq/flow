package software.medusa.code_agent.virtual_workspace.document

import software.medusa.commons.code.CodeBlock

interface CodeLanguage {
  data object Kotlin : CodeLanguage {
    override val lineCommentPrefix: String = c99LineCommentPrefix
  }

  companion object {
    const val c99LineCommentPrefix: String = "//"

    const val regionOpeningMarker: String = "#region"
    const val regionClosingMarker: String = "#endregion"

    val CodeLanguage.regionClosingCommentLine
      get() =
          buildCommentLine(
              content = regionClosingMarker,
          )

    fun CodeLanguage.buildCommentLine(
        content: String,
    ): CodeBlock.Line =
        CodeBlock.Line(
            content = "$lineCommentPrefix$content",
        )

    fun CodeLanguage.buildRegionOpeningCommentLine(
        content: String,
    ): CodeBlock.Line = buildCommentLine(content = "$regionOpeningMarker $content")
  }

  val lineCommentPrefix: String
}
