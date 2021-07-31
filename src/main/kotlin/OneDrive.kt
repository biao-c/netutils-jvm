import HttpUtils.Companion.MIME_TYPE_JSON
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import picocli.CommandLine.*
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.*
import kotlin.experimental.xor

@Command(name = "onedrive")
class OneDrive : Microsoft() {

    override val scope: String
        get() = "offline_access Files.ReadWrite.All"

    @Command(name = "server")
    fun getServerInfo(
        @Option(names = ["--token"])
        token: String
    ): ServerInfo {
        val request = Request.Builder()
            .url("https://graph.microsoft.com/v1.0/me/drive")
            .header("Authorization", "Bearer $token")
            .build()
        return httpClient.newCall(request).execute().use {
            it.header("x-ms-ags-diagnostic")
        }!!.run {
            println(this)
            Gson().fromJson(this, Diagnostic::class.java).serverInfo
        }
    }

    @Command(name = "drive")
    fun getDrive(
        @Option(names = ["--token"])
        token: String,
        @Option(names = ["--path"], description = ["Default: /me/drive"])
        path: String?,
        @Option(names = ["--id"])
        id: String?
    ): Drive {
        val drivePath = if (!id.isNullOrBlank()) "/drives/$id"
        else if (!path.isNullOrBlank()) {
            if (path.startsWith("/")) path else "/$path"
        } else "/me/drive"
        val request = Request.Builder()
            .url("https://graph.microsoft.com/v1.0$drivePath")
            .header("Authorization", "Bearer $token")
            .build()
        return httpClient.newCall(request).execute().use {
            it.body!!.string()
        }.run {
            println(this)
            Gson().fromJson(this, Drive::class.java)
        }
    }

    @Command(name = "item")
    fun getDriveItem(
        @Option(names = ["--token"])
        token: String,
        @Option(names = ["--path"])
        path: String?,
        @Option(names = ["--id"])
        id: String?
    ): DriveItem {
        val itemPath = if (!id.isNullOrBlank()) "/items/$id"
        else if (!path.isNullOrBlank()) {
            "/root:" + if (path.startsWith("/")) path else "/$path"
        } else throw IllegalArgumentException()
        val request = Request.Builder()
            .url("https://graph.microsoft.com/v1.0/me/drive$itemPath")
            .header("Authorization", "Bearer $token")
            .build()
        return httpClient.newCall(request).execute().use {
            it.body!!.string()
        }.run {
            println(this)
            Gson().fromJson(this, DriveItem::class.java)
        }
    }

    @Command(name = "download")
    fun download(
        @Option(names = ["--token"])
        token: String,
        @Option(names = ["--path"])
        path: String?,
        @Option(names = ["--id"])
        id: String?
    ) {
        val item = getDriveItem(token, path, id)
        requireNotNull(item.downloadUrl)
        val request = Request.Builder()
            .url(item.downloadUrl)
            .build()
        httpClient.newCall(request).execute().use {
            require(it.code == 200)
            it.body!!.source().run {
                File(item.name).sink().buffer().use {
                    it.writeAll(this)
                }
            }
        }
    }

    @Command(name = "upload")
    fun upload(
        @Parameters(index = "0")
        src: String,
        @Option(names = ["--token"])
        token: String?,
        @Option(names = ["--dir"])
        destDir: String?,
        @Option(names = ["--name"])
        destName: String?,
        @Option(names = ["--url"])
        uploadUrl: String?
    ) = ContentProvider.create(src).use {
        val dir = destDir.run { this ?: "" }
            .run { if (startsWith("/")) this else "/$this" }
            .run { if (endsWith("/")) this else "$this/" }
        val name = if (destName.isNullOrBlank()) it.name else destName
        if (it.size > 4 * 1024 * 1024) {
            if (uploadUrl.isNullOrBlank()) {
                resumableUpload(token!!, dir, name, it)
            } else {
                resumeUpload(uploadUrl, it)
            }
        } else {
            simpleUpload(token!!, dir, name, it)
        }
    }

    /**
     * This method only supports files up to 4 MB in size.
     */
    private fun simpleUpload(
        token: String,
        dir: String,
        name: String,
        provider: ContentProvider
    ) {
        val url = "https://graph.microsoft.com/v1.0/me/drive/root:$dir$name:/content"
        HttpUtils.upload(
            url = url,
            method = "PUT",
            headers = mapOf("Authorization" to "Bearer $token"),
            contentLength = provider.size,
            inputStream = provider.bytes
        )
    }

    private fun resumableUpload(
        token: String,
        dir: String,
        name: String,
        provider: ContentProvider
    ) {
        val session = createUploadSession(token, dir, name)
        println(session.uploadUrl)
        HttpUtils.chunkedUpload(
            url = session.uploadUrl,
            method = "PUT",
            contentLength = provider.size,
            inputStream = provider.bytes
        )
    }

    private fun createUploadSession(
        token: String,
        dir: String,
        name: String
    ): UploadSession {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("graph.microsoft.com")
            .addPathSegments("v1.0/me/drive")
            .addPathSegments("root:$dir$name:")
            .addPathSegments("createUploadSession")
            .build()
        val driveItem = DriveItem(conflictBehavior = "rename", name = name)
        val mediaType = MIME_TYPE_JSON.toMediaType()
        val requestBody = Gson().toJson(driveItem).let {
            "{\"item\":$it}"
        }.toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Length", "${requestBody.contentLength()}")
            .post(requestBody)
            .build()
        return httpClient.newCall(request).execute().use {
            require(it.code == 200)
            it.body!!.string()
        }.run {
            Gson().fromJson(this, UploadSession::class.java)
        }
    }

    private fun resumeUpload(
        url: String,
        provider: ContentProvider
    ) {
        val offset: Long
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use {
            require(it.code == 200)
            it.body!!.string()
        }.run {
            Gson().fromJson(this, ExpectedRange::class.java)
        }.let {
            offset = it.nextExpectedRanges[0].find("(\\d+)-")!!.toLong()
        }
        HttpUtils.chunkedUpload(
            url = url,
            method = "PUT",
            contentLength = provider.size,
            offset = offset,
            inputStream = provider.bytes
        )
    }

    @Command(name = "hash")
    fun hash(
        @Parameters(index = "0")
        src: String,
        @Option(
            names = ["-a", "--algorithm"],
            defaultValue = "",
            description = ["MD5 SHA-1 SHA-256"]
        )
        algorithm: String,
        @Option(
            names = ["-c", "--concurrent"],
            defaultValue = "4"
        )
        concurrent: Int,
        @Option(
            names = ["-b", "--buffer-size"],
            defaultValue = "262144"
        )
        bufferSize: Int
    ) = runBlocking {
        ContentProvider.create(src).use {
            if (algorithm.isBlank()) {
                quickXorHash(it.bytes, concurrent, bufferSize)
            } else {
                val md = MessageDigest.getInstance(algorithm)
                val dis = DigestInputStream(it.bytes, md)
                val buffer = ByteArray(4096)
                while (true) {
                    if (dis.read(buffer) == -1) break
                }
                val bytes = dis.messageDigest.digest()
                String.format("%032x", BigInteger(1, bytes))
            }.print()
        }
    }

    private suspend fun quickXorHash(
        input: InputStream,
        concurrent: Int,
        bufferSize: Int
    ): String = coroutineScope {
        val slices = (0..concurrent).map { Slice(ByteArray(bufferSize)) }
        val channel: Channel<Slice> = Channel(Channel.RENDEZVOUS)
        val deferreds: List<Deferred<IntArray>> = (1..concurrent).map {
            async(Dispatchers.IO) {
                val array = IntArray(160)
                channel.consumeEach {
                    for (i in 0 until it.length) {
                        val index = ((i + it.offset) % 160).toInt()
                        array[index] = array[index] xor it.bytes[i].toInt()
                    }
                    it.isValid = false
                }
                array
            }
        }
        var slice: Slice
        var length = 0L
        while (true) {
            slice = slices.first { !it.isValid }
            slice.length = input.read(slice.bytes)
            if (slice.length == -1) break
            slice.offset = length
            slice.isValid = true
            length += slice.length
            channel.send(slice)
        }
        channel.close()
        val intArray = IntArray(160)
        for (array in deferreds.awaitAll()) {
            for ((index, int) in array.withIndex()) {
                intArray[index] = intArray[index] xor int
            }
        }
        var unwrapBits = BigInteger("0")
        for ((index, int) in intArray.withIndex()) {
            val bits = BigInteger(byteArrayOf(0, int.toByte()))
            unwrapBits = unwrapBits xor (bits shl index * 11)
        }
        var wrapBits = BigInteger("0")
        for (i in 0..10) {
            wrapBits = wrapBits xor (unwrapBits shr 160 * i)
        }
        val data = ByteArray(20)
        val array = wrapBits.toByteArray()
        for (i in 0 until 20) {
            data[i] = array[array.lastIndex - i]
        }
        val lengthBytes = length.toByteArray()
        for ((index, byte) in lengthBytes.withIndex()) {
            val i = 20 - lengthBytes.size + index
            data[i] = data[i] xor byte
        }
        Base64.getEncoder().encodeToString(data)
    }

    class Slice(
        val bytes: ByteArray,
        @Volatile
        var length: Int = 0,
        @Volatile
        var offset: Long = 0L,
        @Volatile
        var isValid: Boolean = false
    )

    class Diagnostic(
        @SerializedName("ServerInfo")
        val serverInfo: ServerInfo
    )

    class ServerInfo(
        @SerializedName("DataCenter")
        val dataCenter: String,
        @SerializedName("Slice")
        val slice: String,
        @SerializedName("Ring")
        val ring: String,
        @SerializedName("ScaleUnit")
        val scaleUnit: String,
        @SerializedName("RoleInstance")
        val roleInstance: String
    )

    class Drive(
        @SerializedName("id")
        val id: String,
        @SerializedName("name")
        val name: String
    )

    class DriveItem(
        @SerializedName("@microsoft.graph.downloadUrl")
        val downloadUrl: String? = null,
        @SerializedName("@microsoft.graph.conflictBehavior")
        val conflictBehavior: String? = null,
        @SerializedName("name")
        val name: String
    )

    class UploadSession(
        @SerializedName("uploadUrl")
        val uploadUrl: String
    )

    class ExpectedRange(
        @SerializedName("expirationDateTime")
        val expirationDateTime: String,
        @SerializedName("nextExpectedRanges")
        val nextExpectedRanges: List<String>
    )
}