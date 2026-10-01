package dev.valnook.data.transaction

import dev.valnook.data.database.InstrumentDao
import dev.valnook.data.database.TypeEntity
import dev.valnook.data.repository.valid_name
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import java.text.Normalizer
import java.util.Locale

/** Participates in the caller's transaction; never opens or commits its own transaction. */
internal class AssetTypeWriter(private val dao: InstrumentDao) {
    suspend fun save(id: Long?, name: String, now: Long): Long {
        val label = valid_name(name)
        val normalized = Normalizer.normalize(label, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
        val duplicate = dao.type_by_name(normalized)
        if (duplicate != null && duplicate != id) throw DomainException(ErrorCode.DUPLICATE_TYPE)
        if (id == null) return dao.insert_type(TypeEntity(name = label, normalized_name = normalized,
            created_at_ms = now, updated_at_ms = now))
        if (dao.edit_type(id, label, normalized, now) != 1) throw DomainException(ErrorCode.NOT_FOUND)
        return id
    }
}
