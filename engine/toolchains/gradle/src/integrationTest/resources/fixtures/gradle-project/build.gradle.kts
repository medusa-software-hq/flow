tasks.register("hello") { doLast { println("hello from gradle") } }

tasks.register("boom") { doLast { throw GradleException("boom") } }

// Stands in for a real dependency jar in the shared Gradle module cache. Recreates the jar (a
// fresh "download") whenever it's missing, then reads an entry out of it the same way Gradle's own
// classpath analysis does — so a truncated jar fails the same way a poisoned module-cache entry
// does in production: "Could not read file: <path>!/<entry>".
tasks.register("readCachedJar") {
  doLast {
    val cacheJar = layout.projectDirectory.file("fake-cache/fixture-dep.jar").asFile

    if (!cacheJar.exists()) {
      cacheJar.parentFile.mkdirs()
      java.util.zip.ZipOutputStream(cacheJar.outputStream()).use { zip ->
        zip.putNextEntry(java.util.zip.ZipEntry("Marker.class"))
        zip.write(byteArrayOf(1, 2, 3))
        zip.closeEntry()
      }
    }

    try {
      java.util.zip.ZipFile(cacheJar).use { zip ->
        val entry = zip.entries().nextElement()
        zip.getInputStream(entry).use { it.readBytes() }
      }
    } catch (e: Exception) {
      throw GradleException("Could not read file: ${cacheJar.absolutePath}!/Marker.class", e)
    }
  }
}
