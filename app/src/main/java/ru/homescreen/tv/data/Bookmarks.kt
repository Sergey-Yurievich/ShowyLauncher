package ru.homescreen.tv.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Films and series put aside with «В закладки», newest first. One small JSON file. */
class Bookmarks(context: Context) {
    private val file = File(context.filesDir, "bookmarks.json")
    private val list: MutableList<Title> = try {
        if (file.exists()) JSONArray(file.readText()).let { a -> (0 until a.length()).map { Store.titleFromJson(a.getJSONObject(it)) }.toMutableList() }
        else mutableListOf()
    } catch (e: Exception) {
        mutableListOf()
    }

    @Synchronized
    fun all(): List<Title> = list.toList()

    @Synchronized
    fun has(key: String): Boolean = list.any { it.key == key }

    /** Adds the title on top, or takes it out if it is there; true when it is now bookmarked. */
    @Synchronized
    fun toggle(t: Title): Boolean {
        val had = list.removeAll { it.key == t.key }
        if (!had) list.add(0, t)
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONArray(list.map(Store::titleToJson)).toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
        return !had
    }
}
