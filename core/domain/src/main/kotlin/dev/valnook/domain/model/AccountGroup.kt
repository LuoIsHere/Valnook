package dev.valnook.domain.model

/** A section of main accounts on the accounts page; presentation metadata only. */
data class AccountGroup(val id: Long, val name: String, val displayOrder: Long, val collapsed: Boolean = false) {
    val isDefault: Boolean get() = id == DEFAULT_ID

    companion object {
        /** Created with the database; holds every account not moved elsewhere and cannot be renamed or deleted. */
        const val DEFAULT_ID = 1L
    }
}

object AccountGroupRules {
    const val MAX_NAME_LENGTH = 20

    /** Returns the trimmed name, or throws [DomainException] with [ErrorCode.NAME]. */
    fun name(value: String): String = value.trim().also {
        if (it.codePointCount(0, it.length) !in 1..MAX_NAME_LENGTH) throw DomainException(ErrorCode.NAME)
    }
}
