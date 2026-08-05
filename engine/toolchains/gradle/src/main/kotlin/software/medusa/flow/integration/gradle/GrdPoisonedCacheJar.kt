package software.medusa.flow.integration.gradle

import java.io.File

/**
 * Matches the "Could not read file: <path>!/<entry>" signature Gradle reports when a task opens a
 * truncated/corrupt jar from the module cache — the shape a build leaves behind when it's killed
 * (OOM, ENOSPC, timeout) mid-download. `!/` is the jar-URL separator between the archive path and
 * the entry inside it that failed to read.
 */
private val POISONED_CACHE_JAR_PATTERN = Regex("""Could not read file: (.+?)!/""")

/**
 * Extracts the path of a truncated cache jar from Gradle build output, if the output carries the
 * "Could not read file" signature of a poisoned module-cache entry. Returns `null` for any other
 * failure (a real compile error, a missing task, ...) so callers only act on this specific,
 * self-inflicted failure mode rather than retrying builds that are failing for a legitimate reason.
 */
internal fun findPoisonedCacheJar(buildOutput: String): File? =
    POISONED_CACHE_JAR_PATTERN.find(buildOutput)?.let { match -> File(match.groupValues[1]) }
