package io.github.wakuwaku3.adaptivepulse.mobile.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * ダッシュボード表示用ローカル DB (Room)。
 * 端末横断は Firestore 経由の日次集約に任せ、こちらは表示用のフル粒度キャッシュに専念する。
 */
@Database(
    entities = [
        DailySnapshotEntity::class,
        MetricBySourceEntity::class,
        HeartRateSampleEntity::class,
        VitalSampleEntity::class,
        ExerciseSessionEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(VitalKindConverter::class)
abstract class DashboardDatabase : RoomDatabase() {

    abstract fun dashboardDao(): DashboardDao

    companion object {
        @Volatile private var instance: DashboardDatabase? = null

        fun get(context: Context): DashboardDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DashboardDatabase::class.java,
                    "adaptive_pulse_dashboard.db",
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
            }
        }
    }
}

class VitalKindConverter {
    @TypeConverter fun fromKind(kind: VitalKind): String = kind.name
    @TypeConverter fun toKind(name: String): VitalKind = VitalKind.valueOf(name)
}
