import java.nio.ByteBuffer
import java.nio.ByteOrder

fun Byte.toULong(): Long = this.toLong() and 255L

fun Long.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(Long.SIZE_BYTES)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.putLong(this)
    return buffer.array()
}

fun String.find(pattern: String) = pattern.toRegex().find(this)?.run { groupValues[1] }