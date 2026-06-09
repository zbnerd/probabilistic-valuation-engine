package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import maple.expectation.common.storage.ObjectStorage
import maple.synchronizer.domain.BasicRecord
import maple.synchronizer.domain.GroupedEquipmentResult
import maple.synchronizer.domain.OcidMapping
import maple.synchronizer.metrics.SynchronizerReaderMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultChunkFileReaderTest {

    private val objectStorage: ObjectStorage = mock()
    private val objectMapper = ObjectMapper()
    private val readerMetrics: SynchronizerReaderMetrics = mock()
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    @Test
    fun `readBasicChunk returns parsed records from gzipped JSONL`() = runTest(testDispatcher) {
        val line1 = """{"status":"SUCCESS","endpoint":"character-basic","key":"ocid-1","body":{"character_name":"ign-1","world_name":"Aquila","character_class":"Warrior","character_level":250,"guild_name":"G1"}}"""
        val line2 = """{"status":"SUCCESS","endpoint":"character-basic","key":"ocid-2","body":{"character_name":"ign-2","world_name":"Aquila","character_class":"Mage","character_level":200,"guild_name":null}}"""
        val data = gzip("$line1\n$line2\n".toByteArray())
        whenever(objectStorage.get("k")).thenReturn(data)

        val result = DefaultChunkFileReader(objectStorage, objectMapper, readerMetrics, 100).readBasicChunk("k")

        assertThat(result).hasSize(2)
        assertThat(result[0].userIgn).isEqualTo("ign-1")
        assertThat(result[1].userIgn).isEqualTo("ign-2")
        assertThat(result[1].guildName).isNull()
        verify(objectStorage).get("k")
    }

    @Test
    fun `readResultChunk returns parsed equipment results`() = runTest(testDispatcher) {
        val line1 = """{"ocid":"o1","presetNo":1,"itemName":"Sword","itemLevel":200,"itemPart":"Weapon","itemEquipmentPart":"Weapon","currentStar":0,"targetStar":22,"status":"SUCCESS","totalCost":1.0,"blackCubeCost":0.5,"additionalCubeCost":0.3,"starforceCost":0.2}"""
        val data = gzip("$line1\n".toByteArray())
        whenever(objectStorage.get("k")).thenReturn(data)

        val result = DefaultChunkFileReader(objectStorage, objectMapper, readerMetrics, 100).readResultChunk("k")

        assertThat(result).hasSize(1)
        assertThat(result[0].ocid).isEqualTo("o1")
        assertThat(result[0].presetNo).isEqualTo(1)
        verify(objectStorage).get("k")
    }

    @Test
    fun `readOcidMapping returns parsed mappings`() = runTest(testDispatcher) {
        val line1 = """{"userIgn":"ign-1","ocid":"ocid-1"}"""
        val line2 = """{"userIgn":"ign-2","ocid":"ocid-2"}"""
        val data = gzip("$line1\n$line2\n".toByteArray())
        whenever(objectStorage.get("m")).thenReturn(data)

        val result = DefaultChunkFileReader(objectStorage, objectMapper, readerMetrics, 100).readOcidMapping("m")

        assertThat(result).hasSize(2)
        assertThat(result[0]).isEqualTo(OcidMapping("ign-1", "ocid-1"))
        assertThat(result[1]).isEqualTo(OcidMapping("ign-2", "ocid-2"))
    }
}
