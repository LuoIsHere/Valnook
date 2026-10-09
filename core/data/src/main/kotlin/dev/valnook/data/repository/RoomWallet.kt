package dev.valnook.data.repository

import androidx.room.withTransaction
import dev.valnook.data.database.*
import dev.valnook.data.image.WalletImages
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.WalletRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock

class RoomWallet(private val db: ValnookDatabase, private val clock: Clock) : WalletRepository {
    override fun observeCards() = db.wallet().observeCards().map { rows -> rows.map {
        WalletCard(it.id,it.name,it.image_key,it.bound_cash_account_id,it.display_order,it.created_at_ms,
            it.updated_at_ms,it.revision,it.binding_lost)
    } }
    override suspend fun image(key: String) = db.wallet().image(key)?.let { WalletImage(it.id,it.data,it.width,it.height,it.tint) }
    override suspend fun save(id: Long?, expectedRevision: Long?, name: String, cashAccountId: Long?,
        imageKey: String?, image: WalletImage?): Long {
        val cleanName = WalletRules.name(name)
        if (image != null && !withContext(Dispatchers.Default) { WalletImages.valid(image) }) throw DomainException(ErrorCode.FORMAT)
        return db.withTransaction {
            if (cashAccountId != null && db.cash().cashAccount(cashAccountId) == null) throw DomainException(ErrorCode.NOT_FOUND)
            val old = id?.let { db.wallet().card(it) ?: throw DomainException(ErrorCode.NOT_FOUND) }
            if (old != null && old.revision != expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
            if (image != null) {
                require(image.key == imageKey)
                db.wallet().insertImage(WalletImageEntity(image.key,image.bytes,image.width,image.height,image.tint))
            }
            if (imageKey != null && db.wallet().image(imageKey) == null) throw DomainException(ErrorCode.NOT_FOUND)
            val now = clock.millis()
            val row = WalletCardEntity(id ?: 0,cleanName,imageKey,cashAccountId,
                old?.display_order ?: ((db.wallet().cards().maxOfOrNull { it.display_order } ?: -1) + 1),
                old?.created_at_ms ?: now,now,(old?.revision ?: 0) + 1,false)
            val savedId = if (old == null) db.wallet().insert(row) else { check(db.wallet().update(row) == 1); old.id }
            db.wallet().collectImages()
            check(db.audit().advanceGeneration(now) == 1)
            savedId
        }
    }
    override suspend fun delete(id: Long, expectedRevision: Long) = db.withTransaction {
        if (db.wallet().delete(id,expectedRevision) != 1) throw DomainException(ErrorCode.STALE_RECORD)
        db.wallet().collectImages()
        check(db.audit().advanceGeneration(clock.millis()) == 1)
    }
    override suspend fun reorder(expected: List<Long>, ordered: List<Long>) = db.withTransaction {
        val current = db.wallet().cards().map { it.id }
        if (current != expected || ordered.size != current.size || ordered.toSet() != current.toSet())
            throw DomainException(ErrorCode.STALE_RECORD)
        ordered.forEachIndexed { index, id -> db.wallet().position(id,index.toLong()) }
        check(db.audit().advanceGeneration(clock.millis()) == 1)
    }
}
