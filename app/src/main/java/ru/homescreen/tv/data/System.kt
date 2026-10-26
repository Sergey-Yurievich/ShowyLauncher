package ru.homescreen.tv.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object Apps {
    /** Shown first, in this order; the rest follow alphabetically. */
    private val PREFERRED = listOf(
        "top.rootu.lampa", "com.google.android.youtube.tv", "com.vk.tv", "net.gtvbox.vimuhd",
        "com.android.vending", "com.mobisystems.fileman", "com.yablio.sendfilestotv",
    )

    /** Live TV has its own entry under Входы; the rest are system bits that only clutter the grid. */
    private val HIDDEN = setOf(
        "com.tcl.tv", "com.tcl.suspension", "com.google.android.tvrecommendations",
        // TCL's settings dashboard, Bluetooth "listen alone" and system Settings: the gear button covers them.
        "com.tcl.dashboard", "com.tcl.t_solo", "com.android.tv.settings",
    )

    suspend fun load(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == context.packageName || pkg in HIDDEN || !seen.add(pkg)) continue
                val banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                out += AppInfo(
                    pkg = pkg,
                    label = ri.loadLabel(pm).toString(),
                    banner = banner?.toImage(320, 180),
                    icon = if (banner == null) ai.loadIcon(pm)?.toImage(128, 128) else null,
                    intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setComponent(ComponentName(pkg, ai.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                )
            }
        }
        out.sortedWith(compareBy<AppInfo> { PREFERRED.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { it.label.lowercase(Locale.ROOT) })
    }

    /** Whether this launcher is the TV's home screen, or only an app opened from the TV's own one. */
    fun isHome(context: Context): Boolean = context.packageManager
        .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName == context.packageName

    fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    fun launchPackage(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return launch(context, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun Drawable.toImage(w: Int, h: Int): ImageBitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap.asImageBitmap()
        val bw = if (intrinsicWidth > 0) intrinsicWidth else w
        val bh = if (intrinsicHeight > 0) intrinsicHeight else h
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        setBounds(0, 0, bw, bh)
        draw(c)
        return b.asImageBitmap()
    }
}

object Inputs {
    private fun tim(context: Context) = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager

    /** HDMI ports, AV and the tuner, as the TV's Settings > Inputs names them; hidden ones stay out. */
    fun list(context: Context): List<TvInput> {
        val tim = tim(context) ?: return emptyList()
        val out = ArrayList<TvInput>()
        val infos = try { tim.tvInputList } catch (e: Exception) { emptyList<TvInputInfo>() }
        // CEC devices show up as their own inputs whose parent is the HDMI port: their name labels the port.
        val cecNames = HashMap<String, String>()
        for (info in infos) {
            val parent = info.parentId ?: continue
            if (info.type == TvInputInfo.TYPE_HDMI) cecNames[parent] = info.loadLabel(context)?.toString() ?: continue
        }
        for (info in infos) {
            if (!info.isPassthroughInput || info.isHidden(context) || info.parentId != null) continue
            val hdmi = info.type == TvInputInfo.TYPE_HDMI
            var label = info.loadLabel(context)?.toString() ?: info.id
            if (hdmi && !label.uppercase(Locale.ROOT).contains("HDMI")) label = "HDMI $label"
            val custom = info.loadCustomLabel(context)?.toString()
            val device = cecNames[info.id] ?: custom?.takeIf { it.isNotEmpty() && it != label }
            val state = try { tim.getInputState(info.id) } catch (e: Exception) { -1 }
            out += TvInput(
                id = info.id,
                label = label,
                sub = device ?: if (state == TvInputManager.INPUT_STATE_CONNECTED) "Подключено" else "Нет сигнала",
                connected = when (state) {
                    TvInputManager.INPUT_STATE_CONNECTED -> true
                    TvInputManager.INPUT_STATE_DISCONNECTED -> false
                    else -> null
                },
                isHdmi = hdmi,
                isTuner = false,
            )
        }
        out.sortWith(compareBy<TvInput> { !it.isHdmi }.thenBy { it.label })
        val tuner = infos.firstOrNull { it.type == TvInputInfo.TYPE_TUNER && !it.isPassthroughInput && it.serviceInfo.packageName.startsWith("com.tcl") }
        if (tuner != null) out += TvInput(tuner.id, "Эфирное ТВ", "Антенна и кабель", null, isHdmi = false, isTuner = true)
        return out
    }

    enum class Console { PLAYSTATION, XBOX }

    /** The game console plugged in, by the name it gives over HDMI-CEC ("PlayStation 5", "Xbox Series X"); null if none. */
    fun console(list: List<TvInput>): Pair<TvInput, Console>? = list.firstNotNullOfOrNull { i ->
        val name = i.sub.lowercase(Locale.ROOT)
        when {
            "playstation" in name -> i to Console.PLAYSTATION
            "xbox" in name -> i to Console.XBOX
            else -> null
        }
    }

    fun open(context: Context, input: TvInput): Boolean {
        val uri: Uri = if (input.isTuner) TvContract.buildChannelsUriForInput(input.id) else TvContract.buildChannelUriForPassthroughInput(input.id)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            if (input.isTuner) {
                // The tuner opens through the Live TV app itself; it resumes the last channel.
                val live = context.packageManager.getLeanbackLaunchIntentForPackage("com.tcl.tv") ?: view
                context.startActivity(live.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                context.startActivity(view)
            }
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}

object WeatherApi {
    /** Until the network tells where the TV is. */
    val MOSCOW = Place("Москва", 55.7558, 37.6173, "Europe/Moscow")
    // MET Norway asks every client to name itself.
    private const val UA = "ru.homescreen.tv/0.1 (Android TV launcher)"

    /** The TV's city by its public IP (ip-api.com, names in Russian); null when the network cannot tell. */
    suspend fun locate(): Place? {
        val j = Net.json("http://ip-api.com/json/?fields=status,city,lat,lon,timezone&lang=ru") ?: return null
        if (j.optString("status") != "success" || !j.has("lat")) return null
        return Place(j.optString("city"), j.optDouble("lat"), j.optDouble("lon"), j.optString("timezone"))
    }

    /** The weather now at [p]: MET Norway, else open-meteo (which some Russian networks do not reach at all). */
    suspend fun now(p: Place): Weather? = metNo(p) ?: openMeteo(p)

    private suspend fun metNo(p: Place): Weather? {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f".format(Locale.US, p.lat, p.lon)
        val data = Net.json(url, UA)?.optJSONObject("properties")?.optJSONArray("timeseries")?.optJSONObject(0)
            ?.optJSONObject("data") ?: return null
        val temp = data.optJSONObject("instant")?.optJSONObject("details")?.optDouble("air_temperature") ?: return null
        if (temp.isNaN()) return null
        val symbol = (data.optJSONObject("next_1_hours") ?: data.optJSONObject("next_6_hours"))
            ?.optJSONObject("summary")?.optString("symbol_code").orEmpty()
        // "cloudy", "rain" and the like carry no _day / _night: then the local hour decides between sun and moon.
        val day = when {
            symbol.endsWith("_night") -> false
            symbol.endsWith("_day") -> true
            else -> java.time.LocalTime.now(p.zoneId ?: java.time.ZoneId.systemDefault()).hour in 7..18
        }
        return Weather(Math.round(temp).toInt(), wmo(symbol), day)
    }

    /** A MET Norway symbol ("partlycloudy_night", "lightrainshowers_day") as the WMO code [Weather] reads. */
    private fun wmo(symbol: String): Int {
        val s = symbol.substringBefore('_')
        return when {
            s.isEmpty() -> -1
            "thunder" in s -> 95
            "snow" in s -> if ("showers" in s) 85 else 73
            "sleet" in s -> 66
            "rain" in s -> if ("showers" in s) 80 else 63
            s == "fog" -> 45
            s == "cloudy" -> 3
            s == "partlycloudy" -> 2
            s == "fair" -> 1
            else -> 0
        }
    }

    private suspend fun openMeteo(p: Place): Weather? {
        val j = Net.json("https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}&current=temperature_2m,weather_code,is_day&timezone=auto")
            ?: return null
        val c = j.optJSONObject("current") ?: return null
        return Weather(Math.round(c.optDouble("temperature_2m")).toInt(), c.optInt("weather_code"), c.optInt("is_day") == 1)
    }

    fun encode(p: Place): String =
        org.json.JSONObject().put("city", p.city).put("lat", p.lat).put("lon", p.lon).put("zone", p.zone).toString()

    fun decode(s: String?): Place? = s?.let {
        runCatching {
            val o = org.json.JSONObject(it)
            Place(o.getString("city"), o.getDouble("lat"), o.getDouble("lon"), o.optString("zone"))
        }.getOrNull()
    }
}
