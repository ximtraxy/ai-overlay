package dev.aioverlay

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.MATCH_PARENT
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
import android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val PREFS = "ui"
const val DEFAULT_PROMPTS = "Spiega\nRisolvi passo passo\nRiassumi\nTraduci in italiano\nFammi un esempio"
fun prompts(p: SharedPreferences) = p.getString("prompts", DEFAULT_PROMPTS)!!.lines().map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Floating "AI" bubble + panel with Gemini / Claude / ChatGPT web apps.
 * Tap bubble = open/close panel. Hold bubble (or ✂) = screenshot, circle the part, it lands in the chat.
 * 📋 = circle part of the screen (e.g. an answer) and put it on the clipboard as an image.
 */
class OverlayService : AccessibilityService() {
    private val sites = listOf(
        "Gemini" to "https://gemini.google.com/app",
        "Claude" to "https://claude.ai/new",
        "ChatGPT" to "https://chatgpt.com/",
    )
    private val micHosts = setOf("gemini.google.com", "claude.ai", "chatgpt.com")
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var bubble: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panel: LinearLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private var panelShown = false
    private var panelAdded = false
    private var shownX = 0
    private lateinit var content: FrameLayout
    private lateinit var quick: HorizontalScrollView
    private lateinit var quickRow: LinearLayout
    private val tabs = mutableListOf<TextView>()
    private val webs = arrayOfNulls<WebView>(3)
    private var popup: WebView? = null
    private var current = 0
    private var pendingShot: Uri? = null

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private val screen get() = wm.currentWindowMetrics.bounds

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        bubble = TextView(this).apply {
            text = "AI"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; alpha = IDLE_ALPHA
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3B5BDB.toInt()) }
            contentDescription = "AI Overlay: tocca per aprire, tieni premuto per lo screenshot"
        }
        val size = 56.dp
        bubbleParams = params(TYPE_ACCESSIBILITY_OVERLAY, size, size, FLAG_NOT_FOCUSABLE).apply {
            x = prefs.getInt("bx", screen.width() - size).coerceIn(0, screen.width() - size)
            y = prefs.getInt("by", screen.height() / 3).coerceIn(0, screen.height() - size)
        }
        bubble.setOnTouchListener(bubbleTouch())
        wm.addView(bubble, bubbleParams)
        instance = this
    }

    /** Quick Settings tile: hide/show the bubble (and the panel) without turning the service off. */
    var hidden = false
        private set

    fun toggleHidden() {
        hidden = !hidden
        if (hidden) hidePanel()
        bubble.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) fitAboveKeyboard()
    }

    /**
     * Floating windows get no keyboard insets (always 0 on the tablet), so find the keyboard window
     * and shrink the panel by the part it covers: the chat box stays just above the keys.
     */
    private fun fitAboveKeyboard() {
        val p = panel ?: return
        val imeTop = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also { w.getBoundsInScreen(it) }.top }
        val loc = IntArray(2).also { p.getLocationOnScreen(it) }
        val covered = if (panelShown && imeTop != null) max(0, loc[1] + p.height - imeTop) else 0
        if (p.paddingBottom != covered) p.setPadding(0, 0, 0, covered)
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        if (panelAdded) runCatching { wm.removeView(panel) }
        runCatching { wm.removeView(bubble) }
        webs.forEach { it?.destroy() }
        super.onDestroy()
    }

    private fun params(type: Int, w: Int, h: Int, flags: Int) =
        WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- bubble ----------

    private fun bubbleTouch(): View.OnTouchListener {
        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var moved = false
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = bubbleParams.x; py = bubbleParams.y; moved = false; bubble.alpha = 1f }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) + abs(e.rawY - sy) > 12.dp) moved = true
                    if (moved) {
                        bubbleParams.x = px + (e.rawX - sx).toInt(); bubbleParams.y = py + (e.rawY - sy).toInt()
                        wm.updateViewLayout(bubble, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    bubble.alpha = IDLE_ALPHA
                    if (moved) {
                        // Dock to the nearest side edge.
                        val w = screen.width()
                        bubbleParams.x = if (bubbleParams.x + bubble.width / 2 < w / 2) 0 else w - bubble.width
                        bubbleParams.y = bubbleParams.y.coerceIn(0, screen.height() - bubble.height)
                        wm.updateViewLayout(bubble, bubbleParams)
                        prefs.edit().putInt("bx", bubbleParams.x).putInt("by", bubbleParams.y).apply()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (e.eventTime - e.downTime > 500) capture(copy = false) else togglePanel()
                    }
                }
            }
            true
        }
    }

    // ---------- panel ----------

    private fun togglePanel() = if (panelShown) hidePanel() else showPanel()

    private fun showPanel() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Concedi il permesso \"mostra sopra altre app\"")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val p = panel ?: buildPanel()
        if (!panelShown) {
            if (panelAdded) panelParams.x = shownX
            val s = screen // keep it on screen after a rotation
            panelParams.width = min(panelParams.width, s.width()); panelParams.height = min(panelParams.height, s.height())
            panelParams.x = panelParams.x.coerceIn(0, s.width() - panelParams.width)
            panelParams.y = panelParams.y.coerceIn(0, s.height() - panelParams.height)
            panelParams.flags = FLAG_NOT_TOUCH_MODAL
            if (panelAdded) wm.updateViewLayout(p, panelParams) else { wm.addView(p, panelParams); panelAdded = true }
            panelShown = true
        }
        select(current)
    }

    private fun hidePanel() {
        if (!panelShown) return
        panelShown = false
        // Park it off-screen instead of removing it: a detached WebView counts as a background tab,
        // and the sites cut off an answer that is still being written.
        shownX = panelParams.x
        panelParams.x = -(panelParams.width + 1000)
        panelParams.flags = FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_NO_LIMITS
        wm.updateViewLayout(panel, panelParams)
        CookieManager.getInstance().flush()
    }

    private fun savePanel() = prefs.edit()
        .putInt("px", panelParams.x).putInt("py", panelParams.y)
        .putInt("pw", panelParams.width).putInt("ph", panelParams.height).apply()

    private fun buildPanel(): LinearLayout {
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val root = object : LinearLayout(themed) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
                if (e.action == KeyEvent.ACTION_UP) {
                    val w = popup ?: webs[current]
                    when {
                        w != null && w.canGoBack() -> w.goBack()
                        popup != null -> closePopup()
                        else -> hidePanel()
                    }
                }
                return true
            }
        }
        root.orientation = LinearLayout.VERTICAL
        root.background = GradientDrawable().apply { setColor(0xFF1E1E1E.toInt()); cornerRadius = 12.dp.toFloat() }
        root.clipToOutline = true

        val bar = LinearLayout(themed).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(4.dp, 4.dp, 4.dp, 4.dp) }
        sites.forEachIndexed { i, (name, _) -> bar.addView(button(name) { select(i) }.also { tabs += it }) }
        val handle = TextView(themed).apply {
            text = "⠿"; setTextColor(Color.GRAY); gravity = Gravity.CENTER
            setOnTouchListener(dragger({ dx, dy -> panelParams.x += dx; panelParams.y += dy; wm.updateViewLayout(root, panelParams) }, ::savePanel))
        }
        bar.addView(handle, LinearLayout.LayoutParams(0, 40.dp, 1f))
        bar.addView(button("✂") { capture(copy = false) })
        bar.addView(button("📋") { capture(copy = true) })
        bar.addView(button("⟳") { webs[current]?.reload() })
        bar.addView(button("—") { hidePanel() })
        root.addView(bar)

        quickRow = LinearLayout(themed).apply { setPadding(4.dp, 0, 4.dp, 4.dp) }
        quick = HorizontalScrollView(themed).apply { addView(quickRow); visibility = View.GONE }
        root.addView(quick)

        content = FrameLayout(themed)
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val resize = TextView(themed).apply {
            text = "◢"; setTextColor(Color.GRAY); gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, 0, 8.dp, 0)
            setOnTouchListener(dragger({ dx, dy ->
                panelParams.width = max(280.dp, panelParams.width + dx)
                panelParams.height = max(300.dp, panelParams.height + dy)
                wm.updateViewLayout(root, panelParams)
            }, ::savePanel))
        }
        root.addView(resize, LinearLayout.LayoutParams(MATCH_PARENT, 24.dp))

        val s = screen
        val w = prefs.getInt("pw", (s.width() * 0.45).toInt())
        val h = prefs.getInt("ph", (s.height() * 0.85).toInt())
        // NOT_TOUCH_MODAL: touches outside the panel still reach your notes app.
        panelParams = params(TYPE_APPLICATION_OVERLAY, w, h, FLAG_NOT_TOUCH_MODAL).apply {
            x = prefs.getInt("px", s.width() - w - 16.dp); y = prefs.getInt("py", (s.height() - h) / 2)
            softInputMode = SOFT_INPUT_ADJUST_RESIZE
        }
        panel = root
        return root
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(panel?.context ?: this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 14f; setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        setBackgroundColor(0xCC333333.toInt())
        setOnClickListener { onClick() }
    }

    private fun dragger(onDrag: (Int, Int) -> Unit, onEnd: () -> Unit): View.OnTouchListener {
        var lx = 0f; var ly = 0f
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.rawX; ly = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - lx).toInt(); val dy = (e.rawY - ly).toInt()
                    lx += dx; ly += dy; onDrag(dx, dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onEnd()
            }
            true
        }
    }

    private fun select(i: Int) {
        if (i != current) closePopup()
        current = i
        val w = web(i)
        if (w.parent == null) content.addView(w, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        webs.forEach { it?.visibility = if (it === w) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { j, t -> t.setBackgroundColor(if (j == i) 0xFF3B5BDB.toInt() else 0xCC333333.toInt()) }
    }

    private fun web(i: Int): WebView = webs[i] ?: newWeb().apply { loadUrl(sites[i].second); webs[i] = this }

    private fun closePopup() {
        popup?.let { content.removeView(it); it.destroy() }
        popup = null
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun newWeb(): WebView = WebView(content.context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        // "Sign in with Google" opens a popup; without these it dead-ends on a blank accounts.google.com page.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        // Google refuses sign-in inside WebViews by user agent; present as plain Chrome.
        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = WebViewClient()
        webChromeClient = chrome
        addJavascriptInterface(Bridge(), "AiOverlay")
        setDownloadListener { url, ua, cd, mime, _ -> download(this, url, ua, cd, mime) }
    }

    private val chrome = object : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, userGesture: Boolean, msg: Message): Boolean {
            closePopup() // shortcut: one popup at a time (sign-in windows don't nest)
            val p = newWeb()
            popup = p
            content.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            (msg.obj as WebView.WebViewTransport).webView = p
            msg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window === popup) closePopup()
        }

        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
            // shortcut: the last screenshot is offered once to the next 📎 tap; a picker toggle would be needed if that annoys.
            pendingShot?.let { cb.onReceiveValue(arrayOf(it)); pendingShot = null; return true }
            hidePanel()
            PickActivity.callback = { uris -> cb.onReceiveValue(uris); showPanel() }
            startActivity(Intent(this@OverlayService, PickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }

        override fun onPermissionRequest(r: PermissionRequest) {
            val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
            // Only the microphone, and only for the three chat sites.
            if (r.origin.host !in micHosts || audio !in r.resources) { r.deny(); return }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                r.grant(arrayOf(audio)); return
            }
            r.deny()
            hidePanel()
            toast("Consenti il microfono, poi ritocca 🎤")
            startActivity(Intent(this@OverlayService, MainActivity::class.java)
                .putExtra(MainActivity.ASK_MIC, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---------- downloads ----------

    private fun download(w: WebView, url: String, ua: String, cd: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, cd, mime)
        when {
            url.startsWith("data:") -> saveDataUrl(url, name)
            url.startsWith("blob:") -> w.evaluateJavascript(BLOB_JS.replace("__URL__", JSONObject.quote(url)).replace("__NAME__", JSONObject.quote(name)), null)
            else -> {
                val req = DownloadManager.Request(Uri.parse(url))
                    .addRequestHeader("User-Agent", ua)
                    .setMimeType(mime)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
                getSystemService(DownloadManager::class.java).enqueue(req)
                toast("Download avviato: $name")
            }
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun save(dataUrl: String, name: String) = saveDataUrl(dataUrl, name)

        @JavascriptInterface fun pasted(how: String) {
            if (how == "none" || how == "unsure") bubble.post { toast("Se lo screen non è in chat, tocca 📎 / +: è già pronto") }
        }
    }

    private fun saveDataUrl(dataUrl: String, name: String) {
        val ok = runCatching {
            val head = dataUrl.substringBefore(",")
            val body = dataUrl.substringAfter(",")
            val bytes = if (head.endsWith(";base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, File(name).name)
                put(MediaStore.Downloads.MIME_TYPE, head.removePrefix("data:").substringBefore(";").ifEmpty { "application/octet-stream" })
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        }.isSuccess
        bubble.post { toast(if (ok) "Salvato in Download: $name" else "Download non riuscito") }
    }

    // ---------- screenshot + lasso ----------

    /** copy = false: circled part goes into the chat. copy = true: it goes to the clipboard as an image. */
    private fun capture(copy: Boolean) {
        val wasShown = panelShown
        if (!copy) hidePanel()
        bubble.visibility = View.INVISIBLE
        bubble.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    val shot = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); s.hardwareBuffer.close()
                    bubble.visibility = View.VISIBLE
                    if (shot == null) { toast("Screenshot non riuscito"); return }
                    // The panel is already in the screenshot; park it so nothing covers the crop screen's buttons.
                    hidePanel()
                    showCrop(shot, onClose = { if (wasShown && !panelShown) showPanel() }) { crop ->
                        if (copy) copyShot(crop) else sendShot(crop)
                    }
                }
                override fun onFailure(code: Int) {
                    bubble.visibility = View.VISIBLE
                    toast("Screenshot non riuscito (errore $code)")
                }
            })
        }, 300) // let the panel/bubble disappear first
    }

    private fun showCrop(shot: Bitmap, onClose: () -> Unit, onCrop: (Bitmap) -> Unit) {
        var open = true
        lateinit var frame: FrameLayout
        fun finish(result: Bitmap?) {
            if (!open) return
            open = false; wm.removeView(frame); result?.let(onCrop); onClose()
        }
        // Focusable, so the back gesture cancels the crop.
        frame = object : FrameLayout(this) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
                if (e.action == KeyEvent.ACTION_UP) finish(null)
                return true
            }
        }
        frame.addView(CropView(this, shot, prefs) { finish(it) })
        val buttons = LinearLayout(this).apply {
            addView(button("▢ Tutto lo schermo") { finish(shot) })
            addView(button("✕ Annulla") { finish(null) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 8.dp })
        }
        frame.addView(buttons, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, 32.dp, 16.dp, 0) })
        wm.addView(frame, params(TYPE_ACCESSIBILITY_OVERLAY, MATCH_PARENT, MATCH_PARENT,
            FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS))
    }

    private fun sendShot(bmp: Bitmap) {
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val f = File(cacheDir, "screenshot.png").apply { writeBytes(bytes) }
        pendingShot = Uri.fromFile(f) // fallback: offered on the next 📎 tap
        showPanel()
        if (!panelShown) return
        val js = PASTE_JS.replace("__B64__", Base64.encodeToString(bytes, Base64.NO_WRAP))
        webs[current]?.evaluateJavascript(js, null)
        showQuick()
    }

    private fun copyShot(bmp: Bitmap) {
        val dir = File(cacheDir, ShotProvider.DIR).apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, "ritaglio_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = Uri.parse("content://${ShotProvider.AUTHORITY}/${f.name}")
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(contentResolver, "Ritaglio", uri))
        toast("Copiato come immagine: incollalo negli appunti")
    }

    // ---------- quick questions ----------

    private fun showQuick() {
        quickRow.removeAllViews()
        (prompts(prefs) + "✕").forEach { p ->
            val chip = button(p) {
                quick.visibility = View.GONE
                if (p != "✕") sendPrompt(p)
            }.apply { background = GradientDrawable().apply { setColor(0xFF3B5BDB.toInt()); cornerRadius = 16.dp.toFloat() } }
            quickRow.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = 6.dp })
        }
        quick.visibility = View.VISIBLE
    }

    private fun sendPrompt(text: String) {
        webs[current]?.evaluateJavascript(SEND_JS.replace("__TEXT__", JSONObject.quote(text))) { r ->
            if (r.contains("none")) toast("Non trovo la casella di testo della chat")
        }
    }

    companion object {
        /** The running service, for the Quick Settings tile; null when Accessibility has it off. */
        var instance: OverlayService? = null
            private set

        private const val IDLE_ALPHA = 0.6f

        private const val EDITOR = """['#prompt-textarea', 'div.ProseMirror[contenteditable=true]', 'rich-textarea [contenteditable=true]',
            '[contenteditable=true]', 'textarea'].map(s => document.querySelector(s)).find(Boolean)"""

        /**
         * Pastes the image into the chat box like Ctrl+V. Some sites (ChatGPT) ignore a simulated paste,
         * so if no image preview shows up we hand the file to the page's upload input instead.
         * Result goes to Bridge.pasted, because evaluateJavascript can't wait for the async check.
         */
        private val PASTE_JS = """
            (async function(){
              const b = atob('__B64__'); const u = new Uint8Array(b.length);
              for (let i = 0; i < b.length; i++) u[i] = b.charCodeAt(i);
              const dt = new DataTransfer();
              dt.items.add(new File([u], 'screenshot.png', {type: 'image/png'}));
              const previews = () => [...document.querySelectorAll('img')].filter(i => /^(blob|data):/.test(i.src)).length;
              const before = previews();
              const ed = $EDITOR;
              if (ed) {
                ed.focus();
                ed.dispatchEvent(new ClipboardEvent('paste', {clipboardData: dt, bubbles: true, cancelable: true}));
                await new Promise(r => setTimeout(r, 1500));
                if (previews() > before) return AiOverlay.pasted('paste');
              }
              const inp = [...document.querySelectorAll('input[type=file]')].find(i => /image/.test(i.accept))
                       || document.querySelector('input[type=file]');
              if (inp) { inp.files = dt.files; inp.dispatchEvent(new Event('change', {bubbles: true})); return AiOverlay.pasted('file'); }
              AiOverlay.pasted(ed ? 'unsure' : 'none');
            })()
        """.trimIndent()

        /**
         * Types the question and presses send once. It only waits while send is disabled (image still uploading).
         * Never presses twice and skips anything labelled stop: once an answer starts, the send button turns into Stop.
         */
        private val SEND_JS = """
            (function(t){
              const findEd = () => $EDITOR;
              const ed = findEd();
              if (!ed) return 'none';
              ed.focus();
              if (ed.tagName === 'TEXTAREA') {
                Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(ed, ed.value + t);
                ed.dispatchEvent(new Event('input', {bubbles: true}));
              } else document.execCommand('insertText', false, t);
              const SEL = '[data-testid="send-button"], button[aria-label*="send" i], button[aria-label*="invia" i], button.send-button';
              const STOP = /stop|interr|annulla|cancel/i;
              const isStop = b => STOP.test((b.getAttribute('aria-label') || '') + ' ' + (b.dataset.testid || '') + ' ' + b.className);
              const findSend = from => {
                for (let c = from, k = 0; c && k < 8; c = c.parentElement, k++) {
                  const b = [...c.querySelectorAll(SEL)].find(b => !isStop(b));
                  if (b) return b;
                }
                return null;
              };
              let n = 0;
              const tick = () => {
                const e = findEd(); // re-find: the box can be rebuilt
                if (!e || !((e.value ?? e.innerText) || '').trim() || n++ > 40) return; // empty box = already sent
                const btn = findSend(e);
                if (btn && (btn.disabled || btn.getAttribute('aria-disabled') === 'true')) { setTimeout(tick, 700); return; }
                if (btn) btn.click();
                else e.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true}));
              };
              setTimeout(tick, 800);
              return 'ok';
            })(__TEXT__)
        """.trimIndent()

        /** blob: downloads only exist inside the page, so read them there and hand them to Bridge.save. */
        private val BLOB_JS = """
            fetch(__URL__).then(r => r.blob()).then(b => {
              const fr = new FileReader(); fr.onload = () => AiOverlay.save(fr.result, __NAME__); fr.readAsDataURL(b);
            })
        """.trimIndent()
    }
}

/** Lasso bounds in bitmap px -> padded box [l, t, r, b] clamped to the bitmap, or null if it misses the image. */
fun cropBox(l: Float, t: Float, r: Float, b: Float, w: Int, h: Int, pad: Int): IntArray? {
    val box = intArrayOf(max(0, (l - pad).toInt()), max(0, (t - pad).toInt()), min(w, (r + pad).toInt()), min(h, (b + pad).toInt()))
    return if (box[2] - box[0] < 8 || box[3] - box[1] < 8) null else box
}

/** Full-screen view of the screenshot: draw around what you want. A tap sends the whole screen. */
@SuppressLint("ViewConstructor")
class CropView(ctx: Context, private val shot: Bitmap, private val prefs: SharedPreferences, private val onDone: (Bitmap) -> Unit) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val path = Path()
    private val toScreen = Matrix()
    private var pointer = -1
    private var trackingPen = false
    private var fingerIgnored = false
    // Fingers are ignored only once a pen has been used here, so a resting palm can't start a circle
    // but a finger still works for anyone who never picked up the pen.
    private val penOnly get() = prefs.getBoolean("penOnly", true) && prefs.getBoolean("penSeen", false)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * density; color = Color.RED }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20 * density; setShadowLayer(6f, 0f, 0f, Color.BLACK) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        toScreen.setRectToRect(RectF(0f, 0f, shot.width.toFloat(), shot.height.toFloat()),
            RectF(0f, 0f, w.toFloat(), h.toFloat()), Matrix.ScaleToFit.CENTER)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        c.drawBitmap(shot, toScreen, null)
        c.drawColor(0x33000000) // slight dim = "selection mode"
        c.drawPath(path, stroke)
        if (path.isEmpty) c.drawText(when {
            fingerIgnored -> "Usa la penna: il dito è ignorato (si cambia nell'app AI Overlay)"
            penOnly -> "Cerchia con la penna cosa vuoi"
            else -> "Cerchia cosa vuoi (penna o dito)"
        }, 24 * density, 56 * density, hint)
    }

    private fun isPen(e: MotionEvent, i: Int) = e.getToolType(i).let { it == MotionEvent.TOOL_TYPE_STYLUS || it == MotionEvent.TOOL_TYPE_ERASER }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pen = isPen(e, i)
                if (pen && !prefs.getBoolean("penSeen", false)) prefs.edit().putBoolean("penSeen", true).apply()
                // A pen landing after the palm takes over the stroke.
                if ((pointer == -1 && (pen || !penOnly)) || (pen && !trackingPen)) {
                    pointer = e.getPointerId(i); trackingPen = pen
                    path.reset(); path.moveTo(e.getX(i), e.getY(i))
                } else if (!pen && pointer == -1) fingerIgnored = true
            }
            MotionEvent.ACTION_MOVE -> e.findPointerIndex(pointer).takeIf { it >= 0 }?.let { path.lineTo(e.getX(it), e.getY(it)) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (e.getPointerId(i) == pointer) { pointer = -1; onDone(cropped()) }
            MotionEvent.ACTION_CANCEL -> { pointer = -1; path.reset() }
        }
        invalidate()
        return true
    }

    private fun cropped(): Bitmap {
        val r = RectF().also { path.computeBounds(it, true) }
        if (r.width() < 20 * density && r.height() < 20 * density) return shot // a tap
        Matrix().also { toScreen.invert(it) }.mapRect(r)
        val box = cropBox(r.left, r.top, r.right, r.bottom, shot.width, shot.height, (12 * density).toInt()) ?: return shot
        return Bitmap.createBitmap(shot, box[0], box[1], box[2] - box[0], box[3] - box[1])
    }
}
