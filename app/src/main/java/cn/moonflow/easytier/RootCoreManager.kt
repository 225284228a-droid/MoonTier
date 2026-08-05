package cn.moonflow.easytier

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

class RootCoreManager(private val context: Context) {
    private val rootDir = File(context.filesDir, "root")
    val coreDir = File(rootDir, "core")
    val coreFile = File(coreDir, "easytier-core")
    val cliFile = File(coreDir, "easytier-cli")
    private val versionFile = File(coreDir, "version.txt")

    fun ensureDirectories() {
        coreDir.mkdirs()
    }

    fun isReady(): Boolean = coreFile.isFile && cliFile.isFile

    fun installedVersion(): String =
        runCatching { versionFile.readText().trim() }.getOrDefault("")

    fun checkLatest(): String? {
        val connection = URL("https://api.github.com/repos/EasyTier/EasyTier/releases/latest")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 10000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "MoonTier")
        return runCatching {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("tag_name").trim().ifBlank { null }
        }.getOrNull()
    }

    suspend fun installLatest(onProgress: (Int, Int) -> Unit): String =
        withContext(Dispatchers.IO) {
            val tag = checkLatest() ?: throw IllegalStateException("无法获取官方最新版本")
            val zip = File(context.cacheDir, "easytier-core-$tag.zip")
            downloadRelease(tag, zip, onProgress)
            extractRelease(zip)
            versionFile.writeText(tag)
            zip.delete()
            coreFile.setExecutable(true, false)
            cliFile.setExecutable(true, false)
            tag
        }

    private fun downloadRelease(
        tag: String,
        target: File,
        onProgress: (Int, Int) -> Unit
    ) {
        val url = "https://github.com/EasyTier/EasyTier/releases/download/$tag/easytier-linux-aarch64-$tag.zip"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                var written = 0L
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    written += read
                    if (total > 0) {
                        onProgress(
                            (written * 100 / total).toInt().coerceIn(0, 100),
                            (total / 1024).toInt()
                        )
                    }
                }
            }
        }
    }

    private fun extractRelease(zip: File) {
        coreDir.mkdirs()
        ZipFile(zip).use { archive ->
            val entries = archive.entries()
            var coreExtracted = false
            var cliExtracted = false
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                when {
                    name.endsWith("/easytier-core") -> {
                        archive.getInputStream(entry).use { input ->
                            coreFile.outputStream().use { it.write(input.readBytes()) }
                        }
                        coreExtracted = true
                    }
                    name.endsWith("/easytier-cli") -> {
                        archive.getInputStream(entry).use { input ->
                            cliFile.outputStream().use { it.write(input.readBytes()) }
                        }
                        cliExtracted = true
                    }
                }
            }
            if (!coreExtracted || !cliExtracted) {
                throw IllegalStateException("官方压缩包中缺少 easytier-core 或 easytier-cli")
            }
        }
    }
}
