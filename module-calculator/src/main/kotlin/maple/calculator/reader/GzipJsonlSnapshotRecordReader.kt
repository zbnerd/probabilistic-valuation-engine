package maple.calculator.reader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

@Component
class GzipJsonlSnapshotRecordReader {
    fun readLines(inputStream: InputStream): Flow<String> = flow {
        GZIPInputStream(BufferedInputStream(inputStream)).bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) emit(line)
                line = reader.readLine()
            }
        }
    }
}
