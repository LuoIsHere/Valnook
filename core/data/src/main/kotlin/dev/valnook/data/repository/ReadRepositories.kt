package dev.valnook.data.repository

import dev.valnook.data.database.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules
import kotlinx.coroutines.flow.map
import java.text.Normalizer
import java.util.Locale

internal fun valid_name(name: String): String {
    val result = name.trim()
    if (result.isEmpty() || result.length > 200) throw DomainException(ErrorCode.NAME)
    return result
}
class RoomAccounts(private val db: ValnookDatabase) : AccountRepository {
    private val dao = db.accounts()
    override fun observe_accounts() = dao.accounts().map { rows -> rows.map { SavingsAccount(it.id,it.name,it.note,it.revision) } }
}
