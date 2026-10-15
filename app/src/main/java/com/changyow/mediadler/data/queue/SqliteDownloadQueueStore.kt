package com.changyow.mediadler.data.queue

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import com.changyow.mediadler.core.model.QueuedDownload
import com.changyow.mediadler.core.repo.DownloadQueueStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable queue snapshots, separate from download history and other application data. */
class SqliteDownloadQueueStore(context: Context) : DownloadQueueStore {
    private val helper = QueueDatabase(context.applicationContext)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun read(): List<QueuedDownload> = synchronized(helper) {
        load(helper.readableDatabase)
    }

    override fun transact(
        change: (List<QueuedDownload>) -> List<QueuedDownload>,
    ): List<QueuedDownload> = synchronized(helper) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val previous = load(db)
            val updated = change(previous.toList()).toList()
            validate(updated)
            val previousById = previous.associateBy { it.task.id }
            val updatedIds = updated.mapTo(mutableSetOf()) { it.task.id }

            previousById.keys.filterNot { it in updatedIds }.forEach { id ->
                check(db.delete(TABLE, "id = ?", arrayOf(id)) == 1) {
                    "Queue record disappeared during transaction: $id"
                }
            }
            updated.forEach { record ->
                val id = record.task.id
                val existing = previousById[id]
                if (existing != record) {
                    val values = ContentValues().apply {
                        put("ordinal", record.task.queuePosition)
                        put("payload", json.encodeToString(record))
                    }
                    if (existing == null) {
                        values.put("id", id)
                        db.insertOrThrow(TABLE, null, values)
                    } else {
                        check(db.update(TABLE, values, "id = ?", arrayOf(id)) == 1) {
                            "Queue record disappeared during transaction: $id"
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
            updated.sortedBy { it.task.queuePosition }
        } finally {
            // A commit failure propagates before the updated snapshot can be returned.
            db.endTransaction()
        }
    }

    private fun load(db: SQLiteDatabase): List<QueuedDownload> {
        val rows = mutableListOf<QueuedDownload>()
        db.query(TABLE, arrayOf("id", "ordinal", "payload"), null, null, null, null, "ordinal ASC")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    check(!cursor.isNull(0) && !cursor.isNull(1) && !cursor.isNull(2)) {
                        "Queue record contains null fields"
                    }
                    val id = cursor.getString(0)
                    val ordinal = cursor.getLong(1)
                    val record = json.decodeFromString<QueuedDownload>(cursor.getString(2))
                    check(record.task.id == id && record.task.queuePosition == ordinal) {
                        "Queue record metadata does not match its payload: $id"
                    }
                    rows += record
                }
            }
        validate(rows)
        return rows
    }

    private fun validate(records: List<QueuedDownload>) {
        require(records.map { it.task.id }.toSet().size == records.size) {
            "Queue record IDs must be unique"
        }
        require(records.map { it.task.queuePosition }.toSet().size == records.size) {
            "Queue positions must be unique"
        }
    }

    private class QueueDatabase(context: Context) : SQLiteOpenHelper(
        context,
        "download_queue.db",
        null,
        1,
        DatabaseErrorHandler {
            // Android's default handler deletes corrupt databases. Keep the queue for recovery.
            throw SQLiteDatabaseCorruptException("Download queue database is corrupt; data was preserved")
        },
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (id TEXT PRIMARY KEY, ordinal INTEGER NOT NULL UNIQUE, payload TEXT NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported download queue database upgrade: $oldVersion to $newVersion")
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported download queue database downgrade: $oldVersion to $newVersion")
        }
    }

    private companion object {
        const val TABLE = "download_queue"
    }
}
