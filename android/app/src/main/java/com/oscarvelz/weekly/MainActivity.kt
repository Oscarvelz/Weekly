package com.oscarvelz.weekly

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Weekly for Android: a thin shell around the Weekly web app, loaded live from GitHub Pages so every web update
 * arrives on its own. The caseload lives in this app's own private WebView storage, so clearing Chrome's site data
 * can no longer touch it. Backups are written into Documents/Weekly through the WeeklyAndroid bridge below
 * (MediaStore, no "All files access"), and the page writes a dated automatic copy there as you work.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val home = "https://oscarvelz.github.io/Weekly/"
    private val prefs by lazy { getSharedPreferences("weekly-shell", MODE_PRIVATE) }
    private var barColor = Color.parseColor("#14120e")
    private var barDark = true
    private val io = Executors.newSingleThreadExecutor()
    private val store by lazy { Store() }
    private var lastError = 0L
    /** The page's microphone request, waiting on Android's own permission dialog. */
    private var micAsk: PermissionRequest? = null
    private val crashes = ArrayList<Long>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        barColor = prefs.getInt("barColor", barColor)
        barDark = prefs.getBoolean("barDark", true)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        WebView.setWebContentsDebuggingEnabled(true)
        root = FrameLayout(this)
        web = makeWeb()
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Edge to edge: the page sits between the bars, and the bars show the root's colour (the theme's background).
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        setContentView(root)
        applyBars()
        if (state != null) web.restoreState(state) else web.loadUrl(home)
        checkUpdate()
    }


    /** Builds the page's WebView. Also used to start a fresh one if the page's process is ever lost. */
    private fun makeWeb(): WebView {
        val w = WebView(this)
        w.setBackgroundColor(barColor)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false; allowFileAccess = false; allowContentAccess = true
            textZoom = 100
            userAgentString = "$userAgentString WeeklyAndroid/${BuildConfig.VERSION_NAME}"
        }
        ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
            cacheMode = WebSettings.LOAD_DEFAULT; allowContentAccess = true; allowFileAccess = false
        }
        w.addJavascriptInterface(Bridge(), "WeeklyAndroid")
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.host == "oscarvelz.github.io" && (u.path ?: "").startsWith("/Weekly")) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }; return true
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (!r.isForMainFrame) return
                val bg = String.format("#%06X", 0xFFFFFF and barColor)
                v.loadDataWithBaseURL(home, "<html><body style=\"background:$bg;color:#eee;font:18px sans-serif;padding:32px\">" +
                    "<p>Weekly could not open. It needs the internet the first time; after that it opens offline.</p>" +
                    "<p><a style=\"color:#e0b45a\" href=\"$home\">Try again</a></p></body></html>", "text/html", "utf-8", null)
            }
            // If the page's process dies (out of memory, say), Android would close the whole app unless the app
            // says it handled it. Start a fresh page instead.
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                recoverWeb(v)
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                val intent = p.createIntent()
                if (p.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                return try { startActivityForResult(intent, 7); true } catch (e: Exception) { fileCb = null; false }
            }
            // The page asks for the microphone (voice typing): pass it Android's own permission first.
            override fun onPermissionRequest(r: PermissionRequest) {
                val mic = r.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!mic || r.origin?.host != "oscarvelz.github.io") { runCatching { r.deny() }; return }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    runCatching { r.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) }
                    return
                }
                micAsk?.let { runCatching { it.deny() } }
                micAsk = r
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8)
            }
            override fun onPermissionRequestCanceled(r: PermissionRequest) {
                if (micAsk === r) micAsk = null
            }
        }
        w.setDownloadListener { url, _, _, _, _ ->
            // blob: and data: downloads are caught by the page and saved into Documents/Weekly; real links open outside.
            if (url.startsWith("http")) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        return w
    }

    /** A fresh page after the old one's process was lost, so the app stays open instead of closing. */
    private fun recoverWeb(dead: WebView) {
        if (dead !== web) { runCatching { root.removeView(dead); dead.destroy() }; return }
        micAsk?.let { runCatching { it.deny() } }
        micAsk = null
        root.removeView(dead)
        runCatching { dead.destroy() }
        val now = SystemClock.elapsedRealtime()
        crashes.add(now)
        crashes.removeAll { now - it > 60_000 }
        web = makeWeb()
        root.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyBars()
        if (crashes.size >= 3) {
            val bg = String.format("#%06X", 0xFFFFFF and barColor)
            web.loadDataWithBaseURL(home, "<html><body style=\"background:$bg;color:#eee;font:18px sans-serif;padding:32px\">" +
                "<p>Weekly had to reopen its page several times in a minute, so it is waiting here.</p>" +
                "<p><a style=\"color:#e0b45a\" href=\"$home\">Open Weekly</a></p></body></html>", "text/html", "utf-8", null)
        } else {
            web.loadUrl(home)
            Toast.makeText(this, "Weekly reopened its page after it stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(rq: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rq, perms, results)
        if (rq != 8) return
        val r = micAsk ?: return
        micAsk = null
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            runCatching { r.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) }
        } else {
            runCatching { r.deny() }
            Toast.makeText(this, "Voice typing needs the microphone. Allow it in Android Settings › Apps › Weekly › Permissions.", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyBars() {
        root.setBackgroundColor(barColor)
        web.setBackgroundColor(barColor)
        window.decorView.setBackgroundColor(barColor)
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (barDark) 0 else light, light)
    }

    private fun parseColor(s: String): Int? {
        val t = s.trim()
        return runCatching {
            when {
                t.startsWith("#") && t.length == 4 -> Color.parseColor("#" + t.substring(1).map { "$it$it" }.joinToString(""))
                t.startsWith("#") -> Color.parseColor(t)
                t.startsWith("rgb") -> {
                    val n = Regex("[\\d.]+").findAll(t).map { it.value.toFloat() }.toList()
                    Color.rgb(n[0].toInt(), n[1].toInt(), n[2].toInt())
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun dispatch(js: String) = runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(js, null) }

    private fun report(e: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastError < 10000) return
        lastError = now
        val msg = "The phone could not write into Documents/Weekly" + (e.message?.let { ": $it" } ?: ".")
        dispatch("dispatchEvent(new CustomEvent('weeklyandroid-error',{detail:${JSONObject.quote(msg)}}))")
    }

    private fun queue(job: () -> Unit): String {
        io.execute { try { job() } catch (e: Throwable) { report(e) } }
        return "ok"
    }

    private fun <T> waitFor(job: () -> T): T = io.submit(Callable { job() }).get(60, TimeUnit.SECONDS)

    private fun clean(path: String): String {
        val parts = path.split('/')
        require(parts.isNotEmpty() && parts.none { it.isBlank() || it == "." || it == ".." }) { "bad path" }
        return parts.joinToString("/")
    }

    /** Files Weekly keeps in Documents/Weekly. Only ever touched on the io thread. */
    private inner class Store {
        val base = "Documents/Weekly/"
        val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        inner class Entry(val id: Long, var size: Long, var modified: Long)
        private var index: HashMap<String, Entry>? = null
        private val hashes = HashMap<String, String>()
        private var sweepDir: String? = null
        private val seen = HashSet<String>()
        private val alias = getSharedPreferences("weekly-files", MODE_PRIVATE)

        fun uri(id: Long): Uri = ContentUris.withAppendedId(collection, id)

        fun entries(): HashMap<String, Entry> {
            index?.let { return it }
            val map = HashMap<String, Entry>()
            val byId = HashMap<Long, Entry>()
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
            contentResolver.query(collection, proj, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$base%"), null)?.use { c ->
                while (c.moveToNext()) {
                    val rel = c.getString(1) ?: continue
                    val name = c.getString(2) ?: continue
                    val e = Entry(c.getLong(0), c.getLong(3), c.getLong(4) * 1000)
                    map[rel.removePrefix(base) + name] = e
                    byId[e.id] = e
                }
            }
            // A file the phone had to rename on creation (for example "name (1).json") is still found by the name asked for.
            for ((path, v) in alias.all) {
                val id = (v as? Long) ?: continue
                byId[id]?.let { map[path] = it }
            }
            index = map
            return map
        }

        private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        private fun mime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "json" -> "application/json"; "txt" -> "text/plain"; "csv" -> "text/csv"; "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }

        private fun create(path: String): Uri {
            val dir = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, base + if (dir.isEmpty()) "" else "$dir/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val u = contentResolver.insert(collection, v) ?: throw IllegalStateException("could not create $name")
            contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && c.getString(0) != name) alias.edit().putLong(path, ContentUris.parseId(u)).apply()
            }
            return u
        }

        private fun done(u: Uri) {
            contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }

        /** Writes the whole file, skipping it when the phone already has exactly these bytes. */
        fun write(path: String, bytes: ByteArray) {
            if (sweepDir != null) seen.add(path)
            val map = entries()
            val e = map[path]
            val sum = sha(bytes)
            if (e != null && hashes[path] == null && e.size == bytes.size.toLong()) {
                runCatching { contentResolver.openInputStream(uri(e.id))?.use { it.readBytes() } }.getOrNull()?.let { hashes[path] = sha(it) }
            }
            if (e != null && hashes[path] == sum) return
            val fresh = e == null
            val u = if (e != null) uri(e.id) else create(path)
            try {
                contentResolver.openOutputStream(u, "wt")!!.use { it.write(bytes) }
            } catch (x: Exception) {
                if (fresh) runCatching { contentResolver.delete(u, null, null) }
                if (!fresh) { map.remove(path); hashes.remove(path) }
                throw x
            }
            if (fresh) done(u)
            hashes[path] = sum
            map[path] = Entry(ContentUris.parseId(u), bytes.size.toLong(), System.currentTimeMillis())
        }

        fun writeChunk(path: String, bytes: ByteArray, first: Boolean) {
            if (sweepDir != null) seen.add(path)
            hashes.remove(path)
            val map = entries()
            val e = map[path]
            if (first || e == null) {
                val fresh = e == null
                val u = if (e != null) uri(e.id) else create(path)
                contentResolver.openOutputStream(u, "wt")!!.use { it.write(bytes) }
                if (fresh) done(u)
                map[path] = Entry(ContentUris.parseId(u), bytes.size.toLong(), System.currentTimeMillis())
            } else {
                contentResolver.openOutputStream(uri(e.id), "wa")!!.use { it.write(bytes) }
                e.size += bytes.size; e.modified = System.currentTimeMillis()
            }
        }

        fun remove(path: String) {
            val map = entries()
            val e = map.remove(path) ?: return
            hashes.remove(path)
            alias.edit().remove(path).apply()
            runCatching { contentResolver.delete(uri(e.id), null, null) }
        }

        fun sweepStart(dir: String) { sweepDir = dir; seen.clear() }

        /** Removes the files under the swept folder that this save did not write again (a deleted week, say). */
        fun sweepEnd() {
            val dir = sweepDir ?: return
            sweepDir = null
            val stale = entries().keys.filter { it.startsWith("$dir/") && it !in seen }
            seen.clear()
            for (p in stale) remove(p)
        }

        fun autoBackup(name: String, bytes: ByteArray, keep: Int) {
            write(name, bytes)
            val pattern = Regex("^Weekly-auto-\\d{4}-\\d{2}-\\d{2}\\.weekly$")
            val autos = entries().keys.filter { pattern.matches(it) }.sortedDescending()
            for (old in autos.drop(maxOf(1, keep))) remove(old)
        }

        fun list(): String {
            val a = JSONArray()
            for ((p, e) in entries()) a.put(JSONObject().put("p", p).put("s", e.size).put("m", e.modified))
            return a.toString()
        }

        fun readChunk(path: String, offset: Long, length: Int): String {
            val e = entries()[path] ?: return ""
            contentResolver.openInputStream(uri(e.id))?.use { inp ->
                var skip = offset
                while (skip > 0) { val n = inp.skip(skip); if (n <= 0) break; skip -= n }
                if (skip > 0) return ""
                val buf = ByteArray(length)
                var got = 0
                while (got < length) { val n = inp.read(buf, got, length - got); if (n < 0) break; got += n }
                if (got == 0) return ""
                return Base64.encodeToString(buf, 0, got, Base64.NO_WRAP)
            }
            return ""
        }
    }

    inner class Bridge {
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun folder(): String = "Documents/Weekly"
        @JavascriptInterface fun write(path: String, text: String): String {
            val p = runCatching { clean(path) }.getOrElse { return "That file name cannot be used." }
            val bytes = text.toByteArray(Charsets.UTF_8)
            return queue { store.write(p, bytes) }
        }
        @JavascriptInterface fun writeChunk(path: String, b64: String, first: Boolean): String {
            val p = runCatching { clean(path) }.getOrElse { return "That file name cannot be used." }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            return queue { store.writeChunk(p, bytes, first) }
        }
        @JavascriptInterface fun remove(path: String): String {
            val p = runCatching { clean(path) }.getOrElse { return "ok" }
            return queue { store.remove(p) }
        }
        @JavascriptInterface fun sweepStart(dir: String): String {
            val p = runCatching { clean(dir) }.getOrElse { return "ok" }
            return queue { store.sweepStart(p) }
        }
        @JavascriptInterface fun sweepEnd(): String = queue { store.sweepEnd() }
        @JavascriptInterface fun autoBackup(name: String, text: String, keep: Int): String {
            val p = runCatching { clean(name) }.getOrElse { return "That file name cannot be used." }
            val bytes = text.toByteArray(Charsets.UTF_8)
            return queue { store.autoBackup(p, bytes, keep) }
        }
        @JavascriptInterface fun list(): String = runCatching { waitFor { store.list() } }.getOrDefault("[]")
        @JavascriptInterface fun readChunk(path: String, offset: Double, length: Int): String {
            val p = runCatching { clean(path) }.getOrElse { return "" }
            return runCatching { waitFor { store.readChunk(p, offset.toLong(), length) } }.getOrDefault("")
        }
        @JavascriptInterface fun setBars(color: String, dark: Boolean) {
            val c = parseColor(color) ?: return
            runOnUiThread {
                barColor = c; barDark = dark
                prefs.edit().putInt("barColor", c).putBoolean("barDark", dark).apply()
                applyBars()
            }
        }
        @JavascriptInterface fun print() = runOnUiThread { printPage() }
    }

    /** Android printing for window.print(); the page hears 'afterprint' when the print screen closes, as in Chrome. */
    private fun printPage() {
        val pm = getSystemService(PRINT_SERVICE) as PrintManager
        val title = web.title?.takeIf { it.isNotBlank() } ?: "Weekly"
        val inner = web.createPrintDocumentAdapter(title)
        val adapter = object : PrintDocumentAdapter() {
            override fun onStart() = inner.onStart()
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?,
                                  cb: PrintDocumentAdapter.LayoutResultCallback?, extras: Bundle?) = inner.onLayout(old, new, cancel, cb, extras)
            override fun onWrite(pages: Array<out PageRange>?, out: ParcelFileDescriptor?, cancel: CancellationSignal?,
                                 cb: PrintDocumentAdapter.WriteResultCallback?) = inner.onWrite(pages, out, cancel, cb)
            override fun onFinish() {
                inner.onFinish()
                dispatch("dispatchEvent(new Event('afterprint'))")
            }
        }
        try { pm.print(title, adapter, PrintAttributes.Builder().build()) }
        catch (e: Exception) { dispatch("dispatchEvent(new Event('afterprint'))") }
    }

    /** The shell updates itself: if GitHub has a newer android-N build, offer it once per launch and install it in place. */
    private fun checkUpdate() = Thread {
        try {
            val c = URL("https://api.github.com/repos/Oscarvelz/Weekly/releases?per_page=30").openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json"); c.connectTimeout = 8000; c.readTimeout = 8000
            val list = JSONArray(c.inputStream.bufferedReader().readText())
            var best = BuildConfig.VERSION_CODE
            var url: String? = null
            for (i in 0 until list.length()) {
                val r = list.getJSONObject(i)
                val n = r.getString("tag_name").takeIf { it.startsWith("android-") }?.substringAfter("android-")?.toIntOrNull() ?: continue
                if (n <= best) continue
                val assets = r.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") } ?: continue
                best = n; url = apk.getString("browser_download_url")
            }
            val found = url ?: return@Thread
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Weekly update ready")
                    .setMessage("A newer Weekly app is ready. Install it now? Your caseload and your backups stay exactly as they are.")
                    .setPositiveButton("Install") { _, _ -> install(found) }.setNegativeButton("Later", null).show()
            }
        } catch (_: Exception) {}
    }.start()

    private fun install(url: String) {
        Toast.makeText(this, "Downloading the update…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val pi = packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                pi.openSession(id).use { s ->
                    val c = URL(url).openConnection() as HttpURLConnection; c.instanceFollowRedirects = true
                    c.inputStream.use { inp -> s.openWrite("weekly.apk", 0, -1).use { out -> inp.copyTo(out); s.fsync(out) } }
                    val back = Intent(this, MainActivity::class.java).setAction("weekly.INSTALL")
                    val pend = PendingIntent.getActivity(this, 1, back, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    s.commit(pend.intentSender)
                }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != "weekly.INSTALL") return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(this, "Update didn’t install: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rc: Int, d: Intent?) {
        if (rq != 7) { super.onActivityResult(rq, rc, d); return }
        val cd = d?.clipData
        val res: Array<Uri>? = if (rc != RESULT_OK) null
            else if (cd != null) Array(cd.itemCount) { cd.getItemAt(it).uri }
            else WebChromeClient.FileChooserParams.parseResult(rc, d)
        fileCb?.onReceiveValue(res); fileCb = null
    }

    override fun onPause() {
        super.onPause()
        // The page saves the caseload and makes its automatic copy right away when the app is put away.
        dispatch("dispatchEvent(new Event('weeklyandroid-pause'))")
        CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }

    private var lastBack = 0L
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) { web.goBack(); return }
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) finish()
        else { lastBack = now; Toast.makeText(this, "Press back again to close Weekly", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        io.shutdown()
        runCatching { io.awaitTermination(5, TimeUnit.SECONDS) }
        super.onDestroy()
    }
}
