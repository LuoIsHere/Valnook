package dev.valnook.data.transaction

import dev.valnook.data.database.AccountGroupEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.database.insertDefaultAccountGroup
import dev.valnook.data.database.normalizeAccountOrder
import dev.valnook.domain.model.AccountGroupRules
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.OperationResult
import dev.valnook.domain.repository.*

/** Runs inside the command transaction. Only presentation order changes; no financial rows are touched. */
internal class AccountGroupCommandHandler(private val db: ValnookDatabase) {
    private val groups get() = db.accountGroups()

    suspend fun execute(command: AccountGroupCommand, now: Long): OperationResult {
        // Databases created without the seed callback still need the default group to exist.
        insertDefaultAccountGroup(db.openHelper.writableDatabase, now)
        val groupId = when (command) {
            is SaveAccountGroup -> command.groupId?.let { rename(it, command.name, now) } ?: create(command.name, now)
            is DeleteAccountGroup -> delete(command.groupId)
            is ReorderAccountGroups -> reorderGroups(command.orderedIds)
            is MoveAccountsToGroup -> moveAccounts(command.groupId, command.accountIds)
            is ReorderGroupAccounts -> reorderAccounts(command.groupId, command.orderedIds)
        }
        return OperationResult(KIND, groupId)
    }

    private suspend fun create(name: String, now: Long): Long {
        val order = (groups.groups().maxOfOrNull { it.display_order } ?: -1) + 1
        return groups.insert(AccountGroupEntity(name = AccountGroupRules.name(name), display_order = order,
            created_at_ms = now, updated_at_ms = now))
    }

    private suspend fun rename(groupId: Long, name: String, now: Long): Long {
        requireCustom(groupId)
        groups.rename(groupId, AccountGroupRules.name(name), now)
        return groupId
    }

    private suspend fun delete(groupId: Long): Long {
        requireCustom(groupId)
        append(AccountGroupEntity.DEFAULT_ID, groups.accountIds(groupId))
        groups.delete(groupId)
        groups.groups().forEachIndexed { index, group -> groups.setDisplayOrder(group.id, index.toLong()) }
        db.normalizeAccountOrder()
        return groupId
    }

    private suspend fun reorderGroups(orderedIds: List<Long>): Long {
        requireSameMembers(groups.groups().map { it.id }, orderedIds)
        orderedIds.forEachIndexed { index, id -> groups.setDisplayOrder(id, index.toLong()) }
        db.normalizeAccountOrder()
        return AccountGroupEntity.DEFAULT_ID
    }

    private suspend fun moveAccounts(groupId: Long, accountIds: List<Long>): Long {
        requireGroup(groupId)
        if (accountIds.toSet().size != accountIds.size) throw DomainException(ErrorCode.OPERATION_CONFLICT)
        accountIds.forEach { db.accounts().account(it) ?: throw DomainException(ErrorCode.STALE_RECORD) }
        append(groupId, accountIds)
        db.normalizeAccountOrder()
        return groupId
    }

    private suspend fun reorderAccounts(groupId: Long, orderedIds: List<Long>): Long {
        requireGroup(groupId)
        requireSameMembers(groups.accountIds(groupId), orderedIds)
        orderedIds.forEachIndexed { index, id -> groups.placeAccount(id, groupId, index.toLong()) }
        db.normalizeAccountOrder()
        return groupId
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

    companion object {
        const val KIND = "ACCOUNT_GROUP"
    }
}
