package com.seoul2033wiki

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isCapturing = false

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "overlay_channel"

        // 나무위키 다크모드 CSS 인젝션
        private const val DARK_MODE_JS = """
            (function() {
                var style = document.createElement('style');
                style.innerHTML = `
                    html { filter: invert(1) hue-rotate(180deg) !important; }
                    img, video, canvas, svg image { filter: invert(1) hue-rotate(180deg) !important; }
                `;
                document.head.appendChild(style);
            })();
        """
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        scope.launch { WikiUrlResolver.loadStoryLists(applicationContext) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView != null) {
            handler.post {
                Toast.makeText(this, getString(R.string.overlay_already_running), Toast.LENGTH_LONG).show()
            }
            return START_NOT_STICKY
        }
        if (!Seoul2033AccessibilityService.isAlive(this)) {
            handler.post {
                Toast.makeText(
                    this,
                    "접근성 서비스가 연결되지 않았습니다.\n설정 → 접근성 → 서울2033 리더를 활성화해주세요.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        setupOverlay()
        return START_NOT_STICKY
    }

    // ── 드래그 종료 영역 오버레이 ────────────────────────────────────────────
    private var dropZoneView: View? = null

    private fun showDropZone() {
        if (dropZoneView != null) return
        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()
        val view = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC333333.toInt())
                setStroke((2 * density).toInt(), 0xFFAAAAAA.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (72 * density).toInt()
        }
        dropZoneView = view
        try { windowManager.addView(view, params) } catch (_: Exception) {}
    }

    private fun hideDropZone() {
        dropZoneView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        dropZoneView = null
    }

    private fun isInDropZone(rawX: Float, rawY: Float): Boolean {
        val v = dropZoneView ?: return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val centerX = loc[0] + v.width / 2f
        val centerY = loc[1] + v.height / 2f
        val radius = v.width / 2f * 2.0f
        val dx = rawX - centerX
        val dy = rawY - centerY
        return dx * dx + dy * dy <= radius * radius
    }

    private fun setupOverlay() {
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {}; overlayView = null }

        @android.annotation.SuppressLint("InflateParams")
        val newView = LayoutInflater.from(this).inflate(R.layout.overlay_button, null, false)
        overlayView = newView

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 80 }

        val btnText = newView.findViewById<TextView>(R.id.overlayBtnText)
        btnText.setTextColor(0xFFF5F0C8.toInt())

        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        var isDragging = false

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        newView.isClickable = true
        newView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(newView, params)
                    val moved = kotlin.math.abs(event.rawX - initialTouchX) > 10 ||
                            kotlin.math.abs(event.rawY - initialTouchY) > 10
                    if (moved && !isDragging) {
                        isDragging = true
                        showDropZone()
                    }
                    if (isDragging) {
                        val inZone = isInDropZone(event.rawX, event.rawY)
                        if (inZone) {
                            btnText.setBackgroundResource(R.drawable.overlay_btn_bg_active)
                            btnText.setTextColor(0xFFFF4444.toInt())
                            (dropZoneView as? TextView)?.apply {
                                setTextColor(0xFFFF4444.toInt())
                                (background as? GradientDrawable)?.apply {
                                    setColor(0xCC3D0000.toInt())
                                    setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFFF4444.toInt())
                                }
                            }
                        } else {
                            btnText.setBackgroundResource(R.drawable.overlay_btn_bg)
                            btnText.setTextColor(0xFFF5F0C8.toInt())
                            (dropZoneView as? TextView)?.apply {
                                setTextColor(0xFFFFFFFF.toInt())
                                (background as? GradientDrawable)?.apply {
                                    setColor(0xCC333333.toInt())
                                    setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFAAAAAA.toInt())
                                }
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val inZone = isInDropZone(event.rawX, event.rawY)
                        hideDropZone()
                        isDragging = false
                        if (inZone) {
                            stopSelf()
                        } else {
                            btnText.setBackgroundResource(R.drawable.overlay_btn_bg)
                            btnText.setTextColor(0xFFF5F0C8.toInt())
                        }
                    } else {
                        hideDropZone()
                        v.performClick()
                        if (isCapturing) {
                            Toast.makeText(this, "인식 중입니다. 잠깐만요...", Toast.LENGTH_SHORT).show()
                        } else if (!Seoul2033AccessibilityService.isAlive(this)) {
                            btnText.text = "!"
                            btnText.setBackgroundResource(R.drawable.overlay_btn_bg_active)
                            btnText.setTextColor(0xFFFF4444.toInt())
                            Toast.makeText(
                                this,
                                "접근성 서비스가 끊겼습니다.\n설정 → 접근성 → 서울2033 리더를 재활성화해주세요.",
                                Toast.LENGTH_LONG
                            ).show()
                            handler.postDelayed({
                                btnText.text = getString(R.string.btn_read)
                                btnText.setBackgroundResource(R.drawable.overlay_btn_bg)
                                btnText.setTextColor(0xFFF5F0C8.toInt())
                            }, 3000)
                        } else {
                            isCapturing = true
                            btnText.text = getString(R.string.btn_recognizing)
                            btnText.setBackgroundResource(R.drawable.overlay_btn_bg_active)
                            btnText.setTextColor(0xFFFFFFFF.toInt())
                            btnText.post {
                                scope.launch(Dispatchers.Default) {
                                    readAndResolve {
                                        btnText.text = getString(R.string.btn_read)
                                        btnText.setBackgroundResource(R.drawable.overlay_btn_bg)
                                        btnText.setTextColor(0xFFF5F0C8.toInt())
                                        isCapturing = false
                                    }
                                }
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(newView, params)
            handler.post { Toast.makeText(this, "Read 버튼이 화면 상단에 추가됐어요!", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            handler.post { Toast.makeText(this, "버튼 추가 실패: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    // ── 텍스트 수집 및 매칭 ────────────────────────────────────────────────────
    private suspend fun readAndResolve(onDone: () -> Unit) {
        val accessibility = if (Seoul2033AccessibilityService.isAlive(this@OverlayService))
            Seoul2033AccessibilityService.instance else null
        if (accessibility == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@OverlayService,
                    "접근성 서비스가 연결되지 않았습니다.\n설정 → 접근성 → 서울2033 리더를 활성화해주세요.",
                    Toast.LENGTH_LONG
                ).show()
                onDone()
            }
            return
        }
        val rawText = withContext(Dispatchers.Main) {
            accessibility.extractGameText()
        }
        if (rawText.isNullOrBlank()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@OverlayService,
                    "서울2033 화면 텍스트를 읽지 못했습니다.\n게임이 실행 중인지 확인해주세요.",
                    Toast.LENGTH_SHORT
                ).show()
                onDone()
            }
            return
        }
        val lineCount = rawText.lines().count { it.isNotBlank() }
        Log.d("Seoul2033Wiki", "수집 텍스트 (${lineCount}줄):\n$rawText")

        val entry = withContext(Dispatchers.Default) {
            val bottomEntry = WikiUrlResolver.resolve(rawText, applicationContext)
            when {
                bottomEntry != null
                        && bottomEntry.type != EntryType.BASIC
                        && bottomEntry.type != EntryType.MAIN_STORY
                        && bottomEntry.type != EntryType.EXPANSION -> {
                    bottomEntry
                }
                bottomEntry != null && bottomEntry.type == EntryType.EXPANSION -> {
                    Log.d("Seoul2033Wiki", "확장팩 인식: '${bottomEntry.title}' → 섹션 탐지")
                    ExpansionEncounterResolver.resolve(rawText, bottomEntry.title, applicationContext)
                        ?: run {
                            Log.d("Seoul2033Wiki", "확장팩 섹션 매칭 실패 → 인덱스 페이지로 폴백: '${bottomEntry.title}'")
                            ResolvedEntry(
                                title = bottomEntry.title,
                                pageNum = "",
                                type = EntryType.EXPANSION,
                                url = ExpansionEncounterResolver.buildIndexUrl(bottomEntry.title)
                            )
                        }
                }
                else -> {
                    Log.d("Seoul2033Wiki", "풀체인 resolver 실행 (확장팩 제외)")
                    BasicEncounterResolver.resolve(rawText)
                        ?: ActiveEncounterResolver.resolve(rawText)
                        ?: HardModeEncounterResolver.resolve(rawText)
                        ?: MainStoryEncounterResolver.resolve(rawText)
                }
            }
        }
        withContext(Dispatchers.Main) {
            if (entry != null) {
                showResultPopup(entry)
            } else {
                Toast.makeText(
                    this@OverlayService,
                    "인식된 텍스트:\n${rawText.take(80)}\n\n패턴 매칭 실패",
                    Toast.LENGTH_LONG
                ).show()
            }
            onDone()
        }
    }

    // ── 결과 팝업 ────────────────────────────────────────────────────────────
    private var popupView: View? = null

    private fun getHint(entry: ResolvedEntry): String? = when (entry.type) {
        EntryType.EXPANSION_ENCOUNTER -> {
            val dash = entry.title.indexOf(" - ")
            if (dash >= 0) ExpansionEncounterResolver.hint(
                entry.title.substring(0, dash),
                entry.title.substring(dash + 3)
            ) else null
        }
        EntryType.BASIC_ENCOUNTER     -> BasicEncounterResolver.hint(entry.title)
        EntryType.ACTIVE_ENCOUNTER    -> ActiveEncounterResolver.hint(entry.title)
        EntryType.HARD_MODE_ENCOUNTER -> HardModeEncounterResolver.hint(entry.title)
        EntryType.MAIN_STORY          -> MainStoryEncounterResolver.hint(entry.title)
        else -> null
    }

    private fun showResultPopup(entry: ResolvedEntry) {
        val hint = getHint(entry)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val showHintImmediately = prefs.getBoolean(MainActivity.KEY_HINT_DEFAULT, false)
        showPopupInternal(entry = entry, hint = hint, showHint = showHintImmediately)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun showPopupInternal(entry: ResolvedEntry, hint: String?, showHint: Boolean) {
        handler.post {
            dismissPopup()
            val ctx = this
            val metrics = resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val density = metrics.density

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (20 * density).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0xF0101010.toInt())
                    cornerRadius = 18 * density
                    setStroke((1.5f * density).toInt(), 0xFFC0C0C0.toInt())
                }
                elevation = 24 * density
            }

            // ── 타입 라벨 + 힌트 버튼 가로 행 ────────────────────────────
            val headerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(TextView(ctx).apply {
                text = getString(R.string.label_type, entry.type.label)
                textSize = 11f
                setTextColor(0xFFC8A84B.toInt())
                typeface = AppFont.regular(ctx)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // 힌트 버튼: 힌트 있을 때만 표시
            if (hint != null) {
                headerRow.addView(TextView(ctx).apply {
                    text = if (showHint) "힌트 끄기" else "힌트 보기"
                    textSize = 11f
                    setTextColor(if (showHint) 0xFFAAAAAA.toInt() else 0xFFE6C15A.toInt())
                    val hPad = (10 * density).toInt()
                    val vPad = (4 * density).toInt()
                    setPadding(hPad, vPad, hPad, vPad)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(0xFF2A2A2A.toInt())
                        cornerRadius = 10 * density
                        setStroke((1 * density).toInt(), 0xFF555555.toInt())
                    }
                    setOnClickListener { showPopupInternal(entry, hint, !showHint) }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }

            root.addView(headerRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() })

            // ── 제목 ───────────────────────────────────────────────────────
            root.addView(TextView(ctx).apply {
                text = entry.title
                textSize = 18f
                setTextColor(0xFFEEEEEE.toInt())
                typeface = AppFont.bold(ctx)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = if (showHint) (12 * density).toInt() else (4 * density).toInt() })

            if (showHint) {
                // ── 힌트 텍스트 ───────────────────────────────────────────
                root.addView(TextView(ctx).apply {
                    text = hint ?: "현재 힌트 기능은 거의 업데이트 되지 않았습니다."
                    textSize = 13f
                    setTextColor(0xFFD8D8D8.toInt())
                    typeface = AppFont.regular(ctx)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (16 * density).toInt() })
            } else {
                // ── URL 미리보기 ──────────────────────────────────────────
                root.addView(TextView(ctx).apply {
                    text = entry.url
                    textSize = 11f
                    setTextColor(0xFF888888.toInt())
                    typeface = AppFont.regular(ctx)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (16 * density).toInt() })
            }

            // ── 버튼 행: 브라우저에서 열기 / 팝업으로 열기 ────────────────
            val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

            btnRow.addView(Button(ctx).apply {
                text = "브라우저에서 열기"
                textSize = 13f
                setTextColor(0xFF101010.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0xFFEEEEEE.toInt())
                    cornerRadius = 24 * density
                }
                setOnClickListener {
                    dismissPopup()
                    startActivity(Intent(Intent.ACTION_VIEW, entry.url.toUri()).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply {
                rightMargin = (8 * density).toInt()
            })

            btnRow.addView(Button(ctx).apply {
                text = "팝업으로 열기"
                textSize = 13f
                setTextColor(0xFFE6C15A.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0xFF2A2A2A.toInt())
                    cornerRadius = 24 * density
                    setStroke((1 * density).toInt(), 0xFF555555.toInt())
                }
                setOnClickListener {
                    dismissPopup()
                    showWebOverlay(entry.url)
                }
            }, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f))

            root.addView(btnRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            val popupWidth = minOf((screenWidth * 0.88).toInt(), (340 * density).toInt())
            val params = WindowManager.LayoutParams(
                popupWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            root.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) { dismissPopup(); true } else false
            }

            popupView = root
            try {
                windowManager.addView(root, params)
            } catch (e: Exception) {
                Log.e("Seoul2033Wiki", "팝업 표시 오류", e)
                Toast.makeText(ctx, "팝업 표시 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dismissPopup() {
        popupView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        popupView = null
    }

    // ── 오버레이 WebView ─────────────────────────────────────────────────────
    private var webOverlayView: View? = null
    private var webOverlayParams: WindowManager.LayoutParams? = null

    private fun dismissWebOverlay() {
        webOverlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        webOverlayView = null
        webOverlayParams = null
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showWebOverlay(url: String) {
        dismissWebOverlay()
        val ctx = this
        val metrics = resources.displayMetrics
        val density = metrics.density
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xF5101010.toInt())
                cornerRadius = 16 * density
                setStroke((1 * density).toInt(), 0xFF444444.toInt())
            }
            elevation = 32 * density
        }

        val popupW = (screenWidth * 0.92).toInt()
        val popupH = (screenHeight * 0.72).toInt()

        val overlayParams = WindowManager.LayoutParams(
            popupW, popupH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        webOverlayParams = overlayParams

        // ── 상단 바 (드래그 핸들 겸용) ───────────────────────────────────
        var dragInitX = 0; var dragInitY = 0
        var dragTouchX = 0f; var dragTouchY = 0f

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val vPad = (12 * density).toInt()
            val hPad = (14 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            gravity = Gravity.CENTER_VERTICAL
            // 드래그 핸들임을 시각적으로 표시
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1A1A1A.toInt())
                // 상단 모서리만 둥글게
                cornerRadii = floatArrayOf(
                    16 * density, 16 * density,   // top-left
                    16 * density, 16 * density,   // top-right
                    0f, 0f,                        // bottom-right
                    0f, 0f                         // bottom-left
                )
            }
        }

        // 드래그 핸들 아이콘 (≡ 형태)
        topBar.addView(TextView(ctx).apply {
            text = "⠿"
            textSize = 14f
            setTextColor(0xFF555555.toInt())
            val rPad = (8 * density).toInt()
            setPadding(0, 0, rPad, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        topBar.addView(TextView(ctx).apply {
            text = url.removePrefix("https://").take(48)
            textSize = 11f
            setTextColor(0xFF777777.toInt())
            typeface = AppFont.regular(ctx)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        topBar.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 15f
            setTextColor(0xFF888888.toInt())
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
            setOnClickListener { dismissWebOverlay() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 상단 바 터치로 팝업 전체 드래그
        topBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragInitX = overlayParams.x
                    dragInitY = overlayParams.y
                    dragTouchX = event.rawX
                    dragTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    overlayParams.x = dragInitX + (event.rawX - dragTouchX).toInt()
                    overlayParams.y = dragInitY + (event.rawY - dragTouchY).toInt()
                    try { windowManager.updateViewLayout(root, overlayParams) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        root.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── 구분선 ────────────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            setBackgroundColor(0xFF2A2A2A.toInt())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()))

        // ── WebView ───────────────────────────────────────────────────────
        val webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            // 다크모드: Android 13+ 에서 네이티브 다크모드 적용 시도
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                settings.isAlgorithmicDarkeningAllowed = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    super.onPageFinished(view, pageUrl)
                    // 페이지 로딩 후 CSS invert 방식으로 다크모드 강제 적용
                    view?.evaluateJavascript(DARK_MODE_JS, null)
                }
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    // 팝업 내에서 링크 클릭 시 팝업 안에서 이동
                    request?.url?.toString()?.let { view?.loadUrl(it) }
                    return true
                }
            }
            loadUrl(url)
        }
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        webOverlayView = root
        try {
            windowManager.addView(root, overlayParams)
        } catch (e: Exception) {
            Log.e("Seoul2033Wiki", "WebOverlay 표시 오류", e)
            Toast.makeText(ctx, "WebView 팝업 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── 공통 정리 ────────────────────────────────────────────────────────────
    private fun releaseAll() {
        dismissPopup()
        dismissWebOverlay()
        hideDropZone()
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayView = null
        isCapturing = false
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "오버레이 서비스", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("서울2033 리더 실행 중")
            .setContentText("버튼을 아래로 드래그해서 종료")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseAll()
        Seoul2033AccessibilityService.notifyOverlayStopped()
    }
}