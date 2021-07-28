import java.io.File
import java.io.InputStream

class FileContentProvider(private val file: File) : ContentProvider {

    override val name: String = file.name

    override val size: Long = file.length()

    override val bytes: InputStream

    init {
        bytes = file.inputStream()
    }

    override fun close() {
        bytes.close()
    }
}