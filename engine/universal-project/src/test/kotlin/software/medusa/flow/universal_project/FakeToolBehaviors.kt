package software.medusa.flow.universal_project

import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.path.UfsName

/** Writes a generated file into [moduleDirectory], standing in for a code generator. */
internal suspend fun generateFileInto(
    moduleDirectory: UfsMutableDirectory,
) {
  moduleDirectory.createFile(
      name = UfsName.Literal("generated.txt"),
      initialContent = "generated".encodeToByteString(),
  )
}

/** Returns the names of files directly under `src/` that are not all-lowercase, sorted. */
internal suspend fun lowercaseOffenders(
    moduleDirectory: UfsMutableDirectory,
): List<String> {
  val sourceDirectory =
      moduleDirectory.extract(name = UfsName.Literal("src")) as? UfsReadonlyDirectory
          ?: return emptyList()

  return sourceDirectory
      .readIndex()
      .childEntityByName
      .keys
      .map { name -> name.content }
      .filter { fileName -> fileName != fileName.lowercase() }
      .sorted()
}
