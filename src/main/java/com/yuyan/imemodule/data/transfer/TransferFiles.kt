package com.yuyan.imemodule.data.transfer

import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object TransferFiles {
    const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
    fun exists(file: File) = file.exists() || File(file.path + ".bak").exists()
    fun read(file: File): String = AtomicFile(file).openRead().bufferedReader().use { it.readText() }
    fun sha256(file: File): String = file.inputStream().use(::sha256)
    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun child(root: File, path: String): File {
        require(path.isNotBlank() && !path.contains('\\') && !path.contains(':')) { "Invalid archive path" }
        val child = File(root, path)
        require(child.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Archive path escapes target" }
        return child
    }

    fun extract(input: InputStream, root: File, allow: (String) -> Boolean = { true }): List<File> {
        root.mkdirs()
        val seen = hashSetOf<String>()
        val files = mutableListOf<File>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = child(root, entry.name)
                require(seen.add(output.canonicalPath) && seen.size <= 20000) { "Duplicate or excessive archive entries" }
                require(allow(entry.name)) { "Unexpected archive entry" }
                if (entry.isDirectory) output.mkdirs() else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { target ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val size = zip.read(buffer)
                            if (size < 0) break
                            total += size
                            require(total <= MAX_EXPANDED_BYTES) { "Archive exceeds expanded size limit" }
                            target.write(buffer, 0, size)
                        }
                    }
                    files += output
                }
                zip.closeEntry()
            }
        }
        return files
    }
}
