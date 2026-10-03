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
import dev.valnook.feature.backup.GoogleDriveAuthorization
import dev.valnook.feature.backup.UnavailableGoogleDriveAuthorization

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AppModule::class])
object TestModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ValnookDatabase =
        Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed)
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
        createDatabaseGraph(context, database, clock, currentBuildInfo())

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
    @Provides @Singleton fun googleDriveAuthorization(): GoogleDriveAuthorization = UnavailableGoogleDriveAuthorization
}
