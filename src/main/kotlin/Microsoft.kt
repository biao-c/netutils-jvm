import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.*

abstract class Microsoft : Runnable {

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
        @Option(names = ["--cookie"])
        cookie: String?,
        @Option(names = ["--device-code"])
        useDeviceCode: Boolean
    ): Token {
        val token = if (useDeviceCode) {
            authorizeDeviceCode(clientId)
        } else if (!cookie.isNullOrBlank()) {
            authorizeCookie(clientId, clientSecret, redirectUri, cookie)
        } else {
            authorizeCode(clientId, clientSecret, redirectUri)
        }
        println(token)
        return Gson().fromJson(token, Token::class.java)
    }

    private fun authorizeDeviceCode(clientId: String): String {
        var requestBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scope)
            .build()
        var request = Request.Builder()
            .url("https://login.microsoftonline.com/common/oauth2/v2.0/devicecode")
            .post(requestBody)
            .build()
        val deviceCode = httpClient.newCall(request).execute().use {
            require(it.code == 200)
            it.body!!.string()
        }.run {
            Gson().fromJson(this, DeviceCode::class.java)
        }
        println(deviceCode.message)
        requestBody = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("client_id", clientId)
            .add("device_code", deviceCode.deviceCode)
            .build()
        val expiryTime = System.currentTimeMillis() + deviceCode.expiresIn * 1000
        while (System.currentTimeMillis() < expiryTime) {
            request = Request.Builder()
                .url("https://login.microsoftonline.com/common/oauth2/v2.0/token")
                .post(requestBody)
                .build()
            httpClient.newCall(request).execute().use {
                if (it.code == 200) {
                    return it.body!!.string()
                }
            }
            Thread.sleep(deviceCode.interval * 1000L)
        }
        TODO()
    }

    /**
     * @param cookie "ESTSAUTHPERSISTENT=..."
     */
    private fun authorizeCookie(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        cookie: String
    ): String {
        var url = HttpUrl.Builder()
            .scheme("https")
            .host("login.microsoftonline.com")
            .addPathSegments("common/oauth2/v2.0/authorize")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("scope", scope)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", "${Date().time}")
            .toString()
        var request = Request.Builder().url(url).addHeader("Cookie", cookie).build()
        httpClient.newCall(request).execute().use {
            require(it.code == 200)
            val reader = it.body!!.byteStream().bufferedReader()
            var line: String? = null
            while (true) {
                line = reader.readLine() ?: break
                if (line.startsWith("\$Config")) break
            }
            requireNotNull(line)
            line.substring(8, line.length - 1)
        }.let {
            val config = Gson().fromJson(it, Config::class.java)
            url = "${config.urlLogin}&sessionid=${config.arrSessions[0].id}"
        }
        request = Request.Builder().url(url).addHeader("Cookie", cookie).build()
        return httpClient.newCall(request).execute().use {
            it.request.url.toString().find("code=([^&#]+)")
        }!!.run {
            redeemCode(clientId, clientSecret, redirectUri, this)
        }
    }

    private fun authorizeCode(
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): String {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("login.microsoftonline.com")
            .addPathSegments("common/oauth2/v2.0/authorize")
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
            .url("https://login.microsoftonline.com/common/oauth2/v2.0/token")
            .post(requestBody)
            .build()
        return httpClient.newCall(request).execute().use {
            require(it.code == 200)
            it.body!!.string()
        }
    }

    class DeviceCode(
        @SerializedName("device_code")
        val deviceCode: String,
        @SerializedName("expires_in")
        val expiresIn: Int,
        @SerializedName("interval")
        val interval: Int,
        @SerializedName("message")
        val message: String
    )

    class Config(
        @SerializedName("arrSessions")
        val arrSessions: List<ArrSession>,
        @SerializedName("urlLogin")
        val urlLogin: String
    )

    class ArrSession(
        @SerializedName("id")
        val id: String
    )

    class Token(
        @SerializedName("token_type")
        val tokenType: String,
        @SerializedName("scope")
        val scope: String,
        @SerializedName("expires_in")
        val expiresIn: Int,
        @SerializedName("ext_expires_in")
        val extExpiresIn: Int,
        @SerializedName("access_token")
        val accessToken: String,
        @SerializedName("refresh_token")
        val refreshToken: String
    )
}