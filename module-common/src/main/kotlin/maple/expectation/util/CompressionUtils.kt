package maple.expectation.util

object CompressionUtils {
    @JvmStatic
    fun ratioString(uncompressed: Long, compressed: Long): String =
        if (compressed > 0) "%.2f".format(uncompressed.toDouble() / compressed.toDouble()) else "N/A"
}
