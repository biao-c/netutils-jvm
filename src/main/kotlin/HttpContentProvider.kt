import org.apache.commons.io.FilenameUtils
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class HttpContentProvider(private val url: String) : ContentProvider {

    override val name: String
    override val size: Long

    override val bytes: InputStream

    private val connection: HttpURLConnection

    init {
        var filename = FilenameUtils.getName(url)
            .substringBefore("?").substringBefore("#")
        connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "gzip")
        }.connect()
        size = connection.contentLengthLong
        connection.getHeaderField("content-disposition")?.run {
            find("filename=\"([^\"]+)\"")
        }?.let { filename = it }
        name = filename.ifBlank { "Untitled" }
        bytes = connection.inputStream
    }

    override fun close() {
        bytes.close()
        connection.disconnect()
    }
}