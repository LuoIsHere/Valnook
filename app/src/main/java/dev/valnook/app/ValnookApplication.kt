package dev.valnook.app
import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.valnook.app.cloud.CloudBackupWorkerOwner
import dev.valnook.data.cloud.CloudBackupCoordinator
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
@HiltAndroidApp
class ValnookApplication : Application(), CloudBackupWorkerOwner {
    @Inject override lateinit var cloudBackupCoordinator: CloudBackupCoordinator
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { cloudBackupCoordinator.reconcileSchedule() }
    }
}
