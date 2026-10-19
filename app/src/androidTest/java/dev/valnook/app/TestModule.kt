package dev.valnook.app

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.valnook.app.di.AppModule
import dev.valnook.app.di.DatabaseGraph
import dev.valnook.app.di.createDatabaseGraph
import dev.valnook.app.di.currentBuildInfo
import dev.valnook.data.database.ValnookDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Singleton
import dev.valnook.domain.cloud.CloudAccessProvider
import dev.valnook.domain.cloud.CloudAccessResult
import dev.valnook.domain.cloud.CloudBackupService
import dev.valnook.domain.cloud.BackupScheduler
import dev.valnook.feature.backup.CloudAuthorization
import dev.valnook.feature.backup.UnavailableCloudAuthorization

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AppModule::class])
object TestModule {
    @Provides
    @Singleton
    fun onboarding(@ApplicationContext context: Context): dev.valnook.app.onboarding.OnboardingStorage =
        dev.valnook.app.onboarding.OnboardingPreferences(context.getSharedPreferences("onboarding-session-fixture", 0)).apply {
            save(dev.valnook.app.onboarding.OnboardingDraft(step = dev.valnook.app.onboarding.OnboardingStep.LEGACY))
        }

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ValnookDatabase =
        Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed)
            // Fixture history must precede the fixed October 3 test clock, not the host date.
            // 测试内存库的历史起点必须早于固定测试时钟，不能使用宿主机日期。
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    val baseline = java.time.LocalDate.of(2026, 10, 1)
                    db.execSQL("UPDATE statistics_state SET baseline_at_ms=?,earliest_invalidated_epoch_day=? WHERE id=1",
                        arrayOf(baseline.atStartOfDay(ZoneId.of("Asia/Hong_Kong")).toInstant().toEpochMilli(), baseline.toEpochDay()))
                }
            })
            .build()

    @Provides
    @Singleton
    fun clock(): Clock = Clock.fixed(
        Instant.parse("2026-10-03T04:00:00Z"),
        ZoneId.of("Asia/Hong_Kong"),
    )

    @Provides
    @Singleton
    internal fun databaseGraph(@ApplicationContext context: Context, database: ValnookDatabase, clock: Clock): DatabaseGraph =
        createDatabaseGraph(context, database, clock, currentBuildInfo(), persistentPrivateCards = false)

    @Provides @Singleton fun cloudAccessProvider(): CloudAccessProvider = CloudAccessProvider { CloudAccessResult.Unavailable }
    @Provides @Singleton fun backupScheduler(): BackupScheduler = object : BackupScheduler {
        override suspend fun replace(nextDueAtUtcMs: Long, referenceUtcMs: Long, cycleId: String, dataGeneration: Long,
            connectionGeneration: Long, scheduleGeneration: Long) = Unit
        override suspend fun cancel() = Unit
    }
    @Provides @Singleton internal fun cloudBackupCoordinator(@ApplicationContext context: Context,
        database: ValnookDatabase, graph: DatabaseGraph, access: CloudAccessProvider,
        scheduler: BackupScheduler): dev.valnook.data.cloud.CloudBackupCoordinator =
        dev.valnook.data.cloud.createCloudBackupCoordinator(context, database, graph.portability, access, scheduler)
    @Provides @Singleton fun cloudBackupService(
        coordinator: dev.valnook.data.cloud.CloudBackupCoordinator): CloudBackupService = coordinator
    @Provides @Singleton fun cloudAuthorization(): CloudAuthorization = UnavailableCloudAuthorization
}
