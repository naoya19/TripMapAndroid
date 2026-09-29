package com.patipan.tripmap.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val startedAt: Long,
    val endedAt: Long,
    val netDistanceMeters: Double,
    val traveledDistanceMeters: Double,
    val durationMillis: Long,
    val startChainageMeters: Int = 0,
    val chainageDirection: String = "LT"
)

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class,
        parentColumns = ["id"], childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("tripId")]
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val sequence: Int,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestamp: Long,
    val chainageMeters: Double
)

/** จุดทางแยก (3 แยก/4 แยก) ที่ผูกกับทริป — มาจากการแตะระบุเอง หรือดึงจาก OSM */
@Entity(
    tableName = "junctions",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class,
        parentColumns = ["id"], childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("tripId")]
)
data class JunctionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val latitude: Double,
    val longitude: Double,
    val type: String,
    val side: String,
    val source: String,
    val nearestChainageMeters: Double,
    val timestamp: Long
)

data class TripWithPoints(
    @Embedded val trip: TripEntity,
    @Relation(parentColumn = "id", entityColumn = "tripId")
    val points: List<TrackPointEntity>,
    @Relation(parentColumn = "id", entityColumn = "tripId")
    val junctions: List<JunctionEntity> = emptyList()
)

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun observeTrips(): Flow<List<TripEntity>>

    @Transaction
    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun tripWithPoints(id: Long): TripWithPoints?

    @Insert suspend fun insertTrip(trip: TripEntity): Long
    @Insert suspend fun insertPoints(points: List<TrackPointEntity>)
    @Insert suspend fun insertJunctions(junctions: List<JunctionEntity>)
    @Insert suspend fun insertJunction(junction: JunctionEntity): Long
    @Query("DELETE FROM junctions WHERE id = :id") suspend fun deleteJunction(id: Long)
    @Query("UPDATE trips SET name = :name WHERE id = :id") suspend fun updateTripName(id: Long, name: String)
    @Query("DELETE FROM trips WHERE id = :id") suspend fun deleteTrip(id: Long)
}

@Database(entities = [TripEntity::class, TrackPointEntity::class, JunctionEntity::class], version = 4, exportSchema = false)
abstract class TripDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN name TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN startChainageMeters INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trips ADD COLUMN chainageDirection TEXT NOT NULL DEFAULT 'LT'")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS junctions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        tripId INTEGER NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        type TEXT NOT NULL,
                        side TEXT NOT NULL,
                        source TEXT NOT NULL,
                        nearestChainageMeters REAL NOT NULL,
                        timestamp INTEGER NOT NULL,
                        FOREIGN KEY(tripId) REFERENCES trips(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_junctions_tripId ON junctions(tripId)")
            }
        }

        fun create(context: Context): TripDatabase = Room.databaseBuilder(
            context.applicationContext, TripDatabase::class.java, "trip-map.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
