package dev.valnook.feature.backup

import android.content.Intent
import android.content.IntentSender
import dev.valnook.domain.cloud.CloudAuthorizationGrant
import dev.valnook.domain.cloud.CloudBackupError

sealed interface GoogleAuthorizationOutcome {
    data class Granted(val grant: CloudAuthorizationGrant) : GoogleAuthorizationOutcome
    data class RequiresUserAction(val intentSender: IntentSender) : GoogleAuthorizationOutcome
    data object Cancelled : GoogleAuthorizationOutcome
    data class Failed(val error: CloudBackupError) : GoogleAuthorizationOutcome
}

interface GoogleDriveAuthorization {
    suspend fun begin(): GoogleAuthorizationOutcome
    suspend fun complete(resultData: Intent?): GoogleAuthorizationOutcome
    suspend fun revoke(accountReference: String): Boolean
}

object UnavailableGoogleDriveAuthorization : GoogleDriveAuthorization {
    override suspend fun begin() = GoogleAuthorizationOutcome.Failed(CloudBackupError.AUTH_FAILED)
    override suspend fun complete(resultData: Intent?) = GoogleAuthorizationOutcome.Failed(CloudBackupError.AUTH_FAILED)
    override suspend fun revoke(accountReference: String) = false
}
