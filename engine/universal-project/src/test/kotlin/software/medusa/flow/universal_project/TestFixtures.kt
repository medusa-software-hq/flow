package software.medusa.flow.universal_project

import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.commons.unix.path.UfsName

internal fun absolutePath(
    unixPath: String,
): UfsLiteralAbsolutePath = checkNotNull(UfsAbsolutePath.parse(unixPath).toLiteral())

/**
 * Creates a child module directory named [name] containing its manifest and, optionally, a `src/`
 * directory populated with empty files named [sourceFileNames].
 */
internal suspend fun UfsMutableDirectory.createModule(
    name: String,
    manifestFileName: String,
    manifestYaml: String,
    sourceFileNames: List<String> = emptyList(),
): UfsMutableDirectory {
  val moduleDirectory = createDirectory(name = UfsName.Literal(name))

  moduleDirectory.createFile(
      name = UfsName.Literal(manifestFileName),
      initialContent = manifestYaml.encodeToByteString(),
  )

  if (sourceFileNames.isNotEmpty()) {
    val sourceDirectory = moduleDirectory.createDirectory(name = UfsName.Literal("src"))

    sourceFileNames.forEach { fileName ->
      sourceDirectory.createFile(
          name = UfsName.Literal(fileName),
          initialContent = "// $fileName".encodeToByteString(),
      )
    }
  }

  return moduleDirectory
}
