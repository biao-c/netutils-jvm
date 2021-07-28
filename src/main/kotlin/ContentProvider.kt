import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Closeable
import java.io.File
import java.io.InputStream

interface ContentProvider : Closeable {

    companion object {
        fun create(uri: String): ContentProvider {
            return if (uri.toHttpUrlOrNull() != null) {
                HttpContentProvider(uri)
            } else {
                val file = File(uri)
                if (file.exists()) {
                    FileContentProvider(file)
                } else throw IllegalArgumentException()
            }
        }
    }

    val name: String

    val size: Long

    val bytes: InputStream
}