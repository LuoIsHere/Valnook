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
