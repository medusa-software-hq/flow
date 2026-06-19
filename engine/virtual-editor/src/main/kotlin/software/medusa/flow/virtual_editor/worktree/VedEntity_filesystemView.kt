package software.medusa.flow.virtual_editor.worktree

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectoryIndex
import software.medusa.commons.unix.filesystem.UfsReadonlyEntity
import software.medusa.commons.unix.filesystem.UfsReadonlyFile
import software.medusa.commons.unix.path.UfsName

/**
 * A read-only filesystem view over the virtual editor's model of this directory.
 *
 * The view is an _overlay_ exposing only the parts the editor actually holds materialized content
 * for: expanded directories and opened files. Collapsed directories and closed files are omitted,
 * because the editor has no content to expose for them. Layering this overlay on top of the
 * original worktree (e.g. via `copyRecursivelyTo`) therefore reflects exactly the edited, open
 * files while leaving everything else untouched.
 */
val VedExpandedDirectory.asFilesystemEntity: UfsReadonlyDirectory
  get() =
      object : UfsReadonlyDirectory {
        override suspend fun readIndex(): UfsReadonlyDirectoryIndex =
            UfsReadonlyDirectoryIndex(
                childEntityByName =
                    labeledEntityByName
                        .mapNotNull { (name, labeledEntity) ->
                          val childView = labeledEntity.entity.asFilesystemEntityOrNull
                          if (childView == null) null else name to childView
                        }
                        .toMap(),
            )

        override suspend fun extract(
            name: UfsName.Literal,
        ): UfsReadonlyEntity? = labeledEntityByName[name]?.entity?.asFilesystemEntityOrNull
      }

/** A read-only filesystem view over the content of this opened file. */
val VedOpenedFile.asFilesystemEntity: UfsReadonlyFile
  get() =
      object : UfsReadonlyFile {
        override suspend fun read(): ByteString = content.dump().encodeToByteString()
      }

/**
 * The filesystem view of this entity, or `null` when the editor exposes nothing for it (a collapsed
 * directory or a closed file).
 */
internal val VedEntity.asFilesystemEntityOrNull: UfsReadonlyEntity?
  get() =
      when (this) {
        is VedExpandedDirectory -> asFilesystemEntity
        is VedCollapsedDirectory -> null
        is VedOpenedFile -> asFilesystemEntity
        is VedClosedFile -> null
      }
