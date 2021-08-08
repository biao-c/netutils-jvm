import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl
import picocli.CommandLine.*
import java.util.*

@Command(name = "youtube")
class YouTube : Google() {

    override val scope: String
        get() = "https://www.googleapis.com/auth/youtube"

    /**
     * Maximum file size: 128GB
     */
    @Command(name = "upload")
    fun upload(
        @Parameters(index = "0")
        src: String,
        @Option(names = ["--token"])
        token: String,
        // The property value has a maximum length of 100 characters
        // and may contain all valid UTF-8 characters except < and >.
        @Option(names = ["--title"])
        title: String? = null,
        @Option(
            names = ["--privacy"],
            description = ["private, public, unlisted"]
        )
        privacy: String? = null
    ) {
        val isPrivacyValid = privacy.equals("private", true)
                             || privacy.equals("public", true)
                             || privacy.equals("unlisted", true)
        val status = Video.Status(
            privacyStatus = if (isPrivacyValid) {
                requireNotNull(privacy)
                privacy.lowercase(Locale.US)
            } else "public"
        )
        val video = Video(snippet = Video.Snippet(title = title), status = status)
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("www.googleapis.com")
            .addPathSegments("upload/youtube/v3/videos")
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("part", "snippet,status,contentDetails")
            .toString()
        ContentProvider.create(src).use {
            resumableUpload(video, url, token, it)
        }
    }

    class Video(
        @SerializedName("snippet")
        val snippet: Snippet,
        @SerializedName("status")
        val status: Status
    ) {
        class Snippet(
            @SerializedName("title")
            val title: String? = null
        )

        class Status(
            @SerializedName("privacyStatus")
            val privacyStatus: String? = null
        )
    }
}