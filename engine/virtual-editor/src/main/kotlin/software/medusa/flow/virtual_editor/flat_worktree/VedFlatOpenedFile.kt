package software.medusa.flow.virtual_editor.flat_worktree

import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.virtual_editor.VedTimestamp

data class VedFlatOpenedFile(
    val path: UfsLiteralAbsolutePath,
    val content: TxtFileContent,
    val modificationTimestamp: VedTimestamp,
) {
  data class SortKey(
      val modificationTimestamp: VedTimestamp,
      val path: UfsLiteralAbsolutePath,
  ) : Comparable<SortKey> {
    override fun compareTo(other: SortKey): Int =
        compareValuesBy(
            this,
            other,
            { it.modificationTimestamp },
            { it.path.toUnixAbsolutePathString() },
        )
  }

  val sortKey: SortKey
    get() =
        SortKey(
            modificationTimestamp = modificationTimestamp,
            path = path,
        )
}
