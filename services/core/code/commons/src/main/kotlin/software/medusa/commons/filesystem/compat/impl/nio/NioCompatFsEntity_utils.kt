package software.medusa.commons.filesystem.compat.impl.nio

import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import software.medusa.commons.filesystem.compat.MutableCompatFsEntity

data object NioCompatFsEntity_utils {
  fun load(
      entityPath: Path,
  ): MutableCompatFsEntity =
      when {
        entityPath.isRegularFile(LinkOption.NOFOLLOW_LINKS) ->
            NioCompatFsFile(
                filePath = entityPath,
            )

        entityPath.isDirectory(LinkOption.NOFOLLOW_LINKS) ->
            NioCompatFsDirectory(
                directoryPath = entityPath,
            )

        else ->
            throw IllegalStateException(
                "Unexpected or non-existing file type for path: $entityPath",
            )
      }
}
