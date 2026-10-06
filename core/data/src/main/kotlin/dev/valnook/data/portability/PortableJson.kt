package dev.valnook.data.portability

import android.content.ContentValues
import android.database.Cursor
import android.util.JsonReader
import android.util.JsonToken
import dev.valnook.domain.portability.PortabilityErrorCode
import dev.valnook.domain.portability.PortabilityException
import org.json.JSONObject
import java.io.StringReader
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal object PortableJson {
    data class JsonNumber(val raw: String)
    fun row(cursor: Cursor, table: PortableTable): String {
        val json = JSONObject()
        table.columns.forEach { column ->
            val index = cursor.getColumnIndexOrThrow(column.name)
            if (cursor.isNull(index)) {
                json.put(column.name, JSONObject.NULL)
            } else when (column.kind) {
                PortableKind.LONG -> json.put(column.name, cursor.getLong(index).toString())
                PortableKind.TEXT -> json.put(column.name, cursor.getString(index))
                PortableKind.BLOB -> json.put(column.name, android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP))
                PortableKind.BOOLEAN -> json.put(column.name, cursor.getInt(index) != 0)
                PortableKind.LOCAL_DATE -> json.put(column.name, LocalDate.ofEpochDay(cursor.getLong(index)).toString())
            }
        }
        return json.toString()
    }

    fun parseRow(line: String, table: PortableTable, maxDepth: Int): ContentValues {
        val value = parse(line, maxDepth)
        val objectValue = value as? Map<*, *> ?: fail()
        val expected = table.columns.mapTo(linkedSetOf()) { it.name }
        if (objectValue.keys != expected) fail()
        return ContentValues(table.columns.size).apply {
            table.columns.forEach { column ->
                val item = objectValue[column.name]
                if (item == null) {
                    if (!column.nullable) fail()
                    putNull(column.databaseName)
                } else when (column.kind) {
                    PortableKind.LONG -> {
                        val raw = item as? String ?: fail()
                        if (!DECIMAL_LONG.matches(raw)) fail()
                        put(column.databaseName, raw.toLongOrNull() ?: fail())
                    }
                    PortableKind.TEXT -> put(column.databaseName, item as? String ?: fail())
                    PortableKind.BLOB -> {
                        val raw = item as? String ?: fail()
                        if (raw.length > 175000) fail()
                        val bytes = try { android.util.Base64.decode(raw, android.util.Base64.NO_WRAP) }
                            catch (_: IllegalArgumentException) { fail() }
                        if (bytes.size > dev.valnook.domain.model.AccountSymbols.MAX_IMAGE_BYTES ||
                            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) != raw) fail()
                        put(column.databaseName, bytes)
                    }
                    PortableKind.BOOLEAN -> put(column.databaseName, if (item as? Boolean ?: fail()) 1 else 0)
                    PortableKind.LOCAL_DATE -> {
                        val raw = item as? String ?: fail()
                        val date = try { LocalDate.parse(raw) } catch (_: DateTimeParseException) { fail() }
                        if (date.toString() != raw) fail()
                        put(column.databaseName, date.toEpochDay())
                    }
                }
            }
        }
    }

    fun parse(text: String, maxDepth: Int): Any? = try {
        JsonReader(StringReader(text)).use { reader ->
            val value = read(reader, 0, maxDepth)
            if (reader.peek() != JsonToken.END_DOCUMENT) fail()
            value
        }
    } catch (error: PortabilityException) {
        throw error
    } catch (error: Exception) {
        throw PortabilityException(PortabilityErrorCode.INVALID_DATA, error)
    }

    private fun read(reader: JsonReader, depth: Int, maxDepth: Int): Any? {
        if (depth > maxDepth) throw PortabilityException(PortabilityErrorCode.LIMIT_EXCEEDED)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> linkedMapOf<String, Any?>().also { result ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name in result) fail()
                    result[name] = read(reader, depth + 1, maxDepth)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> mutableListOf<Any?>().also { result ->
                reader.beginArray()
                while (reader.hasNext()) result += read(reader, depth + 1, maxDepth)
                reader.endArray()
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); null }
            JsonToken.NUMBER -> JsonNumber(reader.nextString())
            else -> fail()
        }
    }

    private fun fail(): Nothing = throw PortabilityException(PortabilityErrorCode.INVALID_DATA)
    private val DECIMAL_LONG = Regex("-?(0|[1-9][0-9]*)")
}
