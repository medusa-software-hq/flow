package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.flat_worktree.VedFlatOpenedFile

data class VedOpenedFile(
    val contentVersionHistory: ContentVersionHistory,
) : VedFile() {
  /** The most recent content of the file. */
  val currentContent: TxtFileContent
    get() = contentVersionHistory.lastVersion.content

  data class ContentVersion(
      val content: TxtFileContent,
      val modificationTimestamp: VedTimestamp,
  )

  @JvmInline
  value class ContentVersionHistory(
      /** Content versions, from the oldest to the most recent. */
      val contentVersions: List<ContentVersion>,
  ) {
    init {
      require(contentVersions.isNotEmpty()) { "There must be at least one content version" }
    }

    val lastVersion: ContentVersion
      get() = contentVersions.last()

    fun extend(
        newContent: TxtFileContent,
        timestamp: VedTimestamp,
    ): ContentVersionHistory =
        ContentVersionHistory(
            contentVersions =
                contentVersions +
                    ContentVersion(
                        content = newContent,
                        modificationTimestamp = timestamp,
                    ),
        )
  }

  data class Visited(
      val filePath: UfsLiteralAbsolutePath,
      val openedFile: VedOpenedFile,
  ) {
    fun flatten(): List<VedFlatOpenedFile> =
        openedFile.contentVersionHistory.contentVersions.map { contentVersion ->
          VedFlatOpenedFile(
              path = filePath,
              content = contentVersion.content,
              modificationTimestamp = contentVersion.modificationTimestamp,
          )
        }
  }

  override fun visitOpenedFile(
      filePath: UfsLiteralAbsolutePath,
  ): Visited =
      Visited(
          filePath = filePath,
          openedFile = this,
      )

  fun update(
      newContent: TxtFileContent,
      timestamp: VedTimestamp,
  ): VedOpenedFile =
      VedOpenedFile(
          contentVersionHistory =
              contentVersionHistory.extend(
                  newContent = newContent,
                  timestamp = timestamp,
              ),
      )

  companion object {
    /** An opened file whose only content version is [content], stamped at [timestamp]. */
    fun of(
        content: TxtFileContent,
        timestamp: VedTimestamp,
    ): VedOpenedFile =
        VedOpenedFile(
            contentVersionHistory =
                ContentVersionHistory(
                    contentVersions =
                        listOf(
                            ContentVersion(content = content, modificationTimestamp = timestamp),
                        ),
                ),
        )
  }
}
