import HttpUtils.Companion.MIME_TYPE_JSON
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import picocli.CommandLine.*
import java.io.File
import java.util.*
import kotlin.experimental.xor
import kotlin.math.min

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
    fun quickXorHash(
        @Parameters(index = "0")
        src: String
    ) = ContentProvider.create(src).use {
        val bitsInLastCell = 32
        val shift = 11
        val widthInBits = 160
        val data = LongArray((widthInBits - 1) / 64 + 1)
        var shiftSoFar = 0
        var lengthSoFar = 0L
        val array = ByteArray(4096)
        while (lengthSoFar < it.size) {
            val remain = it.size - lengthSoFar
            val length = if (remain < array.size) remain.toInt() else array.size
            var arrayIndex = 0
            while (arrayIndex < length) {
                val bytesRead = it.bytes.read(array, arrayIndex, length - arrayIndex)
                arrayIndex += bytesRead
            }
            var vectorArrayIndex = shiftSoFar / 64
            var vectorOffset = shiftSoFar % 64
            val iterations = min(length, widthInBits)
            for (i in 0 until iterations) {
                val isLastCell = vectorArrayIndex == (data.size - 1)
                val bitsInVectorCell = if (isLastCell) bitsInLastCell else 64
                if (vectorOffset <= bitsInVectorCell - 8) {
                    for (j in i until length step widthInBits) {
                        data[vectorArrayIndex] = data[vectorArrayIndex] xor (array[j].toULong() shl vectorOffset)
                    }
                } else {
                    val index1 = vectorArrayIndex
                    val index2 = if (isLastCell) 0 else vectorArrayIndex + 1
                    val low = bitsInVectorCell - vectorOffset
                    var xoredByte: Byte = 0
                    for (j in i until length step widthInBits) {
                        xoredByte = xoredByte xor array[j]
                    }
                    data[index1] = data[index1] xor (xoredByte.toULong() shl vectorOffset)
                    data[index2] = data[index2] xor (xoredByte.toULong() shr low)
                }
                vectorOffset += shift
                while (vectorOffset >= bitsInVectorCell) {
                    vectorArrayIndex = if (isLastCell) 0 else vectorArrayIndex + 1
                    vectorOffset -= bitsInVectorCell
                }
            }
            shiftSoFar = (shiftSoFar + shift * (length % widthInBits)) % widthInBits
            lengthSoFar += length
        }
        val rgb = ByteArray((widthInBits - 1) / 8 + 1)
        for (i in 0 until data.size - 1) {
            data[i].toByteArray().copyInto(rgb, i * 8, 0, 8)
        }
        data.last().toByteArray().copyInto(rgb, data.lastIndex * 8, 0, rgb.size - data.lastIndex * 8)
        val lengthBytes = lengthSoFar.toByteArray()
        for (i in lengthBytes.indices) {
            val position = widthInBits / 8 - lengthBytes.size + i
            rgb[position] = rgb[position] xor lengthBytes[i]
        }
        val hash = Base64.getEncoder().encodeToString(rgb)
        println("$hash\t${it.name}")
    }

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