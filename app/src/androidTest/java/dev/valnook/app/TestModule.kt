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
import dev.valnook.data.database.ValnookDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Singleton

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
        Instant.parse("2026-09-30T12:00:00Z"),
        ZoneId.systemDefault(),
    )

    @Provides
    @Singleton
    internal fun databaseGraph(database: ValnookDatabase, clock: Clock): DatabaseGraph =
        createDatabaseGraph(database, clock)
}
