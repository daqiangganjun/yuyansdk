package com.yuyan.imemodule.speech

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

object DoubaoProtocol {
    private const val MAX_PAYLOAD = 2 * 1024 * 1024
    data class Response(val payload: JSONObject, val isFinal: Boolean, val errorCode: Int? = null)

    fun request(payload: JSONObject): ByteArray = frame(1, 1, payload.toString().toByteArray(Charsets.UTF_8), true)
    fun audio(pcm: ByteArray, sequence: Int, last: Boolean): ByteArray =
        frame(2, if (last) -sequence else sequence, pcm, false)

    private fun frame(type: Int, sequence: Int, payload: ByteArray, json: Boolean): ByteArray {
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(payload) }
        }.toByteArray()
        return ByteBuffer.allocate(12 + compressed.size)
            .put(0x11.toByte())
            .put(((type shl 4) or if (sequence < 0) 3 else 1).toByte())
            .put((if (json) 0x11 else 0x01).toByte())
            .put(0.toByte()).putInt(sequence).putInt(compressed.size).put(compressed).array()
    }

    fun decode(message: ByteArray): Response {
        require(message.size in 8..MAX_PAYLOAD) { "Invalid speech frame size" }
        require((message[0].toInt() and 0xf0) == 0x10) { "Unsupported speech protocol version" }
        val headerSize = (message[0].toInt() and 15) * 4
        require(headerSize in 4..message.size - 4) { "Invalid speech header" }
        val buffer = ByteBuffer.wrap(message).apply { position(headerSize) }
        val type = (message[1].toInt() and 0xf0) ushr 4
        val flags = message[1].toInt() and 15
        if ((flags and 1) != 0) buffer.int
        if ((flags and 4) != 0) buffer.int
        val code = if (type == 15) buffer.int else null
        require(type == 9 || type == 15) { "Unsupported speech response type" }
        val size = buffer.int
        require(size >= 0 && size == buffer.remaining()) { "Invalid speech payload length" }
        val compressed = ByteArray(size).also { buffer.get(it) }
        val compression = message[2].toInt() and 15
        require(compression in 0..1) { "Unsupported speech compression" }
        val payload = if (compression == 1) {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    require(out.size() + count <= MAX_PAYLOAD) { "Speech payload is too large" }
                    out.write(chunk, 0, count)
                }
                out.toByteArray()
            }
        } else compressed
        val text = String(payload, Charsets.UTF_8)
        val body = if (code != null) JSONObject() else JSONObject(text)
        return Response(body, (flags and 2) != 0, code)
    }
}
