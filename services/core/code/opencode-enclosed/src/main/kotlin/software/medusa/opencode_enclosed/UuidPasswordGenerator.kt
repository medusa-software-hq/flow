package software.medusa.opencode_enclosed

import java.util.UUID

data object UuidPasswordGenerator : PasswordGenerator {
  override fun generatePassword(): String = UUID.randomUUID().toString()
}
