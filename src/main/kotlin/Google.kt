import HttpUtils.Companion.MIME_TYPE_JSON
import HttpUtils.Companion.MIME_TYPE_OCTET
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.EMPTY_REQUEST
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.*

abstract class Google : Runnable {

    internal abstract val scope: String

    internal val httpClient = OkHttpClient.Builder().build()

    override fun run() {
    }

    @Command(name = "auth")
    fun authorize(
        @Option(names = ["--client-id"])
        clientId: String,
        @Option(names = ["--client-secret"])
        clientSecret: String,
        @Option(names = ["--redirect-uri"])
        redirectUri: String,
        @Option(names = ["--device-code"])
        useDeviceCode: Boolean
    ): Token {
        val token = if (useDeviceCode) {
            authorizeDeviceCode(clientId, clientSecret)
        } else {
            authorizeCode(clientId, clientSecret, redirectUri)
        }
        println(token)
        return Gson().fromJson(token, Token::class.java)
    }

    private fun authorizeDeviceCode(clientId: String, clientSecret: String): String {
        var requestBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scope)
            .build()
        var request = Request.Builder()
            .url("https://oauth2.googleapis.com/device/code")
            .post(requestBody)
            .build()
        val device = httpClient.newCall(request).execute().use {
            require(it.code == 200)
            it.body!!.string()
        }.run {
            Gson().fromJson(this, DeviceCode::class.java)
        }
        println(
            "To sign in, use a web browser to open the page " +
            "${device.verificationUrl} and enter the code " +
            "${device.userCode} to authenticate."
        )
        requestBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("device_code", device.deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val expiryTime = System.currentTimeMillis() + device.expiresIn * 1000
        while (System.currentTimeMillis() < expiryTime) {
            request = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(requestBody)
                .build()
            httpClient.newCall(request).execute().use {
                if (it.code == 200) {
                    return it.body!!.string()
                }
            }
            Thread.sleep(device.interval * 1000L)
        }
        TODO()
    }

    private fun authorizeCode(
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): String {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("accounts.google.com")
            .addPathSegments("o/oauth2/auth")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", scope)
            .addQueryParameter("state", "${Date().time}")
            .toString()
        print(
            "Use a web browser to open the page " +
            "$url and sign in to authenticate.\n" +
            "Input authorization code:"
        )
        return readLine()!!.let {
            it.find("code=([^&#]+)") ?: it
        }.run {
            redeemCode(clientId, clientSecret, redirectUri, this)
        }
    }

    private fun redeemCode(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        code: String
    ): String {
        val requestBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", redirectUri)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .build()
        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(requestBody)
            .build()
        return httpClient.newCall(request).execute().use {
            require(it.code == 200)
            it.body!!.string()
        }
    }

    internal fun <T> resumableUpload(
        file: T,
        url: String,
        token: String,
        provider: ContentProvider
    ) {
        val uploadUrl: String
        val mediaType = MIME_TYPE_JSON.toMediaType()
        val requestBody = Gson().toJson(file).toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Length", "${requestBody.contentLength()}")
            .header("X-Upload-Content-Length", "${provider.size}")
            .header("X-Upload-Content-Type", MIME_TYPE_OCTET)
            .post(requestBody)
            .build()
        httpClient.newCall(request).execute().use {
            require(it.code == 200)
            uploadUrl = it.header("Location")!!
        }
        println(uploadUrl)
        HttpUtils.chunkedUpload(
            url = uploadUrl,
            method = "PUT",
            contentLength = provider.size,
            inputStream = provider.bytes
        )
    }

    internal fun resumeUpload(
        url: String,
        provider: ContentProvider
    ) {
        val offset: Long
        val request = Request.Builder()
            .url(url)
            .header("Content-Length", "0")
            .header("Content-Range", "bytes */${provider.size}")
            .put(EMPTY_REQUEST)
            .build()
        httpClient.newCall(request).execute().use {
            require(it.code == 308)
            it.header("range")
        }!!.run {
            find("bytes=0-(\\d+)")
        }!!.let {
            offset = it.toLong() + 1
        }
        HttpUtils.chunkedUpload(
            url = url,
            method = "PUT",
            contentLength = provider.size,
            offset = offset,
            inputStream = provider.bytes
        )
    }

    class DeviceCode(
        @SerializedName("device_code")
        val deviceCode: String,
        @SerializedName("user_code")
        val userCode: String,
        @SerializedName("expires_in")
        val expiresIn: Int,
        @SerializedName("interval")
        val interval: Int,
        @SerializedName("verification_url")
        val verificationUrl: String
    )

    class Token(
        @SerializedName("access_token")
        val accessToken: String,
        @SerializedName("expires_in")
        val expiresIn: Int,
        @SerializedName("refresh_token")
        val refreshToken: String,
        @SerializedName("scope")
        val scope: String,
        @SerializedName("token_type")
        val tokenType: String
    )
}