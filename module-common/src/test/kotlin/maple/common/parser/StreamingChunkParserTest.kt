package maple.common.parser

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

class StreamingChunkParserTest {

    private val objectMapper = ObjectMapper()
    private val parser = StreamingChunkParser(objectMapper, skipMalformed = true)

    private fun gzipped(content: String): ByteArray =
        ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(content.toByteArray()) }
            baos.toByteArray()
        }

    @Test
    fun `parses valid JSONL records`(): Unit = runBlocking {
        val jsonl = """{"ign":"f***l","ocid":"abc123"}
{"ign":"a***b","ocid":"def456"}
"""
        val records = parser.parse(ByteArrayInputStream(gzipped(jsonl))).toList()
        assertEquals(2, records.size)
        assertEquals("f***l", records[0]["ign"])
        assertEquals("abc123", records[0]["ocid"])
        assertEquals("a***b", records[1]["ign"])
    }

    @Test
    fun `skips malformed records and continues`(): Unit = runBlocking {
        val jsonl = """{"ign":"valid","ocid":"abc"}
{this is not json}
{"ign":"also_valid","ocid":"def"}
"""
        val records = parser.parse(ByteArrayInputStream(gzipped(jsonl))).toList()
        assertEquals(2, records.size)
        assertEquals("valid", records[0]["ign"])
        assertEquals("also_valid", records[1]["ign"])
    }

    @Test
    fun `throws on malformed when skipMalformed is false`(): Unit = runBlocking {
        val jsonl = """{"ign":"valid","ocid":"abc"}
{this is not json}
"""
        val strictParser = StreamingChunkParser(objectMapper, skipMalformed = false)
        assertThrows<Exception> {
            strictParser.parse(ByteArrayInputStream(gzipped(jsonl))).toList()
        }
    }

    @Test
    fun `returns empty flow for empty stream`(): Unit = runBlocking {
        val records = parser.parse(ByteArrayInputStream(gzipped(""))).toList()
        assertEquals(0, records.size)
    }

    @Test
    fun `throws on corrupt gzip header`(): Unit = runBlocking {
        val corrupt = ByteArrayInputStream(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertThrows<ZipException> {
            parser.parse(corrupt).toList()
        }
    }

    @Test
    fun `preserves nested object and array structure`(): Unit = runBlocking {
        val jsonl = """{"meta":{"x":1,"y":[10,20]},"key":"a"}
"""
        val records = parser.parse(ByteArrayInputStream(gzipped(jsonl))).toList()
        assertEquals(1, records.size)
        @Suppress("UNCHECKED_CAST")
        val meta = records[0]["meta"] as Map<String, Any>
        assertEquals(1, meta["x"])
        @Suppress("UNCHECKED_CAST")
        val arr = meta["y"] as List<Any>
        assertEquals(listOf(10, 20), arr)
    }

    @Test
    fun `parseToList helper returns same as Flow toList`(): Unit = runBlocking {
        val jsonl = """{"k":"v1"}
{"k":"v2"}
{"k":"v3"}
"""
        val list = parser.parseToList(ByteArrayInputStream(gzipped(jsonl)))
        assertEquals(3, list.size)
        assertEquals(listOf("v1", "v2", "v3"), list.map { it["k"] })
    }

    @Test
    fun `closes resources on early flow cancellation`(): Unit = runBlocking {
        val jsonl = (1..1000).joinToString("\n") { """{"i":$it}""" }
        val gz = gzipped(jsonl)
        val emitted = mutableListOf<Map<String, Any>>()
        try {
            parser.parse(ByteArrayInputStream(gz)).collect { record ->
                emitted.add(record)
                if (emitted.size == 3) throw kotlinx.coroutines.CancellationException("stop")
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertEquals(3, emitted.size)
        assertThrows<Exception> {
            parser.parse(ByteArrayInputStream(gz)).toList()
        }
    }
}
