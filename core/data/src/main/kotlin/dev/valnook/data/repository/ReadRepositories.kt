package dev.valnook.data.repository

import dev.valnook.data.database.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import java.time.Clock
import java.text.Normalizer
import java.util.Locale

internal fun valid_name(name: String): String {
    val result = name.trim()
    if (result.isEmpty() || result.length > 200) throw DomainException(ErrorCode.NAME)
    return result
}
class RoomAccounts(private val db: ValnookDatabase, private val clock: Clock) : AccountRepository {
    private val dao = db.accounts()
    override fun observe_accounts() = dao.accounts().map { rows -> rows.map { SavingsAccount(it.id,it.name,it.note,it.revision) } }
    override suspend fun save_account(id: Long?, name: String, note: String): Long = db.withTransaction {
        val label = valid_name(name)
        if (note.length > 2000) throw DomainException(ErrorCode.FORMAT)
        if (id == null) dao.insert_account(AccountEntity(name=label,note=note,created_at_ms=clock.millis(),updated_at_ms=clock.millis()))
        else { if(dao.edit_account(id,label,note,clock.millis()) != 1) throw DomainException(ErrorCode.NOT_FOUND); id }
    }
}
