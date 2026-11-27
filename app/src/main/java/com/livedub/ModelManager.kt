package com.livedub

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads and unpacks the small offline English speech model (~40 MB) once. */
object ModelManager {
    private const val NAME = "vosk-model-small-en-us-0.15"
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/$NAME.zip"

    fun dir(ctx: Context) = File(ctx.filesDir, NAME)
    fun isReady(ctx: Context) = File(dir(ctx), "am/final.mdl").exists()

    suspend fun download(ctx: Context, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val zip = File(ctx.cacheDir, "model.zip")
        val conn = URL(URL_ZIP).openConnection()
        val total = conn.contentLengthLong
        var done = 0L
        conn.getInputStream().use { input ->
            zip.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        val root = ctx.filesDir.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val f = File(ctx.filesDir, e.name)
                if (!f.canonicalPath.startsWith(root)) throw SecurityException("Bad zip entry")
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zis.copyTo(it) }
                }
                e = zis.nextEntry
            }
        }
        zip.delete()
        Unit
    }
}
