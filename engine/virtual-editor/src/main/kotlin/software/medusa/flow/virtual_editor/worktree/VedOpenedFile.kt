package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.flat_worktree.VedFlatOpenedFile

data class VedOpenedFile(
    val contentVersionHistory: ContentVersionHistory,
    /**
     * Whether this file is on the leader's board. Orthogonal to the content history: opening (or
     * patching) a file never changes its exposure; only the assistant's expose/hide tools (and the
     * leader's `Delegate` hide-list) do. Defaults to [VedExposure.Hidden] — opening is not
     * exposing.
     */
    val exposure: VedExposure = VedExposure.Hidden,
) : VedFile() {
  /** The most recent content of the file. */
  val currentContent: TxtFileContent
    get() = contentVersionHistory.lastVersion.content

  /** The delegation index at which this file entered the journal (its first content version). */
  val openedTimestamp: VedTimestamp
    get() = contentVersionHistory.contentVersions.first().modificationTimestamp

  /** The delegation index of the file's most recent content version. */
  val lastEditedTimestamp: VedTimestamp
    get() = contentVersionHistory.lastVersion.modificationTimestamp

  /** True once the file has been patched since it was opened (a later net version exists). */
  val isEdited: Boolean
    get() = openedTimestamp != lastEditedTimestamp

  data class ContentVersion(
      val content: TxtFileContent,
      val modificationTimestamp: VedTimestamp,
  )

  @JvmInline
  value class ContentVersionHistory(
      /** Content versions, from the oldest to the most recent; timestamps strictly increasing. */
      val contentVersions: List<ContentVersion>,
  ) {
    init {
      require(contentVersions.isNotEmpty()) { "There must be at least one content version" }
      require(
          contentVersions.zipWithNext().all { (earlier, later) ->
            earlier.modificationTimestamp < later.modificationTimestamp
          },
      ) {
        "Content version timestamps must be strictly increasing"
      }
    }

    val lastVersion: ContentVersion
      get() = contentVersions.last()

    /**
     * Records [newContent] at [timestamp]. Within a single delegation (equal timestamp) the last
     * version is *replaced* — intermediate churn never enters the journal, so there is exactly one
     * net version per file per delegation. A later timestamp appends; an earlier one is rejected
     * (the stored history stays strictly increasing).
     */
    fun extend(
        newContent: TxtFileContent,
        timestamp: VedTimestamp,
    ): ContentVersionHistory {
      require(timestamp >= lastVersion.modificationTimestamp) {
        "Cannot extend history at t=${timestamp.t}, before the last version at t=${lastVersion.modificationTimestamp.t}"
      }

      val newVersion = ContentVersion(content = newContent, modificationTimestamp = timestamp)

      val retainedVersions =
          when (timestamp) {
            lastVersion.modificationTimestamp -> contentVersions.dropLast(1)
            else -> contentVersions
          }

      return ContentVersionHistory(contentVersions = retainedVersions + newVersion)
    }
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
      // `copy` preserves exposure: patching a file never moves it on or off the leader's board.
      copy(
          contentVersionHistory =
              contentVersionHistory.extend(
                  newContent = newContent,
                  timestamp = timestamp,
              ),
      )

  /** Returns this file with its exposure set to [exposure] (last-write-wins; content untouched). */
  fun withExposure(
      exposure: VedExposure,
  ): VedOpenedFile = copy(exposure = exposure)

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
