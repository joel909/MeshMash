package com.example.meshmash.mesh

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * Persistent request buffer. There is intentionally no automatic deletion: active, delivered, and
 * resolved request IDs remain available for duplicate detection until a future retention policy is
 * explicitly added.
 */
class MeshRequestStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_REQUESTS (
                $COLUMN_REQUEST_ID BLOB PRIMARY KEY NOT NULL,
                $COLUMN_ORIGIN_DEVICE_ID BLOB NOT NULL,
                $COLUMN_CATEGORY TEXT NOT NULL,
                $COLUMN_PAYLOAD BLOB NOT NULL,
                $COLUMN_REQUESTER_NAME TEXT,
                $COLUMN_REQUESTER_PHONE TEXT,
                $COLUMN_PERSONAL_ID_TYPE TEXT,
                $COLUMN_PERSONAL_ID_VALUE TEXT,
                $COLUMN_LATITUDE_E7 INTEGER,
                $COLUMN_LONGITUDE_E7 INTEGER,
                $COLUMN_LOCATION_ACCURACY REAL,
                $COLUMN_LOCATION_CAPTURED_AT INTEGER,
                $COLUMN_PRIORITY INTEGER NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_RECEIVED_AT INTEGER NOT NULL,
                $COLUMN_LAST_FORWARDED_AT INTEGER,
                $COLUMN_FORWARD_COUNT INTEGER NOT NULL,
                $COLUMN_STATUS TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX index_mesh_status_created " +
                "ON $TABLE_REQUESTS ($COLUMN_STATUS, $COLUMN_CREATED_AT)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_REQUESTS ADD COLUMN $COLUMN_LATITUDE_E7 INTEGER")
            db.execSQL("ALTER TABLE $TABLE_REQUESTS ADD COLUMN $COLUMN_LONGITUDE_E7 INTEGER")
            db.execSQL("ALTER TABLE $TABLE_REQUESTS ADD COLUMN $COLUMN_LOCATION_ACCURACY REAL")
            db.execSQL("ALTER TABLE $TABLE_REQUESTS ADD COLUMN $COLUMN_LOCATION_CAPTURED_AT INTEGER")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_REQUESTS ADD COLUMN $COLUMN_REQUESTER_NAME TEXT")
            db.execSQL("ALTER TABLE $TABLE_REQUESTS ADD COLUMN $COLUMN_REQUESTER_PHONE TEXT")
            db.execSQL("ALTER TABLE $TABLE_REQUESTS ADD COLUMN $COLUMN_PERSONAL_ID_TYPE TEXT")
            db.execSQL("ALTER TABLE $TABLE_REQUESTS ADD COLUMN $COLUMN_PERSONAL_ID_VALUE TEXT")
        }
    }

    /** Returns one stable UUID for this installation, suitable for originDeviceId. */
    fun getOrCreateDeviceId(): UUID {
        val preferences = appContext.getSharedPreferences(DEVICE_PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(DEVICE_ID_KEY, null)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()?.let { saved -> return saved }
        }
        val generated = MeshIdentifiers.generateUuid()
        preferences.edit().putString(DEVICE_ID_KEY, generated.toString()).apply()
        return generated
    }

    /** Creates a request and stores all of its data atomically. */
    fun createAndStore(
        category: String,
        payload: ByteArray,
        requester: RequesterIdentity? = null,
        location: MeshLocation? = null,
        priority: RequestPriority = RequestPriority.NORMAL,
        nowMillis: Long = System.currentTimeMillis(),
    ): MeshRequest {
        require(category.isNotBlank()) { "Request category cannot be blank" }
        require(payload.isNotEmpty()) { "Request payload cannot be empty" }
        val request = MeshRequest(
            requestId = MeshIdentifiers.generateUuid(),
            originDeviceId = getOrCreateDeviceId(),
            category = category.trim(),
            payload = payload.copyOf(),
            requester = requester?.normalized(),
            location = location,
            priority = priority,
            createdAtMillis = nowMillis,
            receivedAtMillis = nowMillis,
        )
        check(store(request)) { "Generated duplicate request ID; request was not stored" }
        return request
    }

    /**
     * Stores locally-created or received data. Returns false when requestId already exists, which
     * is the duplicate suppression check used before relaying a packet.
     */
    fun store(request: MeshRequest): Boolean {
        require(request.category.isNotBlank()) { "Request category cannot be blank" }
        require(request.payload.isNotEmpty()) { "Request payload cannot be empty" }
        val result = writableDatabase.insertWithOnConflict(
            TABLE_REQUESTS,
            null,
            request.toContentValues(),
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return result != -1L
    }

    fun contains(requestId: UUID): Boolean =
        queryById(requestId, columns = arrayOf(COLUMN_REQUEST_ID)).use { it.moveToFirst() }

    fun get(requestId: UUID): MeshRequest? =
        queryById(requestId).use { cursor ->
            if (cursor.moveToFirst()) cursor.toMeshRequest() else null
        }

    fun getAll(limit: Int = DEFAULT_QUERY_LIMIT): List<MeshRequest> {
        require(limit > 0)
        return readableDatabase.query(
            TABLE_REQUESTS,
            ALL_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_CREATED_AT DESC",
            limit.toString(),
        ).use { cursor -> cursor.readRequests() }
    }

    /** Returns locally retained requests with [status], ordered for sequential server upload. */
    fun getByStatus(
        status: RequestStatus,
        limit: Int = DEFAULT_QUERY_LIMIT,
    ): List<MeshRequest> {
        require(limit > 0)
        return readableDatabase.query(
            TABLE_REQUESTS,
            ALL_COLUMNS,
            "$COLUMN_STATUS = ?",
            arrayOf(status.name),
            null,
            null,
            "$COLUMN_PRIORITY DESC, $COLUMN_CREATED_AT ASC",
            limit.toString(),
        ).use { cursor -> cursor.readRequests() }
    }

    /** Returns active requests that are due, with emergencies and newer data first. */
    fun getRequestsDueForForwarding(
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = DEFAULT_QUERY_LIMIT,
    ): List<MeshRequest> {
        require(limit > 0)
        val active = readableDatabase.query(
            TABLE_REQUESTS,
            ALL_COLUMNS,
            "$COLUMN_STATUS = ?",
            arrayOf(RequestStatus.ACTIVE.name),
            null,
            null,
            "$COLUMN_PRIORITY DESC, $COLUMN_CREATED_AT DESC",
        ).use { cursor -> cursor.readRequests() }
        return active.asSequence()
            .filter { MeshForwardingPolicy.shouldForward(it, nowMillis) }
            .take(limit)
            .toList()
    }

    fun markForwarded(requestId: UUID, forwardedAtMillis: Long = System.currentTimeMillis()): Boolean {
        writableDatabase.execSQL(
            """
            UPDATE $TABLE_REQUESTS
            SET $COLUMN_LAST_FORWARDED_AT = ?,
                $COLUMN_FORWARD_COUNT = $COLUMN_FORWARD_COUNT + 1
            WHERE hex($COLUMN_REQUEST_ID) = ?
            """.trimIndent(),
            arrayOf<Any?>(forwardedAtMillis, requestId.toDatabaseHex()),
        )
        return get(requestId)?.lastForwardedAtMillis == forwardedAtMillis
    }

    fun updateStatus(requestId: UUID, status: RequestStatus): Boolean {
        val values = ContentValues().apply { put(COLUMN_STATUS, status.name) }
        return writableDatabase.update(
            TABLE_REQUESTS,
            values,
            "hex($COLUMN_REQUEST_ID) = ?",
            arrayOf(requestId.toDatabaseHex()),
        ) > 0
    }

    private fun queryById(
        requestId: UUID,
        columns: Array<String> = ALL_COLUMNS,
    ): Cursor = readableDatabase.query(
        TABLE_REQUESTS,
        columns,
        "hex($COLUMN_REQUEST_ID) = ?",
        arrayOf(requestId.toDatabaseHex()),
        null,
        null,
        null,
        "1",
    )

    private fun MeshRequest.toContentValues() = ContentValues().apply {
        put(COLUMN_REQUEST_ID, MeshIdentifiers.toBytes(requestId))
        put(COLUMN_ORIGIN_DEVICE_ID, MeshIdentifiers.toBytes(originDeviceId))
        put(COLUMN_CATEGORY, category)
        put(COLUMN_PAYLOAD, payload)
        if (requester == null) {
            putNull(COLUMN_REQUESTER_NAME)
            putNull(COLUMN_REQUESTER_PHONE)
            putNull(COLUMN_PERSONAL_ID_TYPE)
            putNull(COLUMN_PERSONAL_ID_VALUE)
        } else {
            put(COLUMN_REQUESTER_NAME, requester.fullName)
            put(COLUMN_REQUESTER_PHONE, requester.phoneNumber)
            if (requester.personalIdType == null) putNull(COLUMN_PERSONAL_ID_TYPE)
            else put(COLUMN_PERSONAL_ID_TYPE, requester.personalIdType)
            if (requester.personalIdValue == null) putNull(COLUMN_PERSONAL_ID_VALUE)
            else put(COLUMN_PERSONAL_ID_VALUE, requester.personalIdValue)
        }
        if (location == null) {
            putNull(COLUMN_LATITUDE_E7)
            putNull(COLUMN_LONGITUDE_E7)
            putNull(COLUMN_LOCATION_ACCURACY)
            putNull(COLUMN_LOCATION_CAPTURED_AT)
        } else {
            put(COLUMN_LATITUDE_E7, location.latitudeE7)
            put(COLUMN_LONGITUDE_E7, location.longitudeE7)
            put(COLUMN_LOCATION_ACCURACY, location.accuracyMeters)
            put(COLUMN_LOCATION_CAPTURED_AT, location.capturedAtMillis)
        }
        put(COLUMN_PRIORITY, priority.storageValue)
        put(COLUMN_CREATED_AT, createdAtMillis)
        put(COLUMN_RECEIVED_AT, receivedAtMillis)
        if (lastForwardedAtMillis == null) putNull(COLUMN_LAST_FORWARDED_AT)
        else put(COLUMN_LAST_FORWARDED_AT, lastForwardedAtMillis)
        put(COLUMN_FORWARD_COUNT, forwardCount)
        put(COLUMN_STATUS, status.name)
    }

    private fun Cursor.readRequests(): List<MeshRequest> = buildList {
        while (moveToNext()) add(toMeshRequest())
    }

    private fun Cursor.toMeshRequest() = MeshRequest(
        requestId = MeshIdentifiers.fromBytes(getBlob(getColumnIndexOrThrow(COLUMN_REQUEST_ID))),
        originDeviceId = MeshIdentifiers.fromBytes(
            getBlob(getColumnIndexOrThrow(COLUMN_ORIGIN_DEVICE_ID)),
        ),
        category = getString(getColumnIndexOrThrow(COLUMN_CATEGORY)),
        payload = getBlob(getColumnIndexOrThrow(COLUMN_PAYLOAD)),
        requester = readRequester(),
        location = readLocation(),
        priority = priorityFromStorage(getInt(getColumnIndexOrThrow(COLUMN_PRIORITY))),
        createdAtMillis = getLong(getColumnIndexOrThrow(COLUMN_CREATED_AT)),
        receivedAtMillis = getLong(getColumnIndexOrThrow(COLUMN_RECEIVED_AT)),
        lastForwardedAtMillis = getColumnIndexOrThrow(COLUMN_LAST_FORWARDED_AT).let {
            if (isNull(it)) null else getLong(it)
        },
        forwardCount = getInt(getColumnIndexOrThrow(COLUMN_FORWARD_COUNT)),
        status = RequestStatus.valueOf(getString(getColumnIndexOrThrow(COLUMN_STATUS))),
    )

    private fun Cursor.readLocation(): MeshLocation? {
        val latitudeColumn = getColumnIndexOrThrow(COLUMN_LATITUDE_E7)
        if (isNull(latitudeColumn)) return null
        return MeshLocation(
            latitudeE7 = getInt(latitudeColumn),
            longitudeE7 = getInt(getColumnIndexOrThrow(COLUMN_LONGITUDE_E7)),
            accuracyMeters = getFloat(getColumnIndexOrThrow(COLUMN_LOCATION_ACCURACY)),
            capturedAtMillis = getLong(getColumnIndexOrThrow(COLUMN_LOCATION_CAPTURED_AT)),
        )
    }

    private fun Cursor.readRequester(): RequesterIdentity? {
        val nameColumn = getColumnIndexOrThrow(COLUMN_REQUESTER_NAME)
        if (isNull(nameColumn)) return null
        val idTypeColumn = getColumnIndexOrThrow(COLUMN_PERSONAL_ID_TYPE)
        val idValueColumn = getColumnIndexOrThrow(COLUMN_PERSONAL_ID_VALUE)
        return RequesterIdentity(
            fullName = getString(nameColumn),
            phoneNumber = getString(getColumnIndexOrThrow(COLUMN_REQUESTER_PHONE)),
            personalIdType = if (isNull(idTypeColumn)) null else getString(idTypeColumn),
            personalIdValue = if (isNull(idValueColumn)) null else getString(idValueColumn),
        )
    }

    private fun priorityFromStorage(value: Int): RequestPriority =
        RequestPriority.entries.firstOrNull { it.storageValue == value } ?: RequestPriority.NORMAL

    private fun UUID.toDatabaseHex(): String =
        MeshIdentifiers.toBytes(this).joinToString(separator = "") {
            "%02X".format(it.toInt() and 0xFF)
        }

    companion object {
        private const val DATABASE_NAME = "mesh_requests.db"
        private const val DATABASE_VERSION = 3
        private const val TABLE_REQUESTS = "mesh_requests"
        private const val COLUMN_REQUEST_ID = "request_id"
        private const val COLUMN_ORIGIN_DEVICE_ID = "origin_device_id"
        private const val COLUMN_CATEGORY = "category"
        private const val COLUMN_PAYLOAD = "payload"
        private const val COLUMN_REQUESTER_NAME = "requester_name"
        private const val COLUMN_REQUESTER_PHONE = "requester_phone"
        private const val COLUMN_PERSONAL_ID_TYPE = "personal_id_type"
        private const val COLUMN_PERSONAL_ID_VALUE = "personal_id_value"
        private const val COLUMN_LATITUDE_E7 = "latitude_e7"
        private const val COLUMN_LONGITUDE_E7 = "longitude_e7"
        private const val COLUMN_LOCATION_ACCURACY = "location_accuracy"
        private const val COLUMN_LOCATION_CAPTURED_AT = "location_captured_at"
        private const val COLUMN_PRIORITY = "priority"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_RECEIVED_AT = "received_at"
        private const val COLUMN_LAST_FORWARDED_AT = "last_forwarded_at"
        private const val COLUMN_FORWARD_COUNT = "forward_count"
        private const val COLUMN_STATUS = "status"
        private const val DEVICE_PREFERENCES = "mesh_device_identity"
        private const val DEVICE_ID_KEY = "device_id"
        private const val DEFAULT_QUERY_LIMIT = 1_000

        private val ALL_COLUMNS = arrayOf(
            COLUMN_REQUEST_ID,
            COLUMN_ORIGIN_DEVICE_ID,
            COLUMN_CATEGORY,
            COLUMN_PAYLOAD,
            COLUMN_REQUESTER_NAME,
            COLUMN_REQUESTER_PHONE,
            COLUMN_PERSONAL_ID_TYPE,
            COLUMN_PERSONAL_ID_VALUE,
            COLUMN_LATITUDE_E7,
            COLUMN_LONGITUDE_E7,
            COLUMN_LOCATION_ACCURACY,
            COLUMN_LOCATION_CAPTURED_AT,
            COLUMN_PRIORITY,
            COLUMN_CREATED_AT,
            COLUMN_RECEIVED_AT,
            COLUMN_LAST_FORWARDED_AT,
            COLUMN_FORWARD_COUNT,
            COLUMN_STATUS,
        )
    }
}
