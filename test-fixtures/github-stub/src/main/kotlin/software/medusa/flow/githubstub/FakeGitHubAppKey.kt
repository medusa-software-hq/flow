package software.medusa.flow.githubstub

import java.security.KeyPairGenerator
import java.util.Base64

/**
 * A throwaway PKCS#8 RSA private key (PEM) for constructing a real `GitHubAppClient` in tests. The
 * client parses this to sign its App JWT; [FakeGitHubServer] never verifies the signature, so any
 * valid key works — this just spares every test from generating one.
 */
object FakeGitHubAppKey {
  val pkcs8Pem: String by lazy {
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val base64 =
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
    "-----BEGIN PRIVATE KEY-----\n$base64\n-----END PRIVATE KEY-----\n"
  }
}
