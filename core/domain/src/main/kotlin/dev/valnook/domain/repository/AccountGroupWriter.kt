package dev.valnook.domain.repository

/**
 * Account groups and the order of main accounts are presentation metadata; updating them must not create
 * financial movements or invalidate valuations. Every call is saved immediately.
 */
interface AccountGroupWriter {
    /** Appends a new group after the existing ones and returns its id. */
    suspend fun create(name: String): Long
    suspend fun rename(groupId: Long, name: String)
    /** Moves the group's accounts to the end of the default group, then deletes the group. */
    suspend fun delete(groupId: Long)
    /** [orderedIds] must list every group exactly once. */
    suspend fun reorderGroups(orderedIds: List<Long>)
    /** Moves the accounts, wherever they are, to the end of [groupId] in the given order. */
    suspend fun moveAccounts(groupId: Long, accountIds: List<Long>)
    /** [orderedIds] must list every account of [groupId] exactly once. */
    suspend fun reorderAccounts(groupId: Long, orderedIds: List<Long>)
    suspend fun setCollapsed(groupId: Long, collapsed: Boolean)
}

/**
 * Group changes from the app and the web admin share the command pipeline, so both get operation receipts,
 * audit events and a new data generation. Collapsing a group is view state and stays outside it.
 */
sealed interface AccountGroupCommand : FinancialCommand

/** Creates a group when [groupId] is null, otherwise renames it. */
data class SaveAccountGroup(override val operation_id: String, val groupId: Long?, val name: String) : AccountGroupCommand
data class DeleteAccountGroup(override val operation_id: String, val groupId: Long) : AccountGroupCommand
data class ReorderAccountGroups(override val operation_id: String, val orderedIds: List<Long>) : AccountGroupCommand
data class MoveAccountsToGroup(override val operation_id: String, val groupId: Long,
    val accountIds: List<Long>) : AccountGroupCommand
data class ReorderGroupAccounts(override val operation_id: String, val groupId: Long,
    val orderedIds: List<Long>) : AccountGroupCommand
