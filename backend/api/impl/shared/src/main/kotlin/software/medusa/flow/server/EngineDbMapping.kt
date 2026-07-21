package software.medusa.flow.server

/**
 * Shared string encoding for [Engine] in the database, used by the worker registry's
 * comma-separated `supported_engines` column. Kept identical to the values the sessions table uses
 * for its single `engine` column (`PostgresSessionStore`), so both agree on the wire form.
 */
internal fun Engine.toDbValue(): String =
    when (this) {
      Engine.Unspecified -> "UNSPECIFIED"
      Engine.Builtin -> "BUILTIN"
      Engine.Claude -> "CLAUDE"
    }

internal fun engineFromDbValue(
    value: String,
): Engine =
    when (value) {
      "UNSPECIFIED" -> Engine.Unspecified
      "BUILTIN" -> Engine.Builtin
      "CLAUDE" -> Engine.Claude
      else -> error("Unknown engine: $value")
    }

/** Encodes an engine capability list as the DB's comma-separated form (empty list → ""). */
internal fun List<Engine>.toDbValue(): String = joinToString(",") { it.toDbValue() }

/** Parses the DB's comma-separated engine list ("" → empty list). */
internal fun engineListFromDbValue(
    value: String,
): List<Engine> =
    value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { engineFromDbValue(it) }
