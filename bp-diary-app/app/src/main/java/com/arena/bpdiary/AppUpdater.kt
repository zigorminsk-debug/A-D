package com.arena.bpdiary

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Смотрит релиз GitHub `latest-apk` и ставит более новую сборку поверх текущей.
 * Android всё равно показывает системное подтверждение — без него обычное
 * приложение не может обновиться само. Данные дневника при этом не стираются:
 * подпись та же.
 *
 * Репозиторий закрытый: без токена GitHub телефон получает 404. Токен хранится
 * только на устройстве и не вшивается в сборку.
 */
object AppUpdater {

    private const val RELEASE_URL =
        "https://api.github.com/repos/zigorminsk-debug/A-D/releases/tags/latest-apk"
    private const val APK_NAME = "BPDiary-release.apk"
    private const val META_NAME = "version.json"
    private const val TOKEN_URL =
        "https://github.com/settings/tokens/new?scopes=repo&description=BPDiary"
    private const val PREFS = "bp_updater"
    private const val KEY_TOKEN = "token"
    private const val KEY_CHECKED = "checked"
    private const val KEY_ASK_AFTER = "ask_after"
    private const val KEY_READY = "ready"
    private const val KEY_READY_CODE = "ready_code"
    private const val KEY_OFFERED = "offered"
    private const val KEY_INSTALLER = "installer_shown"
    private const val KEY_SETTINGS = "settings_opened"
    private const val CHECK_MS = 30L * 60L * 1000L
    private const val ASK_MS = 3L * 24L * 60L * 60L * 1000L
    private const val MAX_BYTES = 40_000_000
    private const val MIN_BYTES = 100_000L

    private var hostRef = java.lang.ref.WeakReference<AppCompatActivity>(null)
    private var dialog: AlertDialog? = null

    @Volatile private var checking = false
    @Volatile private var downloading = false
    @Volatile private var cancelled = false
    @Volatile private var progressName = ""
    @Volatile private var progressPct = 0

    fun onHostResume(activity: AppCompatActivity) {
        hostRef = java.lang.ref.WeakReference(activity)
        try {
            cleanupInstalled(activity)
            if (downloading) {
                showProgress(activity, progressName, progressPct)
                return
            }
            if (hasReadyApk(activity) && !flag(activity, KEY_INSTALLER)) {
                tryInstall(activity)
                return
            }
            maybeAuto(activity)
        } catch (_: Exception) {
        }
    }

    fun start(activity: AppCompatActivity, manual: Boolean) {
        hostRef = java.lang.ref.WeakReference(activity)
        if (checking || downloading) {
            if (manual) toast(activity, R.string.update_busy)
            return
        }
        checking = true
        cancelled = false
        if (manual) {
            prefs(activity).edit()
                .putBoolean(KEY_INSTALLER, false)
                .putBoolean(KEY_SETTINGS, false)
                .apply()
            toast(activity, R.string.update_checking)
        }
        val app = activity.applicationContext
        thread(name = "bp-update", isDaemon = true) {
            try {
                work(app, manual)
            } catch (_: Exception) {
                ui { if (manual) toast(it, R.string.update_fail_net) }
            } finally {
                checking = false
                downloading = false
            }
        }
    }

    fun showTokenDialog(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed || downloading) return
        if (dialog?.isShowing == true) return
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.update_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
            if (Build.VERSION.SDK_INT >= 26) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
        }
        val wrap = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val shown = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_token_title)
            .setMessage(R.string.update_token_msg)
            .setView(wrap)
            .setPositiveButton(R.string.update_token_save, null)
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_token_create, null)
            .create()
        Dialogs.attachToIme(shown)
        shown.setOnShowListener {
            shown.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                openUrl(activity, TOKEN_URL)
            }
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = normalizeToken(input.text?.toString().orEmpty())
                if (value.length < 20) {
                    input.error = activity.getString(R.string.update_fail_token)
                    return@setOnClickListener
                }
                shown.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                thread(name = "bp-token", isDaemon = true) {
                    val ok = probe(value)
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        if (ok) {
                            prefs(activity).edit()
                                .putString(KEY_TOKEN, value)
                                .putLong(KEY_ASK_AFTER, Long.MAX_VALUE)
                                .commit()
                            shown.dismiss()
                            start(activity, manual = true)
                        } else {
                            shown.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            input.error = activity.getString(R.string.update_fail_token)
                        }
                    }
                }
            }
        }
        shown.setOnDismissListener {
            if (dialog === shown) dialog = null
        }
        dialog = shown
        shown.show()
    }

    private fun work(app: Context, manual: Boolean) {
        val result = check(app)
        when (result) {
            is Check.Update -> {
                markChecked(app)
                val have = hasMatchingApk(app, result)
                if (have && (manual || !flag(app, KEY_INSTALLER))) {
                    ui { tryInstall(it) }
                    return
                }
                if (!manual && flag(app, KEY_INSTALLER) && offered(app) >= result.code) return
                downloading = true
                progressName = result.name
                progressPct = 0
                ui { showProgress(it, result.name, 0) }
                val dl = downloadApk(app, result) { pct ->
                    progressPct = pct
                    ui { showProgress(it, result.name, pct) }
                }
                downloading = false
                when (dl) {
                    is Dl.Ok -> {
                        prefs(app).edit()
                            .putBoolean(KEY_READY, true)
                            .putInt(KEY_READY_CODE, result.code)
                            .putBoolean(KEY_INSTALLER, false)
                            .apply()
                        ui {
                            dismissDialog()
                            tryInstall(it)
                        }
                    }
                    is Dl.Cancelled -> ui { dismissDialog() }
                    is Dl.Err -> ui {
                        dismissDialog()
                        toast(it, dl.msg)
                    }
                }
            }
            Check.Current -> {
                markChecked(app)
                ui { if (manual) toast(it, R.string.update_uptodate) }
            }
            Check.NeedToken -> {
                markChecked(app)
                if (manual || shouldAsk(app)) {
                    if (!manual) postponeAsk(app)
                    ui { promptToken(it, delayed = !manual) }
                }
            }
            is Check.Bad -> {
                if (result.msg != R.string.update_fail_net) markChecked(app)
                ui {
                    if (manual) toast(it, result.msg)
                    if (result.askToken && (manual || shouldAsk(it))) {
                        if (!manual) postponeAsk(it)
                        promptToken(it, delayed = !manual)
                    }
                }
            }
        }
    }

    private fun maybeAuto(activity: AppCompatActivity) {
        if (checking || downloading) return
        val last = prefs(activity).getLong(KEY_CHECKED, 0L)
        if (System.currentTimeMillis() - last < CHECK_MS) return
        start(activity, manual = false)
    }

    private fun promptToken(activity: AppCompatActivity, delayed: Boolean) {
        if (!delayed) {
            showTokenDialog(activity)
            return
        }
        activity.window.decorView.postDelayed({
            val a = hostRef.get() ?: return@postDelayed
            if (!a.isFinishing && !downloading) showTokenDialog(a)
        }, 700)
    }

    private fun tryInstall(activity: Activity) {
        val apk = apkFile(activity)
        if (!flag(activity, KEY_READY) || !apk.isFile || apk.length() < MIN_BYTES) {
            clearReady(activity)
            return
        }
        if (flag(activity, KEY_INSTALLER)) return
        if (!trustedApk(activity, apk)) {
            apk.delete()
            clearReady(activity)
            toast(activity, R.string.update_fail_sign)
            return
        }
        val apkCode = archiveVersion(activity, apk)
        if (apkCode <= localCode(activity)) {
            apk.delete()
            clearReady(activity)
            prefs(activity).edit().putInt(KEY_OFFERED, apkCode.coerceAtLeast(offered(activity))).apply()
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            if (flag(activity, KEY_SETTINGS)) return
            prefs(activity).edit().putBoolean(KEY_SETTINGS, true).apply()
            toast(activity, R.string.update_allow_install)
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            } catch (_: Exception) {
                toast(activity, R.string.update_fail_install)
            }
            return
        }
        prefs(activity).edit()
            .putBoolean(KEY_SETTINGS, false)
            .putBoolean(KEY_INSTALLER, true)
            .putInt(KEY_OFFERED, apkCode)
            .apply()
        toast(activity, R.string.update_confirm_install)
        if (!launchInstaller(activity, apk)) {
            prefs(activity).edit().putBoolean(KEY_INSTALLER, false).apply()
            toast(activity, R.string.update_fail_install)
        }
    }

    private fun launchInstaller(activity: Activity, apk: File): Boolean {
        val uri = try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        } catch (_: Exception) {
            return false
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pm = activity.packageManager
        val resolved = try {
            pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            emptyList()
        }
        for (r in resolved) {
            try {
                activity.grantUriPermission(
                    r.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        return try {
            activity.startActivity(view)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun showProgress(activity: AppCompatActivity, name: String, pct: Int) {
        val msg = activity.getString(R.string.update_downloading, name, pct)
        val existing = dialog
        if (existing != null) {
            try {
                if (existing.isShowing && existing.context.findActivity() === activity) {
                    existing.setMessage(msg)
                    return
                }
            } catch (_: Exception) {
            }
        }
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_title)
            .setMessage(msg)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .show()
    }

    private fun dismissDialog() {
        try {
            dialog?.dismiss()
        } catch (_: Exception) {
        }
        dialog = null
    }

    private fun check(ctx: Context): Check {
        val tok = token(ctx)
        val http = try {
            httpText(RELEASE_URL, tok, "application/vnd.github+json", 500_000)
        } catch (_: Exception) {
            return Check.Bad(R.string.update_fail_net)
        }
        if (http.code == 401) return Check.Bad(R.string.update_fail_token, askToken = true)
        if (http.code == 404 || http.code == 403) {
            if (tok.isEmpty()) return Check.NeedToken
            if (http.code == 403 && http.text.contains("rate limit", ignoreCase = true)) {
                return Check.Bad(R.string.update_fail_net)
            }
            if (http.code == 403) return Check.Bad(R.string.update_fail_token, askToken = true)
            return Check.Bad(R.string.update_fail_release)
        }
        if (http.code !in 200..299) return Check.Bad(R.string.update_fail_net)
        val json = try {
            JSONObject(http.text)
        } catch (_: Exception) {
            return Check.Bad(R.string.update_fail_net)
        }
        val assets = loadAssets(json, tok)
        var version = parseNotes(json.optString("body"))
        if (version == null) {
            val meta = assets.firstOrNull { it.name == META_NAME }
            if (meta != null) version = fetchMeta(meta, tok)
        }
        if (version == null || version.code <= 0) return Check.Bad(R.string.update_no_version)
        if (version.code <= localCode(ctx)) return Check.Current
        val apk = assets.firstOrNull { it.name == APK_NAME }
            ?: return Check.Bad(R.string.update_no_asset)
        return Check.Update(version.code, version.name, apk, version.sha)
    }

    private fun fetchMeta(asset: Asset, token: String): Ver? {
        val text = try {
            val first = httpText(asset.apiUrl, token, "application/octet-stream", 8_192)
            if (first.code in 200..299) first.text else {
                val second = httpText(asset.browserUrl, "", "application/json", 8_192)
                if (second.code in 200..299) second.text else return null
            }
        } catch (_: Exception) {
            return null
        }
        return parseMeta(text)
    }

    private fun probe(token: String): Boolean {
        return try {
            httpText(RELEASE_URL, token, "application/vnd.github+json", 200_000).code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadApk(app: Context, update: Check.Update, onPct: (Int) -> Unit): Dl {
        val dest = apkFile(app)
        val urls = listOf(update.asset.apiUrl to token(app), update.asset.browserUrl to "")
        var last = R.string.update_fail_file
        for ((url, auth) in urls) {
            if (url.isBlank() || cancelled) break
            val got = saveUrl(url, auth, dest, update.asset.size, update.sha, onPct)
            when (got) {
                is Dl.Ok -> {
                    if (!apkLooksValid(dest)) {
                        dest.delete()
                        return Dl.Err(R.string.update_fail_file, terminal = true)
                    }
                    val code = archiveVersion(app, dest)
                    if (code <= localCode(app)) {
                        dest.delete()
                        prefs(app).edit().putInt(KEY_OFFERED, update.code).apply()
                        return Dl.Err(R.string.update_fail_old, terminal = true)
                    }
                    if (!trustedApk(app, dest)) {
                        dest.delete()
                        if (update.sha.isNotBlank()) {
                            prefs(app).edit().putInt(KEY_OFFERED, update.code).apply()
                        }
                        return Dl.Err(R.string.update_fail_sign, terminal = true)
                    }
                    return got
                }
                is Dl.Cancelled -> {
                    dest.delete()
                    prefs(app).edit().putInt(KEY_OFFERED, update.code).apply()
                    return got
                }
                is Dl.Err -> {
                    last = got.msg
                    if (got.terminal) return got
                }
            }
        }
        return Dl.Err(last)
    }

    private fun saveUrl(
        url: String,
        token: String,
        dest: File,
        expected: Long,
        sha: String,
        onPct: (Int) -> Unit
    ): Dl {
        val part = File(dest.parentFile, dest.name + ".part")
        var conn: HttpURLConnection? = null
        return try {
            dest.parentFile?.mkdirs()
            part.delete()
            conn = openFollowing(url, token, "application/octet-stream")
            val code = conn.responseCode
            if (code == 404 || code == 401 || code == 403) return Dl.Err(R.string.update_fail_file)
            if (code !in 200..299) return Dl.Err(R.string.update_fail_file)
            val total = when {
                expected > 0 -> expected
                conn.contentLengthLong > 0 -> conn.contentLengthLong
                else -> -1L
            }
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(8192)
                    var read = 0L
                    var lastPct = -1
                    while (!cancelled) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (read + n > MAX_BYTES) {
                            part.delete()
                            return Dl.Err(R.string.update_fail_file, terminal = true)
                        }
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onPct(pct)
                            }
                        }
                    }
                }
            }
            if (cancelled) {
                part.delete()
                return Dl.Cancelled
            }
            if (part.length() < MIN_BYTES) {
                part.delete()
                return Dl.Err(R.string.update_fail_file, terminal = true)
            }
            if (expected > 0 && part.length() < expected) {
                part.delete()
                return Dl.Err(R.string.update_fail_file, terminal = true)
            }
            if (sha.isNotBlank()) {
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(sha, ignoreCase = true)) {
                    part.delete()
                    return Dl.Err(R.string.update_fail_file, terminal = true)
                }
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            Dl.Ok(dest)
        } catch (_: Exception) {
            part.delete()
            Dl.Err(R.string.update_fail_file)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun openFollowing(startUrl: String, token: String, accept: String): HttpURLConnection {
        var url = startUrl
        repeat(5) {
            val conn = open(url, token, accept)
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                throw e
            }
            if (code !in 300..399) return conn
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            if (loc.isNullOrBlank()) throw java.io.IOException("redirect")
            val next = URL(URL(url), loc).toString()
            if (!next.startsWith("https://") || next.length > 8000) throw java.io.IOException("redirect")
            url = next
        }
        throw java.io.IOException("redirect")
    }

    private fun open(url: String, token: String, accept: String): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 60_000
            useCaches = false
            setRequestProperty("User-Agent", "BPDiary")
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Encoding", "identity")
        }
        val host = try {
            URL(url).host.lowercase(Locale.US)
        } catch (_: Exception) {
            ""
        }
        if (token.isNotEmpty() && host == "api.github.com") {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        return conn
    }

    private fun httpText(url: String, token: String, accept: String, max: Int): HttpText {
        val conn = openFollowing(url, token, accept)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buf = CharArray(2048)
                val sb = StringBuilder()
                while (sb.length < max) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n.coerceAtMost(max - sb.length))
                }
                sb.toString()
            }.orEmpty()
            return HttpText(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseNotes(body: String): Ver? {
        val clean = body.replace("\r", "")
        val code = Regex("""(?m)^versionCode:\s*(\d+)\s*$""")
            .find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val name = Regex("""(?m)^versionName:\s*(\S+)\s*$""")
            .find(clean)?.groupValues?.getOrNull(1).orEmpty()
        val sha = Regex("""(?m)^sha256:\s*([0-9a-fA-F]{64})\s*$""")
            .find(clean)?.groupValues?.getOrNull(1).orEmpty()
        return Ver(code, name.ifBlank { code.toString() }, sha)
    }

    private fun parseMeta(text: String): Ver? {
        val o = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        if (!o.has("versionCode")) return null
        val code = o.optInt("versionCode", -1)
        if (code <= 0) return null
        val sha = o.optString("sha256")
        return Ver(code, o.optString("versionName").ifBlank { code.toString() }, sha)
    }

    /**
     * Ответ по метке latest-apk иногда приходит без списка файлов, хотя они уже залиты.
     * Тогда берём assets_url того же релиза — иначе телефон пишет, что обновления нет.
     */
    private fun loadAssets(json: JSONObject, token: String): List<Asset> {
        val inline = parseAssets(json)
        if (inline.isNotEmpty()) return inline
        val url = json.optString("assets_url")
        if (url.isBlank()) return inline
        val http = try {
            httpText(url, token, "application/vnd.github+json", 500_000)
        } catch (_: Exception) {
            return inline
        }
        if (http.code !in 200..299 || http.text.isBlank()) return inline
        val arr = try {
            JSONArray(http.text)
        } catch (_: Exception) {
            return inline
        }
        return parseAssets(JSONObject().put("assets", arr))
    }

    private fun parseAssets(json: JSONObject): List<Asset> {
        val arr = json.optJSONArray("assets") ?: return emptyList()
        val out = ArrayList<Asset>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            out.add(
                Asset(
                    a.optString("name"),
                    a.optString("url"),
                    a.optString("browser_download_url"),
                    a.optLong("size", -1)
                )
            )
        }
        return out
    }

    private fun apkLooksValid(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_BYTES) return false
        val magic = ByteArray(2)
        return try {
            file.inputStream().use { it.read(magic) == 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() }
        } catch (_: Exception) {
            false
        }
    }

    private fun trustedApk(ctx: Context, apk: File): Boolean {
        val info = archiveInfo(ctx, apk) ?: return false
        if (info.packageName != ctx.packageName) return false
        val theirs = certs(info)
        val ours = certs(installedInfo(ctx) ?: return false)
        return theirs.isNotEmpty() && theirs == ours
    }

    private fun archiveVersion(ctx: Context, apk: File): Int {
        val info = archiveInfo(ctx, apk) ?: return -1
        return versionCodeOf(info)
    }

    private fun archiveInfo(ctx: Context, apk: File): PackageInfo? {
        val info = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, pkgFlags()) ?: return null
        info.applicationInfo?.let {
            it.sourceDir = apk.absolutePath
            it.publicSourceDir = apk.absolutePath
        }
        return info
    }

    private fun installedInfo(ctx: Context): PackageInfo? {
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, pkgFlags())
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun pkgFlags(): Int {
        return if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
    }

    @Suppress("DEPRECATION")
    private fun certs(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo
            val modern = signing?.apkContentsSigners
                ?.map { it.toCharsString() }
                ?.toSet()
                .orEmpty()
            if (modern.isNotEmpty()) return modern
        }
        return info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
    }

    private fun versionCodeOf(info: PackageInfo): Int {
        return if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    private fun localCode(ctx: Context): Int {
        val info = installedInfo(ctx) ?: return -1
        return versionCodeOf(info)
    }

    private fun token(ctx: Context): String {
        return prefs(ctx).getString(KEY_TOKEN, "").orEmpty().trim()
    }

    private fun normalizeToken(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("Bearer ", ignoreCase = true)) t = t.substring(7).trim()
        return t.replace(Regex("\\s+"), "")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun flag(ctx: Context, key: String) = prefs(ctx).getBoolean(key, false)

    private fun offered(ctx: Context) = prefs(ctx).getInt(KEY_OFFERED, 0)

    private fun markChecked(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
    }

    private fun postponeAsk(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_ASK_AFTER, System.currentTimeMillis() + ASK_MS).apply()
    }

    private fun shouldAsk(ctx: Context): Boolean {
        return System.currentTimeMillis() >= prefs(ctx).getLong(KEY_ASK_AFTER, 0L)
    }

    private fun apkFile(ctx: Context): File {
        val dir = File(ctx.cacheDir, "updates")
        return File(dir, APK_NAME)
    }

    private fun hasReadyApk(ctx: Context): Boolean {
        val apk = apkFile(ctx)
        return flag(ctx, KEY_READY) && apk.isFile && apk.length() >= MIN_BYTES
    }

    private fun hasMatchingApk(ctx: Context, update: Check.Update): Boolean {
        if (!hasReadyApk(ctx)) return false
        if (prefs(ctx).getInt(KEY_READY_CODE, -1) != update.code) return false
        val len = apkFile(ctx).length()
        return update.asset.size <= 0 || len == update.asset.size
    }

    private fun clearReady(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_READY, false).remove(KEY_READY_CODE).apply()
    }

    private fun cleanupInstalled(ctx: Context) {
        val readyCode = prefs(ctx).getInt(KEY_READY_CODE, 0)
        if (readyCode in 1..localCode(ctx)) {
            apkFile(ctx).delete()
            File(apkFile(ctx).parentFile, APK_NAME + ".part").delete()
            clearReady(ctx)
        }
    }

    private fun openUrl(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast(activity, R.string.update_fail_net)
        }
    }

    private fun toast(ctx: Context, msg: Int) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
    }

    private fun ui(block: (AppCompatActivity) -> Unit) {
        val a = hostRef.get() ?: return
        a.runOnUiThread {
            val cur = hostRef.get() ?: return@runOnUiThread
            if (cur.isFinishing || cur.isDestroyed) return@runOnUiThread
            try {
                block(cur)
            } catch (_: Exception) {
            }
        }
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private data class Asset(val name: String, val apiUrl: String, val browserUrl: String, val size: Long)
    private data class Ver(val code: Int, val name: String, val sha: String)
    private data class HttpText(val code: Int, val text: String)

    private sealed class Check {
        data class Update(val code: Int, val name: String, val asset: Asset, val sha: String) : Check()
        object Current : Check()
        object NeedToken : Check()
        data class Bad(val msg: Int, val askToken: Boolean = false) : Check()
    }

    private sealed class Dl {
        data class Ok(val file: File) : Dl()
        data class Err(val msg: Int, val terminal: Boolean = false) : Dl()
        object Cancelled : Dl()
    }
}
