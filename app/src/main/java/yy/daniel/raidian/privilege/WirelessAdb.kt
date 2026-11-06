package yy.daniel.raidian.privilege

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/** LibADB 3.1.1 handles AOSP SPAKE2/TLS and ADB framing; keys never leave noBackupFilesDir. */
class WirelessAdb(context: Context) : AbsAdbConnectionManager() {
    private val identity: Pair<PrivateKey, Certificate> = synchronized(identityLock) { loadIdentity(context) }
    init { setApi(Build.VERSION.SDK_INT); setTimeout(15, TimeUnit.SECONDS); setThrowOnUnauthorised(true) }
    override fun getPrivateKey() = identity.first
    override fun getCertificate() = identity.second
    override fun getDeviceName() = "Raidian"

    fun startRunner(command: String) {
        // Keep the PTY alive briefly so the child can enter its new session before shell exit.
        // Success is checked by the authenticated local runner handshake, not by shell EOF.
        openStream("shell:$command sleep 1").use { stream ->
            val input = stream.openInputStream()
            val buffer = ByteArray(1024)
            var total = 0
            while (true) {
                val n = try { input.read(buffer) } catch (e: java.io.IOException) {
                    if (stream.isClosed) break else throw e
                }
                if (n < 0) break
                total += n
                check(total <= 16384) { "启动命令输出异常" }
            }
        }
    }

    companion object {
        private val identityLock = Any()
        private fun loadIdentity(context: Context): Pair<PrivateKey, Certificate> {
            val file = AtomicFile(File(context.noBackupFilesDir, "wireless-adb.p12"))
            val password = CharArray(0) // App-private, backup-excluded file is the security boundary.
            val store = KeyStore.getInstance("PKCS12")
            if (file.baseFile.exists()) {
                file.openRead().use { store.load(it, password) }
            } else {
                store.load(null, password)
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val name = X500Name("CN=Raidian")
                val now = System.currentTimeMillis()
                val cert = JcaX509CertificateConverter().getCertificate(
                    JcaX509v3CertificateBuilder(name, BigInteger(128, SecureRandom()), Date(now - 86400000L),
                        Date(now + 10L * 365 * 86400000L), name, pair.public)
                        .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)))
                store.setKeyEntry("adb", pair.private, password, arrayOf(cert))
                val output = file.startWrite()
                try { store.store(output, password); file.finishWrite(output) }
                catch (e: Exception) { file.failWrite(output); throw e }
            }
            return (store.getKey("adb", password) as PrivateKey) to store.getCertificate("adb")
        }
    }
}
