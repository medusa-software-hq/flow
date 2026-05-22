package software.medusa.commons.filesystem.compat

/**
 * A filesystem entity exposed through a read-only compatibility API.
 *
 * In practice, this is meant to model either:
 * - a read-only facade over some real filesystem, or
 * - a synthesized filesystem view produced for inspection, diffing, export, or similar purposes.
 */
sealed interface ReadonlyCompatFsEntity
