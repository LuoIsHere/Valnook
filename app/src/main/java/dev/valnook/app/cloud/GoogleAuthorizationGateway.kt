package dev.valnook.app.cloud

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.valnook.domain.cloud.CloudAccessProvider
import dev.valnook.domain.cloud.CloudAccessResult
import dev.valnook.domain.cloud.CloudAuthorizationGrant
import dev.valnook.domain.cloud.CloudBackupError
import dev.valnook.feature.backup.GoogleAuthorizationOutcome
import dev.valnook.feature.backup.GoogleDriveAuthorization
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class GoogleAuthorizationGateway @Inject constructor(
    @ApplicationContext context: Context
) : GoogleDriveAuthorization, CloudAccessProvider {
    private val client = Identity.getAuthorizationClient(context.applicationContext)

    override suspend fun begin(): GoogleAuthorizationOutcome = try {
        outcome(client.authorize(request(null)).awaitTask())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        GoogleAuthorizationOutcome.Failed(CloudBackupError.AUTH_FAILED)
    }

    override suspend fun complete(resultData: Intent?): GoogleAuthorizationOutcome {
        if (resultData == null) return GoogleAuthorizationOutcome.Cancelled
        return try {
            outcome(client.getAuthorizationResultFromIntent(resultData))
        } catch (error: ApiException) {
            if (error.statusCode == CommonStatusCodes.CANCELED) GoogleAuthorizationOutcome.Cancelled
            else GoogleAuthorizationOutcome.Failed(CloudBackupError.AUTH_FAILED)
        } catch (_: Exception) {
            GoogleAuthorizationOutcome.Failed(CloudBackupError.AUTH_FAILED)
        }
    }

    override suspend fun access(accountReference: String): CloudAccessResult = try {
        val result = client.authorize(request(accountReference)).awaitTask()
        if (result.hasResolution()) CloudAccessResult.AuthorizationRequired
        else result.accessToken?.takeIf(String::isNotBlank)?.let(CloudAccessResult::Granted)
            ?: CloudAccessResult.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CloudAccessResult.Unavailable
    }

    override suspend fun revoke(accountReference: String): Boolean = try {
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(accountReference, GOOGLE_ACCOUNT_TYPE))
            .setScopes(SCOPES)
            .build()
        client.revokeAccess(request).awaitTask()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun request(accountReference: String?): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setRequestedScopes(SCOPES)
        if (accountReference != null) builder.setAccount(Account(accountReference, GOOGLE_ACCOUNT_TYPE))
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun outcome(result: AuthorizationResult): GoogleAuthorizationOutcome {
        if (result.hasResolution()) {
            val sender = result.pendingIntent?.intentSender
                ?: return GoogleAuthorizationOutcome.Failed(CloudBackupError.AUTH_FAILED)
            return GoogleAuthorizationOutcome.RequiresUserAction(sender)
        }
        val token = result.accessToken?.takeIf(String::isNotBlank)
            ?: return GoogleAuthorizationOutcome.Failed(CloudBackupError.AUTH_FAILED)
        return GoogleAuthorizationOutcome.Granted(CloudAuthorizationGrant(token))
    }

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        val SCOPES = listOf(Scope(DRIVE_FILE_SCOPE))
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
