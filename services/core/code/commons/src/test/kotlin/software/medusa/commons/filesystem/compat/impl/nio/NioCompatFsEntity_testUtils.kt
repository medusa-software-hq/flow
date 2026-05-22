package software.medusa.commons.filesystem.compat.impl.nio

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

data object NioCompatFsEntity_testUtils {
  @OptIn(ExperimentalPathApi::class)
  internal inline fun <T> withTempDir(
      block: (Path) -> T,
  ): T {
    val tempDirPath: Path = Files.createTempDirectory("tmp-")

    return try {
      block(tempDirPath)
    } finally {
      tempDirPath.deleteRecursively()
    }
  }
}
