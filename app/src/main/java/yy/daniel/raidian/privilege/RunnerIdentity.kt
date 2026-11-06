package yy.daniel.raidian.privilege

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.SecureRandom

/** Per-install capability also prevents Android UID reuse from exposing an orphaned server. */
object RunnerIdentity {
    @Synchronized fun token(context: Context): String {
        val file = AtomicFile(File(context.noBackupFilesDir, "runner-token"))
        if (file.baseFile.exists()) {
            return file.openRead().bufferedReader().use { it.readText() }.also { check(it.matches(Regex("[0-9a-f]{64}"))) }
        }
        val value = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val output = file.startWrite()
        try { output.write(value.toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
        return value
    }
}
