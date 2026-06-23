package software.medusa.flow.test_utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath

/**
 * Copies the classpath resource directory at [resourcePath] into a fresh temporary directory, runs
 * [block] with that directory, and removes it afterwards.
 *
 * [resourcePath] is an absolute classpath location (e.g. `/fixtures/example`). Materializing a
 * read-only resource onto a real, writable directory lets tests treat a bundled fixture as an
 * ordinary on-disk project — for example as the source tree to hand to a workspace allocator.
 */
suspend fun withMaterializedResource(
    resourcePath: UfsLiteralAbsolutePath,
    block: suspend (Path) -> Unit,
) {
  val materializedDirectory = withContext(Dispatchers.IO) { materializeResource(resourcePath) }

  try {
    block(materializedDirectory)
  } finally {
    withContext(Dispatchers.IO) { materializedDirectory.toFile().deleteRecursively() }
  }
}

private fun materializeResource(
    resourcePath: UfsLiteralAbsolutePath,
): Path {
  val classpathLocation = resourcePath.innerPath.toUnixRelativePathString()

  val resourceUrl =
      checkNotNull(resourceClassLoader().getResource(classpathLocation)) {
        "Resource not found on the classpath: ${resourcePath.toUnixAbsolutePathString()}"
      }

  val sourceDirectory = Paths.get(resourceUrl.toURI())
  val targetDirectory = Files.createTempDirectory("materialized-resource-")

  Files.walk(sourceDirectory).use { entries ->
    entries.forEach { sourceEntry ->
      val targetEntry = targetDirectory.resolve(sourceDirectory.relativize(sourceEntry).toString())

      if (Files.isDirectory(sourceEntry)) {
        Files.createDirectories(targetEntry)
      } else {
        Files.createDirectories(checkNotNull(targetEntry.parent))
        Files.copy(sourceEntry, targetEntry)
      }
    }
  }

  return targetDirectory
}

private object ClasspathAnchor

private fun resourceClassLoader(): ClassLoader =
    Thread.currentThread().contextClassLoader ?: ClasspathAnchor::class.java.classLoader
