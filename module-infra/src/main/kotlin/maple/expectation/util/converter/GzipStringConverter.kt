package maple.expectation.util.converter

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import maple.expectation.error.exception.CompressionException
import maple.expectation.util.GzipUtils

/**
 * JPA AttributeConverter for compressing String values using GZIP.
 *
 * Converts String entities to compressed byte arrays for database storage,
 * and decompresses them back when reading from the database.
 */
@Converter
class GzipStringConverter : AttributeConverter<String, ByteArray> {

    /**
     * Converts a String attribute to its compressed byte array representation
     * for database storage.
     *
     * @param attribute the entity attribute value to be converted
     * @return the GZIP-compressed byte array, or null if the input is null
     * @throws CompressionException if GZIP compression fails
     */
    override fun convertToDatabaseColumn(attribute: String?): ByteArray? {
        if (attribute == null) return null
        return runCatching { GzipUtils.compress(attribute) }
            .getOrElse { e ->
                throw CompressionException("GZIP 압축 오류: ${e.message}", e)
            }
    }

    /**
     * Converts a compressed byte array from the database back to a String.
     *
     * @param dbData the data from the database column
     * @return the decompressed String, or null if the input is null
     * @throws CompressionException if GZIP decompression fails
     */
    override fun convertToEntityAttribute(dbData: ByteArray?): String? {
        if (dbData == null) return null
        return runCatching { GzipUtils.decompress(dbData) }
            .getOrElse { e ->
                throw CompressionException("GZIP 압축 해제 오류: ${e.message}", e)
            }
    }
}
