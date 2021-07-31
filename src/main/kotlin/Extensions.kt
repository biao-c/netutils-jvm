import java.nio.ByteBuffer
import java.nio.ByteOrder

fun Long.toByteArray(): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES)
    .order(ByteOrder.LITTLE_ENDIAN)
    .putLong(this)
    .array()

fun String.find(pattern: String) = pattern.toRegex().find(this)?.run { groupValues[1] }

fun String.print() = println(this)