package dev.valnook.data.repository

import androidx.room.withTransaction
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.database.insertDefaultAccountGroup
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.repository.*
import java.time.Clock
import java.util.UUID

/** Issues the same commands the web admin sends; only collapsing a group is written directly. */
class RoomAccountGroupWriter(private val db: ValnookDatabase, private val clock: Clock,
    private val commands: FinancialCommands = RoomFinancialCommands(db, clock)) : AccountGroupWriter {

    override suspend fun create(name: String): Long = run { SaveAccountGroup(it, null, name) }

    override suspend fun rename(groupId: Long, name: String) { run { SaveAccountGroup(it, groupId, name) } }

    override suspend fun delete(groupId: Long) { run { DeleteAccountGroup(it, groupId) } }

    override suspend fun reorderGroups(orderedIds: List<Long>) { run { ReorderAccountGroups(it, orderedIds) } }

    override suspend fun moveAccounts(groupId: Long, accountIds: List<Long>) {
        run { MoveAccountsToGroup(it, groupId, accountIds) }
    }

    override suspend fun reorderAccounts(groupId: Long, orderedIds: List<Long>) {
        run { ReorderGroupAccounts(it, groupId, orderedIds) }
    }

    override suspend fun setCollapsed(groupId: Long, collapsed: Boolean) = db.withTransaction {
        insertDefaultAccountGroup(db.openHelper.writableDatabase, clock.millis())
        db.accountGroups().group(groupId) ?: throw DomainException(ErrorCode.STALE_RECORD)
        db.accountGroups().setCollapsed(groupId, collapsed)
        Unit
    }

    private suspend fun run(command: (String) -> AccountGroupCommand): Long =
        commands.execute(command(UUID.randomUUID().toString())).id
}
