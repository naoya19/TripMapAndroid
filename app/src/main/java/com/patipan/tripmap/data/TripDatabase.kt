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

/** จุดทางแยก (3 แยก/4 แยก) ที่ผูกกับทริป — มาจากการแตะระบุเอง หรือดึงจาก OSM
 *
 *  ค่าถนน (lanes/width/shoulder/surface) เป็นค่า "วัดเฉพาะจุด" — ถ้าเป็น null จะไปใช้ค่าของ
 *  ช่วงถนน (RoadSegmentEntity) ที่ครอบคลุมจุดนั้นแทน ถ้าไม่มีช่วงครอบก็ค่าว่าง
 */
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
    val timestamp: Long,
    // ── v1.5 ──
    val note: String = "",
    val lanes: Int? = null,
    val widthMeters: Double? = null,
    val shoulderMeters: Double? = null,
    val surface: String = SurfaceKind.UNKNOWN,
    val photoRef: String? = null
)

/** ช่วงของถนน — ข้อมูลความกว้าง/เลน/ไหล่ทาง/ผิว ที่ใช้ตลอดช่วง ไม่ใช่เฉพาะจุด
 *
 *  อ้างอิงด้วยระยะตามเส้นทาง (chainageMeters) ไม่ใช่พิกัด lat/lon เพราะช่วงต้องอยู่บนเส้นทาง
 *  และทริปถัดไปอาจเริ่ม กม. ไม่เหมือนเดิม — อ้างด้วยระยะจึงตัดช่วงตอน export ได้ตรงเสมอ
 */
@Entity(
    tableName = "road_segments",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class,
        parentColumns = ["id"], childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("tripId")]
)
data class RoadSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String = "",
    val startChainageMeters: Int,
    val endChainageMeters: Int,
    val lanes: Int = 2,
    val widthMeters: Double? = null,
    val shoulderMeters: Double? = null,
    val surface: String = SurfaceKind.UNKNOWN,
    val note: String = "",
    val colorIndex: Int = 5,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** ค่าที่เก็บเป็น String ในฐานข้อมูล เพื่อให้แก้/เพิ่มชนิดผิวถนนได้ในอนาคตโดยไม่ต้องแก้ schema */
object SurfaceKind {
    const val UNKNOWN = "UNKNOWN"
    const val ASPHALT = "ASPHALT"
    const val CONCRETE = "CONCRETE"
    const val GRAVEL = "GRAVEL"
    const val DIRT = "DIRT"

    fun label(value: String) = when (value) {
        ASPHALT -> "Asphalt"
        CONCRETE -> "คอนกรีต"
        GRAVEL -> "ลูกรัง"
        DIRT -> "ถนนดิน"
        else -> "ยังไม่ระบุ"
    }

    val all = listOf(ASPHALT, CONCRETE, GRAVEL, DIRT)
}

data class TripWithPoints(
    @Embedded val trip: TripEntity,
    @Relation(parentColumn = "id", entityColumn = "tripId")
    val points: List<TrackPointEntity>,
    @Relation(parentColumn = "id", entityColumn = "tripId")
    val junctions: List<JunctionEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "tripId")
    val roadSegments: List<RoadSegmentEntity> = emptyList()
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

    @Query("""
        UPDATE junctions SET type = :type, side = :side, note = :note,
            lanes = :lanes, widthMeters = :width, shoulderMeters = :shoulder,
            surface = :surface, photoRef = :photoRef
        WHERE id = :id
    """)
    suspend fun updateJunction(
        id: Long, type: String, side: String, note: String,
        lanes: Int?, width: Double?, shoulder: Double?, surface: String, photoRef: String?
    )

    @Query("UPDATE trips SET name = :name WHERE id = :id") suspend fun updateTripName(id: Long, name: String)
    @Query("DELETE FROM trips WHERE id = :id") suspend fun deleteTrip(id: Long)

    // ── ช่วงของถนน (v1.5) ──
    @Query("SELECT * FROM road_segments WHERE tripId = :tripId ORDER BY startChainageMeters")
    suspend fun segmentsOf(tripId: Long): List<RoadSegmentEntity>

    @Query("SELECT * FROM road_segments WHERE tripId = :tripId ORDER BY startChainageMeters")
    fun observeSegments(tripId: Long): Flow<List<RoadSegmentEntity>>

    @Insert suspend fun insertSegment(segment: RoadSegmentEntity): Long

    @Query("""
        UPDATE road_segments SET name = :name, startChainageMeters = :start, endChainageMeters = :end,
            lanes = :lanes, widthMeters = :width, shoulderMeters = :shoulder,
            surface = :surface, note = :note, updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateSegment(
        id: Long, name: String, start: Int, end: Int, lanes: Int,
        width: Double?, shoulder: Double?, surface: String, note: String, updatedAt: Long
    )

    @Query("DELETE FROM road_segments WHERE id = :id") suspend fun deleteSegment(id: Long)
}

@Database(
    entities = [TripEntity::class, TrackPointEntity::class, JunctionEntity::class, RoadSegmentEntity::class],
    version = 5,
    exportSchema = false
)
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

        /** v1.5 — เพิ่มคอลัมน์คุณสมบัติถนนให้จุดทางแยก และตารางช่วงของถนน */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // nullable → ไม่ต้องใส่ DEFAULT
                db.execSQL("ALTER TABLE junctions ADD COLUMN lanes INTEGER")
                db.execSQL("ALTER TABLE junctions ADD COLUMN widthMeters REAL")
                db.execSQL("ALTER TABLE junctions ADD COLUMN shoulderMeters REAL")
                db.execSQL("ALTER TABLE junctions ADD COLUMN photoRef TEXT")
                // NOT NULL → ต้องมี DEFAULT และค่า default ต้องตรงกับที่ประกาศใน entity
                db.execSQL("ALTER TABLE junctions ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE junctions ADD COLUMN surface TEXT NOT NULL DEFAULT 'UNKNOWN'")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS road_segments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        tripId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        startChainageMeters INTEGER NOT NULL,
                        endChainageMeters INTEGER NOT NULL,
                        lanes INTEGER NOT NULL DEFAULT 2,
                        widthMeters REAL,
                        shoulderMeters REAL,
                        surface TEXT NOT NULL DEFAULT 'UNKNOWN',
                        note TEXT NOT NULL DEFAULT '',
                        colorIndex INTEGER NOT NULL DEFAULT 5,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(tripId) REFERENCES trips(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_road_segments_tripId ON road_segments(tripId)")
            }
        }

        fun create(context: Context): TripDatabase = Room.databaseBuilder(
            context.applicationContext, TripDatabase::class.java, "trip-map.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}