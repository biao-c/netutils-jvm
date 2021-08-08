import java.io.File
import java.io.InputStream

class FileContentProvider(file: File) : ContentProvider {

    override val name: String = file.name

    override val size: Long = file.length()

    override val bytes: InputStream = file.inputStream()

    override fun close() = bytes.close()
}