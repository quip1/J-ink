package dev.onyxbox.ferry

import android.content.Context
import android.os.Build
import dev.onyxbox.ferry.core.Identity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Small persistent state: identity, trust list, known peers, history. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("ferry", Context.MODE_PRIVATE)

    val id: String
        get() = sp.getString("id", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("id", it).apply() }

    var deviceName: String
        get() = sp.getString("name", null) ?: defaultName()
        set(v) = sp.edit().putString("name", v.trim().ifEmpty { defaultName() }).apply()

    val identity get() = Identity(id, deviceName, "android")

    var receiving: Boolean
        get() = sp.getBoolean("receiving", true)
        set(v) = sp.edit().putBoolean("receiving", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("boot", true)
        set(v) = sp.edit().putBoolean("boot", v).apply()

    // ---- trust: peer app id -> display name
    fun trusted(): Map<String, String> = obj("trusted").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    fun isTrusted(id: String) = obj("trusted").has(id)
    fun trust(id: String, name: String) = put("trusted", obj("trusted").put(id, name))
    fun untrust(id: String) = put("trusted", obj("trusted").apply { remove(id) })

    // ---- known Ferry peers: bluetooth address -> {id,name,os,seen}
    data class Known(val address: String, val id: String, val name: String, val os: String, val seen: Long)
    fun known(): List<Known> = obj("known").let { o ->
        o.keys().asSequence().map { a ->
            val k = o.getJSONObject(a)
            Known(a, k.optString("id"), k.optString("name"), k.optString("os"), k.optLong("seen"))
        }.toList()
    }
    fun remember(address: String, id: String, name: String, os: String) = put("known", obj("known").put(address,
        JSONObject().put("id", id).put("name", name).put("os", os).put("seen", System.currentTimeMillis())))

    // ---- history (newest first, capped)
    data class Event(val time: Long, val text: String)
    fun history(): List<Event> {
        val a = JSONArray(sp.getString("history", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { e -> Event(e.getLong("t"), e.getString("x")) } }
    }
    fun log(text: String) = synchronized(Prefs::class) {
        val a = JSONArray().put(JSONObject().put("t", System.currentTimeMillis()).put("x", text))
        val old = JSONArray(sp.getString("history", "[]"))
        for (i in 0 until minOf(old.length(), 49)) a.put(old.get(i))
        sp.edit().putString("history", a.toString()).apply()
    }

    private fun obj(key: String) = JSONObject(sp.getString(key, "{}")!!)
    private fun put(key: String, o: JSONObject) = synchronized(Prefs::class) { sp.edit().putString(key, o.toString()).apply() }

    private fun defaultName(): String {
        val m = Build.MODEL ?: "Android"
        return if (Build.MANUFACTURER.equals("onyx", true) && !m.startsWith("Boox", true)) "Boox $m" else m
    }
}
