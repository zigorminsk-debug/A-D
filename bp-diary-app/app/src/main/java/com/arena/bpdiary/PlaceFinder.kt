package com.arena.bpdiary

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Место для звонка в скорую: GPS и сетевой провайдер (Wi‑Fi и сотовые вышки).
 * Координаты никуда не отправляются, кроме системного геокодера телефона,
 * чтобы назвать ближайшее здание.
 */
object PlaceFinder {

    val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    data class Fix(
        val lat: Double,
        val lon: Double,
        val accuracyM: Float,
        val source: String,
        val time: Long
    )

    data class Report(
        val shouldSpeak: Boolean,
        val speech: String,
        val screen: String,
        /** true — в тексте звонка адрес назначения заменить на ближайшее здание. Запись в памяти не меняется. */
        val replaceAddress: Boolean = false,
        val spokenAddress: String = ""
    )

    private const val PREFS = "bp_emergency"
    private const val KEY_ASKED = "loc_asked"
    private const val FRESH_MS = 2L * 60L * 1000L
    private const val FIRST_WAIT_MS = 8_000L
    private const val STOP_MS = 12_000L
    private const val SAME_FLOOR_M = 250f

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    private var token = 0
    private var describeToken = 0
    private var onUpdate: ((Fix?) -> Unit)? = null
    private var best: Fix? = null
    private var emitted: Fix? = null
    private var running = false
    private var listening = false
    private var manager: LocationManager? = null
    private var listener: LocationListener? = null
    private var appCtx: Context? = null

    fun hasPermission(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun shouldAsk(ctx: Context): Boolean =
        !hasPermission(ctx) && !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun sensorsOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    fun locate(ctx: Context, onUpdate: (Fix?) -> Unit) {
        cancel()
        val my = token
        this.onUpdate = onUpdate
        val app = ctx.applicationContext
        if (!hasPermission(app) || !sensorsOn(app)) {
            emit(my, null)
            this.onUpdate = null
            return
        }
        running = true
        appCtx = app
        best = lastKnown(app)
        best?.let { if (goodEnough(it)) emit(my, it) }
        try {
            ContextCompat.startForegroundService(app, Intent(app, SosLocationService::class.java))
        } catch (_: Exception) {
            listen(app)
        }
        main.postDelayed({
            if (my != token || !running) return@postDelayed
            if (!listening) listen(app)
        }, 1_500)
        main.postDelayed({
            if (my != token || emitted != null) return@postDelayed
            if (best != null) emit(my, best)
        }, FIRST_WAIT_MS)
        main.postDelayed({
            if (my != token) return@postDelayed
            val better = best
            val sent = emitted
            if (better != null && (sent == null || muchBetter(better, sent))) emit(my, better)
            else if (sent == null) emit(my, null)
            if (my == token) stopSession()
        }, STOP_MS)
    }

    fun cancel() {
        token++
        describeToken++
        onUpdate = null
        stopSession()
    }

    /** Служба переднего плана вызывает это, чтобы поиск не оборвался, когда откроется звонилка. */
    fun onHostReady(ctx: Context) {
        if (running) listen(ctx.applicationContext)
    }

    fun describe(ctx: Context, fix: Fix, savedAddress: String, onReady: (Report) -> Unit) {
        val app = ctx.applicationContext
        val my = ++describeToken
        var lastSpeech: String? = null
        fun emit(report: Report) {
            main.post {
                if (my != describeToken) return@post
                if (report.speech == lastSpeech) return@post
                lastSpeech = report.speech
                onReady(report)
            }
        }
        main.postDelayed({
            if (my != describeToken || lastSpeech != null) return@postDelayed
            emit(reportOf(app, fix, savedAddress, building = "", savedPoint = null, geocoderFailed = true))
        }, 4_000)
        io.execute {
            try {
                val building = reverseBuilding(app, fix)
                val savedPoint = forward(app, savedAddress)
                emit(reportOf(app, fix, savedAddress, building, savedPoint, geocoderFailed = false))
            } catch (_: Exception) {
                emit(reportOf(app, fix, savedAddress, building = "", savedPoint = null, geocoderFailed = true))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun listen(ctx: Context) {
        if (!running || listening || !hasPermission(ctx)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val ear = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                offer(location, ctx)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = ear
        manager = lm
        listening = true
        val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine) request(lm, LocationManager.GPS_PROVIDER, ear)
        request(lm, LocationManager.NETWORK_PROVIDER, ear)
        request(lm, LocationManager.PASSIVE_PROVIDER, ear)
        lastKnown(ctx)?.let { offerFix(it) }
    }

    @SuppressLint("MissingPermission")
    private fun request(lm: LocationManager, provider: String, ear: LocationListener) {
        try {
            if (provider != LocationManager.PASSIVE_PROVIDER && !lm.isProviderEnabled(provider)) return
            lm.requestLocationUpdates(provider, 0L, 0f, ear, Looper.getMainLooper())
        } catch (_: Exception) {
        }
    }

    private fun offer(location: Location, ctx: Context) {
        offerFix(toFix(location, ctx, live = true) ?: return)
    }

    private fun offerFix(fix: Fix?) {
        val next = fix ?: return
        val prev = best
        if (prev != null && !better(next, prev)) return
        best = next
        val my = token
        val sent = emitted
        if (goodEnough(next) || (sent != null && muchBetter(next, sent))) emit(my, next)
    }

    private fun emit(my: Int, fix: Fix?) {
        if (my != token) return
        if (fix != null) emitted = fix
        val cb = onUpdate ?: return
        main.post {
            if (my != token) return@post
            cb(fix)
        }
    }

    private fun stopSession() {
        running = false
        listening = false
        val host = appCtx
        appCtx = null
        val lm = manager
        val ear = listener
        manager = null
        listener = null
        if (lm != null && ear != null) {
            try {
                lm.removeUpdates(ear)
            } catch (_: Exception) {
            }
        }
        if (host != null) stopHost(host)
    }

    fun stopHost(ctx: Context) {
        try {
            ctx.applicationContext.stopService(Intent(ctx.applicationContext, SosLocationService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun goodEnough(fix: Fix): Boolean {
        val age = System.currentTimeMillis() - fix.time
        if (age !in 0..FRESH_MS && fix.source != "gps") return false
        return fix.source == "gps" && fix.accuracyM <= 60f || fix.accuracyM <= 80f
    }

    private fun better(next: Fix, prev: Fix): Boolean {
        if (next.time + 1_000 < prev.time && next.accuracyM >= prev.accuracyM) return false
        if (next.source == "gps" && prev.source != "gps" && next.accuracyM <= prev.accuracyM + 30f) return true
        return next.accuracyM + 5f < prev.accuracyM
    }

    private fun muchBetter(next: Fix, prev: Fix): Boolean {
        if (next.source == "gps" && prev.source != "gps" && next.accuracyM <= 50f) return true
        return next.accuracyM < prev.accuracyM * 0.65f
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(ctx: Context): Fix? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        return providers.mapNotNull { provider ->
            try {
                lm.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            }
        }.mapNotNull { toFix(it, ctx, live = false) }
            .filter { System.currentTimeMillis() - it.time < 15L * 60L * 1000L }
            .minWithOrNull(compareBy<Fix> { if (it.source == "gps") 0 else 1 }.thenBy { it.accuracyM })
    }

    private fun toFix(location: Location, ctx: Context, live: Boolean): Fix? {
        if (location.latitude == 0.0 && location.longitude == 0.0) return null
        val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
        if (mock) return null
        val now = System.currentTimeMillis()
        val wall = location.time in (now - 24L * 60L * 60L * 1000L)..(now + 60_000L)
        // Живой замер с нечитаемыми часами всё равно текущий. Старый lastKnown с такими часами не берём.
        val whenMs = if (wall) location.time else if (live) now else return null
        val source = sourceOf(location, ctx)
        return Fix(location.latitude, location.longitude, location.accuracy, source, whenMs)
    }

    private fun sourceOf(location: Location, ctx: Context): String {
        val provider = location.provider.orEmpty()
        if (provider == LocationManager.GPS_PROVIDER || provider.equals("gps", true)) return "gps"
        val wifi = wifiConnected(ctx)
        return if (wifi && location.accuracy <= 200f) "wifi" else "cell"
    }

    private fun wifiConnected(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val caps = if (network != null) cm.getNetworkCapabilities(network) else null
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return true
            false
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun reverseBuilding(ctx: Context, fix: Fix): String {
        if (!Geocoder.isPresent()) return ""
        val geocoder = Geocoder(ctx, Locale("ru", "BY"))
        val list = geocoder.getFromLocation(fix.lat, fix.lon, 5) ?: return ""
        return list.maxByOrNull { score(it) }?.let { formatBuilding(it) }.orEmpty()
    }

    @Suppress("DEPRECATION")
    private fun forward(ctx: Context, saved: String): Pair<Double, Double>? {
        val query = saved.trim()
        if (query.isBlank() || !Geocoder.isPresent()) return null
        val geocoder = Geocoder(ctx, Locale("ru", "BY"))
        val hit = geocoder.getFromLocationName(query, 1)?.firstOrNull() ?: return null
        if (hit.latitude == 0.0 && hit.longitude == 0.0) return null
        return hit.latitude to hit.longitude
    }

    private fun reportOf(
        ctx: Context,
        fix: Fix,
        saved: String,
        building: String,
        savedPoint: Pair<Double, Double>?,
        geocoderFailed: Boolean
    ): Report {
        val savedText = saved.trim()
        val meters = savedPoint?.let { meters(fix.lat, fix.lon, it.first, it.second) }
        val limit = max(SAME_FLOOR_M, fix.accuracyM + 80f)
        val textSame = building.isNotBlank() && textMatches(savedText, building)
        val kind = when {
            savedText.isBlank() -> "missing"
            meters != null && meters <= limit -> "same"
            meters != null && meters > limit -> "differ"
            textSame -> "same"
            building.isNotBlank() && fix.accuracyM <= 150f && !textSame -> "differ"
            else -> "unsure"
        }
        if (geocoderFailed && kind == "differ" && meters == null) {
            return speechReport(ctx, fix, building, "unsure")
        }
        if (kind == "same") {
            val screen = ctx.getString(R.string.sos_place_same) + "\n" + rawLine(fix) + "\n" + sourceLine(ctx, fix)
            return Report(shouldSpeak = false, speech = "", screen = screen)
        }
        return speechReport(ctx, fix, building, kind)
    }

    private fun speechReport(ctx: Context, fix: Fix, building: String, kind: String): Report {
        val lead = when (kind) {
            "missing" -> ctx.getString(R.string.sos_place_no_saved)
            "differ" -> ctx.getString(R.string.sos_place_differ)
            else -> ctx.getString(R.string.sos_place_unsure)
        }
        val where = if (building.isBlank()) "" else ctx.getString(R.string.sos_place_building, building)
        val latSpoken = spokenNumber(abs(fix.lat))
        val lonSpoken = spokenNumber(abs(fix.lon))
        val latSide = if (fix.lat >= 0) ctx.getString(R.string.sos_lat_n) else ctx.getString(R.string.sos_lat_s)
        val lonSide = if (fix.lon >= 0) ctx.getString(R.string.sos_lon_e) else ctx.getString(R.string.sos_lon_w)
        val coords = ctx.getString(
            R.string.sos_place_coords,
            "$latSpoken $latSide",
            "$lonSpoken $lonSide",
            latSpoken,
            lonSpoken
        )
        val source = sourceLine(ctx, fix)
        val speech = lead + where + coords + source
        val screen = speech + "\n" + rawLine(fix)
        val replace = kind == "differ" || kind == "missing"
        val spoken = when {
            building.isNotBlank() -> building
            else -> rawLine(fix)
        }
        return Report(
            shouldSpeak = true,
            speech = speech,
            screen = screen,
            replaceAddress = replace,
            spokenAddress = if (replace) spoken else ""
        )
    }

    private fun sourceLine(ctx: Context, fix: Fix): String {
        val meters = fix.accuracyM.roundToInt().coerceAtLeast(5)
        val id = when (fix.source) {
            "gps" -> R.string.sos_place_source_gps
            "wifi" -> R.string.sos_place_source_wifi
            else -> R.string.sos_place_source_cell
        }
        return ctx.getString(id, meters)
    }

    private fun rawLine(fix: Fix): String =
        String.format(Locale.US, "%.5f, %.5f", fix.lat, fix.lon)

    private fun spokenNumber(value: Double): String =
        String.format(Locale("ru", "BY"), "%.5f", value)

    private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0]
    }

    private fun textMatches(saved: String, building: String): Boolean {
        val s = norm(saved)
        val b = norm(building)
        if (s.isBlank() || b.isBlank()) return false
        val house = Regex("""(?<!\d)\d{1,4}[а-яa-z]?""").find(s)?.value
        val tokens = s.split(" ").filter { it.length >= 5 && it.any(Char::isLetter) }
        if (tokens.isEmpty()) return false
        val streetHit = tokens.any { b.contains(it) }
        val houseHit = house == null || b.contains(house)
        return streetHit && houseHit
    }

    private fun norm(value: String): String =
        value.lowercase(Locale("ru"))
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9]"), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun score(address: Address): Int {
        var score = 0
        if (!address.thoroughfare.isNullOrBlank()) score += 4
        if (!address.subThoroughfare.isNullOrBlank()) score += 3
        if (!address.featureName.isNullOrBlank()) score += 2
        if (Build.VERSION.SDK_INT >= 29 && !address.premises.isNullOrBlank()) score += 2
        return score
    }

    private fun formatBuilding(address: Address): String {
        val house = address.subThoroughfare?.trim().orEmpty()
        val street = address.thoroughfare?.trim().orEmpty()
        val feature = address.featureName?.trim().orEmpty()
        val premises = if (Build.VERSION.SDK_INT >= 29) address.premises?.trim().orEmpty() else ""
        val locality = address.locality?.trim().orEmpty()
        val parts = mutableListOf<String>()
        if (premises.isNotBlank() && premises != feature && premises != house) parts += premises
        if (feature.isNotBlank() && feature != house && feature != street && !street.contains(feature)) {
            parts += feature
        }
        when {
            street.isNotBlank() && house.isNotBlank() -> parts += "$street, $house"
            street.isNotBlank() -> parts += street
            house.isNotBlank() -> parts += house
        }
        if (locality.isNotBlank()) parts += locality
        val text = parts.distinct().joinToString(", ")
        if (text.isNotBlank()) return text
        return address.getAddressLine(0)?.trim().orEmpty()
    }

    private fun granted(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
}
