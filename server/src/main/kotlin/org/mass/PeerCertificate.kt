package org.mass

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.security.auth.x500.X500Principal

/**
 * The TLS identity of this peer (ADR-006): one self-signed ECDSA P-256 certificate, created on first start and reused, so
 * paired clients can keep pinning its public key.
 */
class PeerCertificate private constructor(
    val keyStore: KeyStore,
    val password: String,
) {
    val certificate: X509Certificate = keyStore.getCertificate(ALIAS) as X509Certificate

    /** SHA-256 of the SubjectPublicKeyInfo, the value clients pin. */
    val spkiSha256: ByteArray = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)

    /** Six digits the operator compares with the judge's screen before approving a device. */
    val verificationCode: String = verificationCode(spkiSha256)

    companion object {
        const val ALIAS = "u-judge-peer"

        /** First three bytes of the SPKI hash as an unsigned big-endian integer modulo 1 000 000, zero-padded. */
        fun verificationCode(spkiSha256: ByteArray): String {
            val value = ((spkiSha256[0].toInt() and 0xff) shl 16) or
                ((spkiSha256[1].toInt() and 0xff) shl 8) or
                (spkiSha256[2].toInt() and 0xff)
            return (value % 1_000_000).toString().padStart(6, '0')
        }

        fun loadOrCreate(directory: Path, peerId: String): PeerCertificate {
            val keyStoreFile = directory.resolve("peer.p12")
            val passwordFile = directory.resolve("peer.password")
            if (Files.exists(keyStoreFile) && Files.exists(passwordFile)) {
                val password = Files.readString(passwordFile).trim()
                val keyStore = KeyStore.getInstance("PKCS12").apply {
                    Files.newInputStream(keyStoreFile).use { load(it, password.toCharArray()) }
                }
                return PeerCertificate(keyStore, password)
            }

            Files.createDirectories(directory)
            val password = ByteArray(32).also(SecureRandom()::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
            val keyStore = buildKeyStore {
                certificate(ALIAS) {
                    hash = HashAlgorithm.SHA256
                    sign = SignatureAlgorithm.ECDSA
                    keySizeInBits = 256
                    this.password = password
                    daysValid = 3_650
                    subject = X500Principal("CN=U'Judge $peerId")
                    domains = listOf("localhost")
                    ipAddresses = listOf(InetAddress.getByName("127.0.0.1"))
                }
            }
            writeOwnerOnly(passwordFile) { Files.writeString(it, password) }
            writeOwnerOnly(keyStoreFile) { keyStore.saveToFile(it.toFile(), password) }
            return PeerCertificate(keyStore, password)
        }

        private fun writeOwnerOnly(file: Path, write: (Path) -> Unit) {
            val posix = runCatching { Files.getFileStore(file.parent).supportsFileAttributeView("posix") }.getOrDefault(false)
            if (posix) {
                Files.deleteIfExists(file)
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            }
            write(file)
        }
    }
}
