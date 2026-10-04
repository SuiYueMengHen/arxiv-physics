package org.arxiv.physics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class Store(context: Context, databaseName: String = "arxiv.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE papers(id TEXT PRIMARY KEY, data TEXT NOT NULL, saved INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE feeds(key TEXT PRIMARY KEY, ids TEXT NOT NULL, total INTEGER NOT NULL, fetched INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY, stage TEXT NOT NULL, progress INTEGER NOT NULL, message TEXT NOT NULL, updated INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun putFeed(key: String, feed: Feed) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            feed.papers.forEach { p ->
                val values = ContentValues().apply { put("id", p.id); put("data", encode(p).toString()) }
                db.insertWithOnConflict("papers", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                // Compatible with Android 8 SQLite; update only metadata, preserving bookmarks.
                db.update("papers", ContentValues().apply { put("data", encode(p).toString()) }, "id=?", arrayOf(p.id))
            }
            db.insertWithOnConflict("feeds", null, ContentValues().apply {
                put("key", key); put("ids", JSONArray(feed.papers.map { it.id }).toString()); put("total", feed.total); put("fetched", feed.fetched)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun feed(key: String): Feed? = readableDatabase.rawQuery("SELECT ids,total,fetched FROM feeds WHERE key=?", arrayOf(key)).use { c ->
        if (!c.moveToFirst()) null else Feed(strings(JSONArray(c.getString(0))).mapNotNull(::paper), c.getInt(1), c.getLong(2))
    }
    fun paper(id: String): Paper? = readableDatabase.rawQuery("SELECT data FROM papers WHERE id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) decode(JSONObject(c.getString(0))) else null
    }
    fun saved(id: String) = readableDatabase.rawQuery("SELECT saved FROM papers WHERE id=?", arrayOf(id)).use { it.moveToFirst() && it.getInt(0) == 1 }
    fun save(id: String, value: Boolean) { writableDatabase.execSQL("UPDATE papers SET saved=? WHERE id=?", arrayOf<Any>(if (value) 1 else 0, id)) }
    fun library(): List<Paper> = readableDatabase.rawQuery("SELECT data FROM papers WHERE saved=1 ORDER BY id DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(decode(JSONObject(c.getString(0)))) }
    }
    fun job(job: TranslationJob) {
        writableDatabase.insertWithOnConflict("jobs", null, ContentValues().apply {
            put("id", job.id); put("stage", job.stage.name); put("progress", job.progress); put("message", job.message); put("updated", job.updated)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun jobs(): List<TranslationJob> = readableDatabase.rawQuery("SELECT id,stage,progress,message,updated FROM jobs ORDER BY updated DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(TranslationJob(c.getString(0), Stage.valueOf(c.getString(1)), c.getInt(2), c.getString(3), c.getLong(4))) }
    }
    companion object {
        fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
        fun encode(p: Paper) = JSONObject().apply {
            put("id", p.id); put("title", p.title); put("authors", JSONArray(p.authors)); put("summary", p.summary)
            put("published", p.published); put("updated", p.updated); put("categories", JSONArray(p.categories)); put("primary", p.primary)
            put("comment", p.comment); put("journal", p.journal); put("doi", p.doi)
        }
        fun decode(j: JSONObject) = Paper(j.getString("id"), j.getString("title"), strings(j.getJSONArray("authors")), j.getString("summary"),
            j.getString("published"), j.getString("updated"), strings(j.getJSONArray("categories")), j.getString("primary"), j.optString("comment"), j.optString("journal"), j.optString("doi"))
    }
}
