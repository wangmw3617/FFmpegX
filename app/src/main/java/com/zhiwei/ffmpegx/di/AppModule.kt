package com.zhiwei.ffmpegx.di

import android.content.Context
import androidx.room.Room
import com.zhiwei.ffmpegx.core.hw.MediaCodecScanner
import com.zhiwei.ffmpegx.core.task.FFmpegXDatabase
import com.zhiwei.ffmpegx.core.task.TaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** 与应用同生命周期的协程作用域：任务队列跑在这里，切后台也不会被取消 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FFmpegXDatabase =
        Room.databaseBuilder(context, FFmpegXDatabase::class.java, "ffmpegx.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideTaskDao(database: FFmpegXDatabase): TaskDao = database.taskDao()

    @Provides
    @Singleton
    fun provideMediaCodecScanner(): MediaCodecScanner = MediaCodecScanner()

    // HardwarePlanner 有 @Inject constructor，这里不再重复 @Provides，
    // 否则会出现两个绑定，读代码的人会分不清哪个生效
}
