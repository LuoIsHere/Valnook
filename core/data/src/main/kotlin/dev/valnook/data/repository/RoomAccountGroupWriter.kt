package dev.valnook.data.repository

import androidx.room.withTransaction
import dev.valnook.data.database.AccountGroupEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.database.insertDefaultAccountGroup
import dev.valnook.data.database.normalizeAccountOrder
import dev.valnook.domain.model.AccountGroupRules
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.repository.AccountGroupWriter
import java.time.Clock

class RoomAccountGroupWriter(private val db: ValnookDatabase, private val clock: Clock) : AccountGroupWriter {
    private val groups get() = db.accountGroups()

    override suspend fun create(name: String): Long = write {
        val now = clock.millis()
        val order = (groups.groups().maxOfOrNull { it.display_order } ?: -1) + 1
        groups.insert(AccountGroupEntity(name = AccountGroupRules.name(name), display_order = order,
            created_at_ms = now, updated_at_ms = now))
    }

    override suspend fun rename(groupId: Long, name: String) = write {
        requireCustom(groupId)
        groups.rename(groupId, AccountGroupRules.name(name), clock.millis())
        Unit
    }

    override suspend fun delete(groupId: Long) = write {
        requireCustom(groupId)
        append(AccountGroupEntity.DEFAULT_ID, groups.accountIds(groupId))
        groups.delete(groupId)
        groups.groups().forEachIndexed { index, group -> groups.setDisplayOrder(group.id, index.toLong()) }
        db.normalizeAccountOrder()
    }

    override suspend fun reorderGroups(orderedIds: List<Long>) = write {
        requireSameMembers(groups.groups().map { it.id }, orderedIds)
        orderedIds.forEachIndexed { index, id -> groups.setDisplayOrder(id, index.toLong()) }
        db.normalizeAccountOrder()
    }

    override suspend fun moveAccounts(groupId: Long, accountIds: List<Long>) = write {
        requireGroup(groupId)
        if (accountIds.toSet().size != accountIds.size) throw DomainException(ErrorCode.OPERATION_CONFLICT)
        accountIds.forEach { db.accounts().account(it) ?: throw DomainException(ErrorCode.STALE_RECORD) }
        append(groupId, accountIds)
        db.normalizeAccountOrder()
    }

    override suspend fun reorderAccounts(groupId: Long, orderedIds: List<Long>) = write {
        requireGroup(groupId)
        requireSameMembers(groups.accountIds(groupId), orderedIds)
        orderedIds.forEachIndexed { index, id -> groups.placeAccount(id, groupId, index.toLong()) }
        db.normalizeAccountOrder()
    }

    override suspend fun setCollapsed(groupId: Long, collapsed: Boolean) = write {
        requireGroup(groupId)
        groups.setCollapsed(groupId, collapsed)
        Unit
    }

    private suspend fun <T> write(block: suspend () -> T): T = db.withTransaction {
        // Databases created without the seed callback still need the default group to exist.
        insertDefaultAccountGroup(db.openHelper.writableDatabase, clock.millis())
        block()
    }

    /** Places [accountIds] after the group's current accounts; normalization later makes the order global. */
    private suspend fun append(groupId: Long, accountIds: List<Long>) {
        val members = groups.accountIds(groupId).filterNot { it in accountIds }
        (members + accountIds).forEachIndexed { index, id -> groups.placeAccount(id, groupId, index.toLong()) }
    }

    private suspend fun requireGroup(groupId: Long) {
        groups.group(groupId) ?: throw DomainException(ErrorCode.STALE_RECORD)
    }

    private suspend fun requireCustom(groupId: Long) {
        if (groupId == AccountGroupEntity.DEFAULT_ID) throw DomainException(ErrorCode.OPERATION_CONFLICT)
        requireGroup(groupId)
    }

    /** A list from an outdated screen no longer matches the stored members. */
    private fun requireSameMembers(current: List<Long>, ordered: List<Long>) {
        if (ordered.size != current.size || ordered.toSet() != current.toSet()) throw DomainException(ErrorCode.STALE_RECORD)
    }
}
