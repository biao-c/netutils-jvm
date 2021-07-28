import HttpUtils.Companion.MIME_TYPE_TEXT
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import picocli.CommandLine.*
import java.util.*

@Command(name = "cloudlucky")
class CloudLucky {

    private val httpClient = OkHttpClient.Builder().build()

    @Command(name = "upload")
    fun upload(
        @Parameters(index = "0")
        src: String,
        @Option(names = ["--path"], defaultValue = "/")
        path: String,
        @Option(names = ["--cookie"])
        cookie: String
    ) = ContentProvider.create(src).use {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("xin.cloudlucky.cn")
            .addPathSegments("api/v3/file/upload/credential")
            .addQueryParameter("path", path)
            .addQueryParameter("size", "${it.size}")
            .addQueryParameter("name", "${Date().time}-${it.name}")
            .addQueryParameter("type", "onedrive")
            .build()
        var request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .build()
        val credential = httpClient.newCall(request).execute().use {
            require(it.code == 200)
            it.body!!.string()
        }.run {
            println(this)
            Gson().fromJson(this, Credential::class.java)
        }
        val response = HttpUtils.chunkedUpload(
            url = credential.data.policy,
            method = "PUT",
            contentLength = it.size,
            inputStream = it.bytes
        )
        val mediaType = MIME_TYPE_TEXT.toMediaType()
        request = Request.Builder()
            .url(credential.data.token)
            .header("Cookie", cookie)
            .post(response.toRequestBody(mediaType))
            .build()
        httpClient.newCall(request).execute().use {
            require(it.code == 200)
            println(it.body!!.string())
        }
    }

    class Credential(
        @SerializedName("code")
        val code: Int,
        @SerializedName("data")
        val data: Date,
        @SerializedName("msg")
        val msg: String
    ) {
        class Date(
            @SerializedName("token")
            val token: String,
            @SerializedName("policy")
            val policy: String,
            @SerializedName("path")
            val path: String,
            @SerializedName("ak")
            val ak: String
        )
    }
}