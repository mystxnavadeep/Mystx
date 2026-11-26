package app.grapheneos.pdfviewer.outline

import android.util.JsonReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import java.io.StringReader

data class OutlineNode(
    val id: Int,
    val title: String,
    val pageNumber: Int,
    val children: List<OutlineNode>
) {
    companion object {
        private const val KEY_TITLE = "t"
        private const val KEY_PAGE_NUMBER = "p"
        private const val KEY_CHILDREN = "c"
        private const val MAX_DEPTH = 64

        data class IdTracker(private var currentId: Int = 0) {
            fun getAndIncrement(): Int {
                currentId++
                return currentId - 1
            }
        }

        suspend fun parse(input: String): List<OutlineNode> = try {
            val idTracker = IdTracker()

            StringReader(input).use { sr ->
                JsonReader(sr).use { reader ->
                    coroutineScope { parseOutlineArray(reader, idTracker, depth = 0) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        private fun CoroutineScope.parseOutlineArray(
            reader: JsonReader,
            idTracker: IdTracker,
            depth: Int = 0
        ): List<OutlineNode> = with(reader) {
            if (depth >= MAX_DEPTH) {
                skipValue()
                return emptyList()
            }
            val topLevelNodes = arrayListOf<OutlineNode>()
            beginArray()
            while (hasNext() && isActive) {
                topLevelNodes.add(parseOutlineObject(this, idTracker, depth))
            }
            endArray()
            topLevelNodes
        }

        private fun CoroutineScope.parseOutlineObject(
            reader: JsonReader,
            id: IdTracker,
            depth: Int
        ): OutlineNode = with(reader) {
            var title = ""
            var pageNumber = -1
            var children = emptyList<OutlineNode>()

            beginObject()
            while (hasNext() && isActive) {
                when (nextName()) {
                    KEY_TITLE -> title = reader.nextString()
                    KEY_PAGE_NUMBER -> pageNumber = reader.nextInt()
                    KEY_CHILDREN -> children = parseOutlineArray(reader, id, depth + 1)
                    else -> reader.skipValue()
                }
            }
            endObject()

            OutlineNode(id.getAndIncrement(), title, pageNumber, children)
        }
    }
}
