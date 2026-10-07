package dev.valnook.feature.backup

import android.app.Activity
import dev.valnook.domain.cloud.CloudAuthorizationGrant
import dev.valnook.domain.cloud.CloudBackupError

sealed interface CloudAuthorizationOutcome {
    data class Granted(val grant: CloudAuthorizationGrant) : CloudAuthorizationOutcome
    data object Cancelled : CloudAuthorizationOutcome
    data class Failed(val error: CloudBackupError) : CloudAuthorizationOutcome
}

/** SDK-specific callbacks, browser launch and token cache stay outside the ViewModel. */
interface CloudAuthorization {
    suspend fun connect(activity: Activity): CloudAuthorizationOutcome
    suspend fun disconnect(): Boolean
    suspend fun managementUrl(): String
}

object UnavailableCloudAuthorization : CloudAuthorization {
    override suspend fun connect(activity: Activity) = CloudAuthorizationOutcome.Failed(CloudBackupError.AUTH_NOT_CONFIGURED)
    override suspend fun disconnect() = true
    override suspend fun managementUrl() = "https://account.live.com/consent/Manage"
}
