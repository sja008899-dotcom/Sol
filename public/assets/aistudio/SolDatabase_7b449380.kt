package com.sol.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SolDatabase(context: Context) : SQLiteOpenHelper(context, "sol_memory.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, key_text TEXT NOT NULL UNIQUE, value_text TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, user_text TEXT NOT NULL, sol_text TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun remember(key: String, value: String, kind: String = "fact") {
        val v = ContentValues().apply {
            put("kind", kind); put("key_text", key); put("value_text", value)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("memories", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun recall(key: String): String? =
        readableDatabase.rawQuery("SELECT value_text FROM memories WHERE key_text=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    fun allMemories(): List<Pair<String,String>> {
        val out = mutableListOf<Pair<String,String>>()
        readableDatabase.rawQuery("SELECT key_text,value_text FROM memories ORDER BY updated_at DESC", null).use {
            while (it.moveToNext()) out.add(it.getString(0) to it.getString(1))
        }
        return out
    }
    fun history(user: String, answer: String) {
        val v = ContentValues().apply {
            put("user_text", user); put("sol_text", answer); put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("history", null, v)
    }
    fun forget(key: String) = writableDatabase.delete("memories", "key_text=?", arrayOf(key)) > 0
}
