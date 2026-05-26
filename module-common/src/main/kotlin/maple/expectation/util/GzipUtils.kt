@file:JvmName("GzipUtils")

package maple.expectation.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * GZIP compression utilities.
 */
object GzipUtils {

    /**
     * 문자열을 GZIP 압축합니다.
     *
     * @param str 압축할 문자열
     * @return 압축된 바이트 배열
     * @throws IOException 압축 중 I/O 오류 발생 시
     */
    @JvmStatic
    @Throws(IOException::class)
    fun compress(str: String?): ByteArray {
        if (str.isNullOrBlank()) {
            return ByteArray(0)
        }

        return compress(str.toByteArray(StandardCharsets.UTF_8))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun compress(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) {
            return ByteArray(0)
        }

        val out = ByteArrayOutputStream(bytes.size)
        GZIPOutputStream(out).use { gzip ->
            gzip.write(bytes)
        }
        return out.toByteArray()
    }

    /**
     * GZIP 압축된 바이트 배열을 압축 해제합니다.
     *
     * @param compressed 압축된 바이트 배열
     * @return 압축 해제된 문자열
     * @throws IOException 압축 해제 중 I/O 오류 발생 시
     */
    @JvmStatic
    @Throws(IOException::class)
    fun decompress(compressed: ByteArray?): String {
        if (compressed == null || compressed.isEmpty()) {
            return ""
        }

        if (!isGzipped(compressed)) {
            return String(compressed, StandardCharsets.UTF_8)
        }

        val gzip = GZIPInputStream(ByteArrayInputStream(compressed))
        return String(gzip.readAllBytes(), StandardCharsets.UTF_8)
    }

    /**
     * 문자열을 GZIP 압축합니다 (IOException을 IllegalStateException로 래핑).
     *
     * LogicExecutor 패턴과 함께 사용하기 위해 IOException을 unchecked exception으로 변환합니다.
     *
     * @param str 압축할 문자열
     * @return 압축된 바이트 배열
     * @throws IllegalStateException 압축 중 I/O 오류 발생 시 (IOException 래핑)
     */
    @JvmStatic
    fun compressUnchecked(str: String?): ByteArray = try {
        compress(str)
    } catch (e: IOException) {
        throw IllegalStateException("GZIP compression failed", e)
    }

    private fun isGzipped(data: ByteArray): Boolean = data.size >= 2 &&
        data[0] == GZIPInputStream.GZIP_MAGIC.toByte() &&
        data[1] == (GZIPInputStream.GZIP_MAGIC shr 8).toByte()
}
