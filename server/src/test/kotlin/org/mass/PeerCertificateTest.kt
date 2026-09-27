package org.mass

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PeerCertificateTest {
    @Test
    fun `verification code uses the first three bytes of the SPKI hash`() {
        // The same vectors are asserted by the client, which derives the code from the certificate it pinned.
        assertEquals("066051", PeerCertificate.verificationCode(byteArrayOf(0x01, 0x02, 0x03) + ByteArray(29)))
        assertEquals("777215", PeerCertificate.verificationCode(ByteArray(32) { 0xff.toByte() }))
        assertEquals("000000", PeerCertificate.verificationCode(ByteArray(32)))
    }

    @Test
    fun `creates one ECDSA P-256 certificate readable only by the owner and reuses it`() {
        val directory = createTempDirectory().resolve("tls")
        try {
            val created = PeerCertificate.loadOrCreate(directory, "peer-1")
            val reloaded = PeerCertificate.loadOrCreate(directory, "peer-1")

            assertEquals("EC", created.certificate.publicKey.algorithm)
            assertEquals("SHA256withECDSA", created.certificate.sigAlgName)
            assertEquals("CN=U'Judge peer-1", created.certificate.subjectX500Principal.name)
            assertContentEquals(created.spkiSha256, reloaded.spkiSha256)
            assertEquals(created.verificationCode, reloaded.verificationCode)
            listOf("peer.p12", "peer.password").forEach { name ->
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve(name))))
            }
        } finally {
            directory.parent.toFile().deleteRecursively()
        }
    }
}
