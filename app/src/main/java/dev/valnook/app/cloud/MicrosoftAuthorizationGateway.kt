package dev.valnook.app.cloud

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.valnook.app.BuildConfig
import dev.valnook.domain.cloud.*
import dev.valnook.feature.backup.CloudAuthorization
import dev.valnook.feature.backup.CloudAuthorizationOutcome
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class MicrosoftAuthorizationGateway @Inject constructor(@ApplicationContext private val context: Context) :
    CloudAuthorization, CloudAccessProvider {
    private val initialization = Mutex()
    private val interactive = Mutex()
    private var client: ISingleAccountPublicClientApplication? = null

    private suspend fun application(): ISingleAccountPublicClientApplication = initialization.withLock {
        client?.let { return@withLock it }
        if (BuildConfig.MICROSOFT_SIGNATURE_HASH.isBlank()) throw CloudBackupException(CloudBackupError.AUTH_NOT_CONFIGURED)
        val config = JSONObject().put("client_id", BuildConfig.MICROSOFT_CLIENT_ID)
            .put("redirect_uri", "msauth://${context.packageName}/${Uri.encode(BuildConfig.MICROSOFT_SIGNATURE_HASH)}")
            .put("account_mode", "SINGLE").put("authorization_user_agent", "BROWSER")
            .put("broker_redirect_uri_registered", false).put("shared_device_mode_supported", false)
            .put("logging", JSONObject().put("pii_enabled", false).put("logcat_enabled", false))
            .put("authorities", JSONArray().put(JSONObject().put("type", "AAD").put("default", true)
                .put("audience", JSONObject().put("type", "AzureADandPersonalMicrosoftAccount"))))
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "msal-public-config.json").also { it.writeText(config.toString()) }
        }
        suspendCancellableCoroutine<ISingleAccountPublicClientApplication> { continuation ->
            PublicClientApplication.createSingleAccountPublicClientApplication(context, file,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        if (continuation.isActive) continuation.resume(application)
                    }
                    override fun onError(exception: MsalException) {
                        if (continuation.isActive) continuation.resumeWithException(CloudBackupException(CloudBackupError.AUTH_FAILED))
                    }
                })
        }.also { client = it }
    }

    private suspend fun current(app: ISingleAccountPublicClientApplication): IAccount? = withContext(Dispatchers.IO) {
        app.currentAccount.currentAccount
    }

    override suspend fun connect(activity: Activity): CloudAuthorizationOutcome = interactive.withLock {
        try {
            val app = application()
            val account = current(app)
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<CloudAuthorizationOutcome> { continuation ->
                    val callback = object : AuthenticationCallback {
                        override fun onSuccess(result: IAuthenticationResult) {
                            if (continuation.isActive) continuation.resume(CloudAuthorizationOutcome.Granted(
                                CloudAuthorizationGrant(result.accessToken, CloudProvider.ONEDRIVE, reference(result.account))))
                        }
                        override fun onCancel() { if (continuation.isActive) continuation.resume(CloudAuthorizationOutcome.Cancelled) }
                        override fun onError(exception: MsalException) {
                            if (continuation.isActive) continuation.resume(CloudAuthorizationOutcome.Failed(CloudBackupError.AUTH_FAILED))
                        }
                    }
                    val parameters = SignInParameters.builder().withActivity(activity).withScopes(SCOPES)
                        .withCallback(callback).build()
                    if (account == null) app.signIn(parameters) else app.signInAgain(parameters)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: CloudBackupException) { CloudAuthorizationOutcome.Failed(error.error) }
        catch (_: Exception) { CloudAuthorizationOutcome.Failed(CloudBackupError.AUTH_FAILED) }
    }

    override suspend fun access(accountReference: String): CloudAccessResult = try {
        val app = application()
        val account = current(app)
        if (account == null || reference(account) != accountReference) CloudAccessResult.AuthorizationRequired
        else withContext(Dispatchers.IO) {
            val result = app.acquireTokenSilent(AcquireTokenSilentParameters.Builder().withScopes(SCOPES)
                .forAccount(account).fromAuthority(account.authority).build())
            if (reference(result.account) != accountReference) CloudAccessResult.AuthorizationRequired
            else CloudAccessResult.Granted(result.accessToken)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: MsalUiRequiredException) { CloudAccessResult.AuthorizationRequired }
    catch (_: Exception) { CloudAccessResult.Unavailable }

    override suspend fun disconnect(): Boolean = interactive.withLock {
        try {
            val app = application()
            withContext(Dispatchers.IO) { if (current(app) == null) true else app.signOut() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    override suspend fun managementUrl(): String {
        val account = try { current(application()) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        return if (account?.claims?.get("tid") == "9188040d-6c67-4c5b-b112-36a304b66dad")
            "https://account.live.com/consent/Manage" else "https://myapplications.microsoft.com/"
    }

    private fun reference(account: IAccount) = "${account.id}|${account.authority.trimEnd('/')}"
    private companion object {
        val SCOPES = listOf("https://graph.microsoft.com/Files.ReadWrite.AppFolder", "https://graph.microsoft.com/User.Read")
    }
}
