package software.medusa.flow.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowCredentialsTest {
  private val dir: Path = Files.createTempDirectory("flow-creds-test")

  @AfterTest fun cleanup() = dir.toFile().deleteRecursively().let {}

  @Test
  fun `save then load round-trips`() {
    val creds = FlowCredentials("refresh-1", "id-1", 1893456000, "user@medusa.software")
    saveFlowCredentials(creds, dir)
    assertEquals(creds, loadFlowCredentials(dir))
  }

  @Test
  fun `credentials file is owner-only`() {
    saveFlowCredentials(FlowCredentials("r", "i", 1, "e"), dir)
    val perms = Files.getPosixFilePermissions(flowCredentialsFile(dir))
    assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
  }

  @Test
  fun `load returns null when absent, delete removes it`() {
    assertNull(loadFlowCredentials(dir))
    saveFlowCredentials(FlowCredentials("r", "i", 1, "e"), dir)
    deleteFlowCredentials(dir)
    assertNull(loadFlowCredentials(dir))
  }
}

class FlowSessionTest {
  private val dir: Path = Files.createTempDirectory("flow-session-test")

  @AfterTest fun cleanup() = dir.toFile().deleteRecursively().let {}

  private val farFuture = 4_102_444_800L // 2100
  private val longAgo = 1_000_000_000L // 2001

  @Test
  fun `returns the cached token while it is still valid`() {
    saveFlowCredentials(FlowCredentials("r", "cached-id", farFuture, "e"), dir)
    val session =
        FlowSession(
            dir = dir,
            nowEpochSec = { 1_700_000_000 },
            refresher = { error("must not refresh") },
        )
    assertEquals("cached-id", session.currentIdToken())
  }

  @Test
  fun `refreshes an expired token and persists the new one`() {
    saveFlowCredentials(FlowCredentials("refresh-1", "old-id", longAgo, "e"), dir)
    var seenRefreshToken: String? = null
    val session =
        FlowSession(
            dir = dir,
            nowEpochSec = { 1_700_000_000 },
            refresher = { rt ->
              seenRefreshToken = rt
              FlowTokenSet("new-id", refreshToken = null, expiresAtEpochSec = farFuture)
            },
        )

    assertEquals("new-id", session.currentIdToken())
    assertEquals("refresh-1", seenRefreshToken)
    // The refresh token is preserved; the id token + expiry are updated on disk.
    val stored = loadFlowCredentials(dir)!!
    assertEquals("refresh-1", stored.refreshToken)
    assertEquals("new-id", stored.idToken)
    assertEquals(farFuture, stored.idTokenExpiresAtEpochSec)
  }

  @Test
  fun `a failed refresh surfaces as not-logged-in`() {
    saveFlowCredentials(FlowCredentials("refresh-1", "old-id", longAgo, "e"), dir)
    val session =
        FlowSession(
            dir = dir,
            nowEpochSec = { 1_700_000_000 },
            refresher = { throw FlowOAuthException("invalid_grant", "revoked") },
        )
    val error = assertFailsWith<FlowNotLoggedInException> { session.currentIdToken() }
    assertTrue(error.message!!.contains("login"))
  }

  @Test
  fun `no cached session is not-logged-in`() {
    val session = FlowSession(dir = dir, refresher = { error("unused") })
    assertFailsWith<FlowNotLoggedInException> { session.currentIdToken() }
  }
}
