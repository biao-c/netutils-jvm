import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl
import picocli.CommandLine.*

@Command(name = "googledrive")
class GoogleDrive : Google() {

    override val scope: String
        get() = "https://www.googleapis.com/auth/drive.file"

    /**
     * Maximum file size: 5120GB
     */
    @Command(name = "upload")
    fun upload(
        @Parameters(index = "0")
        src: String,
        @Option(names = ["--token"])
        token: String?,
        @Option(names = ["--name"])
        destName: String?,
        @Option(names = ["--url"])
        uploadUrl: String?
    ) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("www.googleapis.com")
            .addPathSegments("upload/drive/v3/files")
            .addQueryParameter("uploadType", "resumable")
            .toString()
        ContentProvider.create(src).use {
            if (uploadUrl.isNullOrBlank()) {
                requireNotNull(token)
                val name = if (destName.isNullOrBlank()) it.name else destName
                val item = DriveItem(name = name)
                resumableUpload(item, url, token, it)
            } else {
                resumeUpload(uploadUrl, it)
            }
        }
    }

    class DriveItem(
        @SerializedName("name")
        val name: String
    )
}