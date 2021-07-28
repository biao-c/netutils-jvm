import okhttp3.internal.closeQuietly
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class HttpUtils {

    companion object {
        const val MIME_TYPE_TEXT = "text/plain;charset=UTF-8"
        const val MIME_TYPE_FORM = "application/x-www-form-urlencoded"
        const val MIME_TYPE_JSON = "application/json;charset=UTF-8"
        const val MIME_TYPE_OCTET = "application/octet-stream"
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
        const val DEFAULT_CHUNK_SIZE = 50 * 1024 * 1024

        fun upload(
            url: String,
            method: String = "POST",
            headers: Map<String, String> = emptyMap(),
            contentLength: Long,
            contentType: String = MIME_TYPE_OCTET,
            inputStream: InputStream
        ) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.doInput = true
                conn.doOutput = true
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Accept-Encoding", "gzip")
                conn.setRequestProperty("Content-Length", "$contentLength")
                conn.setRequestProperty("Content-Type", contentType)
                for (header in headers.entries) {
                    conn.setRequestProperty(header.key, header.value)
                }
                conn.setFixedLengthStreamingMode(contentLength)
                var output: OutputStream? = null
                try {
                    output = conn.outputStream
                    while (true) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                } finally {
                    output?.closeQuietly()
                }
                var input: InputStream? = null
                try {
                    val encoding = conn.getHeaderField("Content-Encoding")
                    input = if (encoding.equals("gzip", true)) {
                        GZIPInputStream(conn.inputStream)
                    } else conn.inputStream
                    requireNotNull(input)
                    val response = input.bufferedReader().readText()
                    println(response)
                } finally {
                    input?.closeQuietly()
                }
            } finally {
                conn?.disconnect()
            }
        }

        fun chunkedUpload(
            url: String,
            method: String = "POST",
            contentLength: Long,
            contentType: String = MIME_TYPE_OCTET,
            offset: Long = 0L,
            chunkSize: Int = DEFAULT_CHUNK_SIZE,
            inputStream: InputStream
        ): String {
            var response: String
            var rangeStart = 0L
            val buffer = Buffer(DEFAULT_BUFFER_SIZE)
            while (true) {
                if (rangeStart < offset) {
                    val remain = offset - rangeStart
                    val length = if (remain > buffer.size)
                        buffer.size else remain.toInt()
                    val bytesRead = inputStream.read(buffer.bytes, 0, length)
                    rangeStart += bytesRead
                    continue
                }
                var rangeEnd = rangeStart + chunkSize - 1
                if (rangeEnd >= contentLength) {
                    rangeEnd = contentLength - 1
                }
                val rangeLength = (rangeEnd - rangeStart + 1).toInt()
                val range = "$rangeStart-$rangeEnd/$contentLength"
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = method
                    conn.doInput = true
                    conn.doOutput = true
                    conn.setRequestProperty("Accept", "*/*")
                    conn.setRequestProperty("Accept-Encoding", "gzip")
                    conn.setRequestProperty("Content-Length", "$rangeLength")
                    conn.setRequestProperty("Content-Range", "bytes $range")
                    conn.setRequestProperty("Content-Type", contentType)
                    conn.setFixedLengthStreamingMode(rangeLength)
                    var output: OutputStream? = null
                    try {
                        output = conn.outputStream
                        var remain = rangeLength
                        while (true) {
                            val bytesRead = if (buffer.length > 0) buffer.length
                            else inputStream.read(buffer.bytes, 0, buffer.size)
                            require(bytesRead != -1)
                            val bytesWrite = if (bytesRead < remain)
                                bytesRead else remain
                            output.write(buffer.bytes, buffer.offset, bytesWrite)
                            buffer.length = bytesRead - bytesWrite
                            buffer.offset = if (bytesRead > bytesWrite)
                                bytesWrite else 0
                            remain -= bytesWrite
                            if (remain == 0) break
                        }
                        output.flush()
                    } finally {
                        output?.closeQuietly()
                    }
                    var input: InputStream? = null
                    try {
                        val encoding = conn.getHeaderField("Content-Encoding")
                        input = if (encoding.equals("gzip", true)) {
                            GZIPInputStream(conn.inputStream)
                        } else conn.inputStream
                        requireNotNull(input)
                        response = input.bufferedReader().readText()
                        println(response)
                    } finally {
                        input?.closeQuietly()
                    }
                } finally {
                    conn?.disconnect()
                }
                val percent = (rangeEnd + 1) * 100 / contentLength
                println("$percent% uploaded: $range")
                rangeStart += rangeLength
                if (rangeStart >= contentLength) break
            }
            return response
        }
    }

    class Buffer(val size: Int) {
        var offset: Int = 0
        var length: Int = 0
        val bytes = ByteArray(size)
    }
}