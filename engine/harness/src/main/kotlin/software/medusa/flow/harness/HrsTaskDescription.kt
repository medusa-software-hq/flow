package software.medusa.flow.harness

import software.medusa.commons.markdown.MdChapter

/**
 * A task for the engine, as its whole root Markdown chapter — title plus every sub-section.
 *
 * It must be the whole chapter, not just its lead [MdChapter.element]: a real issue body carries
 * its substance in `##` sub-sections (Goal, file inventory, definition of done…), which live in
 * [MdChapter.subChapters], not in the blocks directly under the `#` title. Rendering only the lead
 * element yields an empty prompt for any such issue — the engine then sees no task at all.
 */
@JvmInline
value class HrsTaskDescription(
    val body: MdChapter,
)
