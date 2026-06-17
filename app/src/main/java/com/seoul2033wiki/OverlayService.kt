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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        scope.launch { WikiUrlResolver.loadStoryLists() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == MainActivity.ACTION_SAVE_POSITION) {
            saveOverlayPosition()
            return START_NOT_STICKY
        }
        if (intent?.action == MainActivity.ACTION_RESET_POSITION) {
            resetOverlayPosition()
            return START_NOT_STICKY
        }
        if (intent?.action == MainActivity.ACTION_SET_ALPHA_ON) {
            val alpha = intent.getIntExtra(MainActivity.EXTRA_ALPHA_VALUE, 100)
            alphaOn = alpha / 100f
            if (isTouchable) applyAlpha(alphaOn)
            return START_NOT_STICKY
        }
        if (intent?.action == MainActivity.ACTION_SET_ALPHA_OFF) {
            val alpha = intent.getIntExtra(MainActivity.EXTRA_ALPHA_VALUE, 60)
            alphaOff = alpha / 100f
            if (!isTouchable) applyAlpha(alphaOff)
            return START_NOT_STICKY
        }
        if (intent?.action == MainActivity.ACTION_SET_SIZE_ON) {
            widthOnPct  = intent.getIntExtra(MainActivity.EXTRA_WIDTH_VALUE,  MainActivity.DEFAULT_WIDTH_ON)
            heightOnPct = intent.getIntExtra(MainActivity.EXTRA_HEIGHT_VALUE, MainActivity.DEFAULT_HEIGHT_ON)
            if (isTouchable) applySize(widthOnPct, heightOnPct)
            return START_NOT_STICKY
        }
        if (intent?.action == MainActivity.ACTION_SET_SIZE_OFF) {
            widthOffPct  = intent.getIntExtra(MainActivity.EXTRA_WIDTH_VALUE,  MainActivity.DEFAULT_WIDTH_OFF)
            heightOffPct = intent.getIntExtra(MainActivity.EXTRA_HEIGHT_VALUE, MainActivity.DEFAULT_HEIGHT_OFF)
            if (!isTouchable) applySize(widthOffPct, heightOffPct)
            return START_NOT_STICKY
        }
        if (intent?.action == MainActivity.ACTION_RESET_SETTINGS) {
            alphaOn  = 1f;  alphaOff = 0.6f
            widthOnPct   = MainActivity.DEFAULT_WIDTH_ON;  heightOnPct  = MainActivity.DEFAULT_HEIGHT_ON
            widthOffPct  = MainActivity.DEFAULT_WIDTH_OFF; heightOffPct = MainActivity.DEFAULT_HEIGHT_OFF
            applyAlpha(if (isTouchable) alphaOn else alphaOff)
            applySize(if (isTouchable) widthOnPct else widthOffPct,
                if (isTouchable) heightOnPct else heightOffPct)
            return START_NOT_STICKY
        }
        if (intent?.action == MainActivity.ACTION_SHOW_EXAMPLE) {
            showWebOverlay(ResolvedEntry(
                title = "랜덤 인카운터", pageNum = "", type = EntryType.BASIC,
                url = "https://namu.wiki/w/%EC%84%9C%EC%9A%B8%202033/%EB%9E%9C%EB%8D%A4%20%EC%9D%B8%EC%B9%B4%EC%9A%B4%ED%84%B0"
            ))
            return START_NOT_STICKY
        }
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
        Seoul2033AccessibilityService.notifyOverlayStarted()
        return START_NOT_STICKY
    }

    // ── 버튼 위치 저장 / 초기화 ─────────────────────────────────────────────
    private fun saveOverlayPosition() {
        val view = overlayView ?: run {
            handler.post { Toast.makeText(this, "오버레이가 실행 중이지 않습니다.", Toast.LENGTH_SHORT).show() }
            return
        }
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putInt("overlay_x", params.x)
            .putInt("overlay_y", params.y)
            .putInt("overlay_gravity", params.gravity)
            .apply()
        handler.post { Toast.makeText(this, "위치가 저장됐어요!", Toast.LENGTH_SHORT).show() }
    }

    private fun resetOverlayPosition() {
        val defaultGravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putInt("overlay_x", 0)
            .putInt("overlay_y", 80)
            .putInt("overlay_gravity", defaultGravity)
            .apply()
        val view = overlayView
        if (view != null) {
            val params = view.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                params.gravity = defaultGravity
                params.x = 0
                params.y = 80
                try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
            }
        }
        handler.post { Toast.makeText(this, "버튼 위치가 초기화됐어요!", Toast.LENGTH_SHORT).show() }
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
            gravity = Gravity.CENTER
            x = 0
            y = 0
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
        )
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val savedGravity = prefs.getInt("overlay_gravity", Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        params.gravity = savedGravity
        params.x = prefs.getInt("overlay_x", 0)
        params.y = prefs.getInt("overlay_y", 80)
        if (savedGravity != (Gravity.TOP or Gravity.LEFT)) {
            newView.post {
                val loc = IntArray(2)
                newView.getLocationOnScreen(loc)
                val statusBarHeight = run {
                    val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
                    if (resId > 0) resources.getDimensionPixelSize(resId) else 0
                }
                params.gravity = Gravity.TOP or Gravity.LEFT
                params.x = loc[0]
                params.y = loc[1] - statusBarHeight
                try { windowManager.updateViewLayout(newView, params) } catch (_: Exception) {}
            }
        }

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
                    val moved = kotlin.math.abs(event.rawX - initialTouchX) > 10 ||
                            kotlin.math.abs(event.rawY - initialTouchY) > 10
                    if (moved && !isDragging) {
                        isDragging = true
                        showDropZone()
                    }
                    if (isDragging) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(newView, params)
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
            val bottomEntry = WikiUrlResolver.resolve(rawText)
            when {
                bottomEntry != null
                        && bottomEntry.type != EntryType.BASIC
                        && bottomEntry.type != EntryType.MAIN_STORY
                        && bottomEntry.type != EntryType.EXPANSION -> {
                    bottomEntry
                }
                bottomEntry != null && bottomEntry.type == EntryType.EXPANSION -> {
                    if (bottomEntry.url.contains('#')) {
                        // Case B expansionAnchor 있음 → URL 이미 결정됨, ExpansionEncounterResolver 스킵
                        // 제목을 "확장팩 - 섹션앵커" 형태로 구성
                        val anchor = bottomEntry.url
                            .substringAfterLast('#')
                            .let { java.net.URLDecoder.decode(it, "UTF-8") }
                        Log.d("Seoul2033Wiki", "확장팩 앵커 포함 URL 확정: '${bottomEntry.title}' → $anchor")
                        bottomEntry.copy(title = "${bottomEntry.title} - $anchor")
                    } else {
                        Log.d("Seoul2033Wiki", "확장팩 인식: '${bottomEntry.title}' → 섹션 탐지")
                        val expansionEntry = ExpansionEncounterResolver.resolve(rawText, bottomEntry.title)
                            ?: run {
                                Log.d("Seoul2033Wiki", "확장팩 섹션 매칭 실패 → 폴백: '${bottomEntry.title}'")
                                ResolvedEntry(
                                    title = bottomEntry.title,
                                    pageNum = "",
                                    type = EntryType.EXPANSION,
                                    url = bottomEntry.url
                                )
                            }
                        // Case B crossLink 보존
                        if (bottomEntry.crossLinkUrl != null) {
                            expansionEntry.copy(
                                crossLinkUrl = bottomEntry.crossLinkUrl,
                                crossLinkLabel = bottomEntry.crossLinkLabel
                            )
                        } else expansionEntry
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

    private fun showResultPopup(entry: ResolvedEntry) {
        showPopupInternal(entry)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun showPopupInternal(entry: ResolvedEntry) {
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

            // 크로스링크 토글 상태 — if 블록 밖에서도 btnRow가 참조
            var popupCrossLinked = false

            // 크로스링크 버튼 (crossLinkUrl 있을 때만)
            if (entry.crossLinkUrl != null && entry.crossLinkLabel != null) {
                var isCrossLinked = false
                val tvType  = headerRow.getChildAt(0) as TextView
                // 제목/URL TextView는 아직 생성 전이므로 나중에 참조할 수 있게 미리 선언
                var tvTitle: TextView? = null
                var tvUrl:   TextView? = null

                val crossBtn = TextView(ctx).apply {
                    text = "( ${entry.crossLinkLabel} )"
                    textSize = 11f
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    val hp = (10 * density).toInt()
                    val vp = (4 * density).toInt()
                    setPadding(hp, vp, hp, vp)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(0xFF2A2A2A.toInt())
                        cornerRadius = 10 * density
                        setStroke((1 * density).toInt(), 0xFF555555.toInt())
                    }
                    setOnClickListener {
                        isCrossLinked = !isCrossLinked
                        popupCrossLinked = isCrossLinked
                        if (isCrossLinked) {
                            setTextColor(0xFF4CAF50.toInt())
                            (background as? GradientDrawable)?.setStroke((1 * density).toInt(), 0xFF4CAF50.toInt())
                            tvType.text = getString(R.string.label_type, EntryType.MAIN_STORY.label)
                            tvTitle?.text = entry.crossLinkLabel
                            tvUrl?.text   = entry.crossLinkUrl
                        } else {
                            setTextColor(0xFFFFFFFF.toInt())
                            (background as? GradientDrawable)?.setStroke((1 * density).toInt(), 0xFF555555.toInt())
                            tvType.text = getString(R.string.label_type, entry.type.label)
                            tvTitle?.text = entry.title
                            tvUrl?.text   = entry.url
                        }
                    }
                }
                headerRow.addView(crossBtn, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = (8 * density).toInt() })

                root.addView(headerRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * density).toInt() })

                val titleView = TextView(ctx).apply {
                    text = entry.title
                    textSize = 18f
                    setTextColor(0xFFEEEEEE.toInt())
                    typeface = AppFont.bold(ctx)
                }
                tvTitle = titleView
                root.addView(titleView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * density).toInt() })

                val urlView = TextView(ctx).apply {
                    text = entry.url
                    textSize = 11f
                    setTextColor(0xFF888888.toInt())
                    typeface = AppFont.regular(ctx)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                tvUrl = urlView
                root.addView(urlView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (16 * density).toInt() })

            } else {
                // 크로스링크 없는 일반 케이스
                root.addView(headerRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * density).toInt() })

                root.addView(TextView(ctx).apply {
                    text = entry.title
                    textSize = 18f
                    setTextColor(0xFFEEEEEE.toInt())
                    typeface = AppFont.bold(ctx)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * density).toInt() })

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
                    val url = if (popupCrossLinked) entry.crossLinkUrl ?: entry.url else entry.url
                    dismissPopup()
                    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply {
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
                    val url = if (popupCrossLinked) entry.crossLinkUrl ?: entry.url else entry.url
                    dismissPopup()
                    showWebOverlay(entry.copy(url = url))
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
    // WebView 영역과 상단 바를 별도 View로 분리.
    // 상단 바는 항상 터치 가능.
    // WebView 영역은 터치 토글(isTouchable)에 따라 FLAG_NOT_TOUCHABLE 적용.
    //
    // 상단 바 구조:
    //   [⠿] [url...] [터치ON/OFF] [✕]   ← 버튼행
    //   [══════ 투명도 슬라이더 ══════]   ← 슬라이더행 (아래쪽으로만 높이 추가)

    private var webOverlayView: View? = null
    private var webTopBarView: View? = null
    private var webOverlayParams: WindowManager.LayoutParams? = null
    private var webTopBarParams: WindowManager.LayoutParams? = null

    private var webOverlayIsPeeked = false
    private var webOverlayFullX = 0
    private var webOverlayFullY = 0
    private var isTouchable = true    // 기본: 터치 ON

    // 터치 ON/OFF 별 투명도 (설정에서 로드)
    private var alphaOn  = 1f
    private var alphaOff = 0.6f

    // 터치 ON/OFF 별 창 크기 (화면 대비 %, 설정에서 로드)
    private var widthOnPct  = MainActivity.DEFAULT_WIDTH_ON
    private var widthOffPct = MainActivity.DEFAULT_WIDTH_OFF
    private var heightOnPct  = MainActivity.DEFAULT_HEIGHT_ON
    private var heightOffPct = MainActivity.DEFAULT_HEIGHT_OFF

    // 상단 바 실측 높이 (onGlobalLayout 후 갱신)
    private var topBarH = 0

    // peek 탭 (오른쪽 가운데 고정)
    private var peekTabView: View? = null

    // peek 드롭존 (오른쪽 가장자리 세로 중앙)
    private var peekDropZoneView: View? = null

    private fun showPeekDropZone() {
        if (peekDropZoneView != null) return
        val density = resources.displayMetrics.density
        val zoneW = (6 * density).toInt()
        val zoneH = (500 * density).toInt()
        val view = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xCCE6C15A.toInt())
                cornerRadius = 4 * density
            }
        }
        val params = WindowManager.LayoutParams(
            zoneW, zoneH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = 0
        }
        peekDropZoneView = view
        try { windowManager.addView(view, params) } catch (_: Exception) {}
    }

    private fun hidePeekDropZone() {
        peekDropZoneView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        peekDropZoneView = null
    }

    private fun isInPeekDropZone(rawX: Float, rawY: Float): Boolean {
        val v = peekDropZoneView ?: return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val density = resources.displayMetrics.density
        val hitPad = (48 * density).toInt()
        val vW = if (v.width > 0) v.width else (6 * density).toInt()
        val vH = if (v.height > 0) v.height else (500 * density).toInt()
        val left   = (loc[0] - hitPad).toFloat()
        val right  = (loc[0] + vW + hitPad).toFloat()
        val top    = loc[1].toFloat()
        val bottom = (loc[1] + vH).toFloat()
        return rawX in left..right && rawY in top..bottom
    }

    private fun dismissWebOverlay() {
        webOverlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        webTopBarView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        webOverlayView = null
        webTopBarView = null
        webOverlayParams = null
        webTopBarParams = null
        webOverlayIsPeeked = false
        isTouchable = true
        hidePeekTab()
        hidePeekDropZone()
    }

    // ── 터치 토글 ────────────────────────────────────────────────────────────
    // isTouchable=false → FLAG_NOT_TOUCHABLE (터치가 WebView를 통과해 게임으로 전달)
    // isTouchable=true  → 일반 터치 (WebView 조작 가능)
    private fun applyTouchable(touchable: Boolean, toggleBtn: TextView? = null) {
        isTouchable = touchable
        val webView = webOverlayView ?: return
        val params = webOverlayParams ?: return
        if (touchable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { windowManager.updateViewLayout(webView, params) } catch (_: Exception) {}
        toggleBtn?.text = if (touchable) "터치 ON" else "터치 OFF"
        toggleBtn?.setTextColor(if (touchable) 0xFF4CAF50.toInt() else 0xFFAAAAAA.toInt())
        // 터치 상태에 맞는 투명도 + 크기 자동 적용
        applyAlpha(if (touchable) alphaOn else alphaOff)
        applySize(if (touchable) widthOnPct else widthOffPct,
            if (touchable) heightOnPct else heightOffPct)
    }

    // ── 투명도 적용 ──────────────────────────────────────────────────────────
    private fun applyAlpha(alpha: Float) {
        webOverlayView?.alpha = alpha
    }

    // ── 창 크기 적용 ─────────────────────────────────────────────────────────
    private fun applySize(widthPct: Int, heightPct: Int) {
        val webView = webOverlayView ?: return
        val params  = webOverlayParams ?: return
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // 최소 너비: 터치 ON → 50%, 터치 OFF → 25%
        val minWidthPct = if (isTouchable) 50 else 25
        val clampedW = widthPct.coerceAtLeast(minWidthPct)

        val newW = (screenW * clampedW  / 100.0).toInt()
        val newH = (screenH * heightPct / 100.0).toInt()

        params.width  = newW
        params.height = newH
        try { windowManager.updateViewLayout(webView, params) } catch (_: Exception) {}

        // 상단 바 너비 동기화
        val topBar       = webTopBarView   ?: return
        val topBarParams = webTopBarParams ?: return
        topBarParams.width = newW
        // 높이 변경 시 상단 바가 웹뷰 바로 위에 붙도록 y 재계산
        topBarParams.y = params.y - (newH + topBarH) / 2
        try { windowManager.updateViewLayout(topBar, topBarParams) } catch (_: Exception) {}
    }

    // ── peek 진입 ────────────────────────────────────────────────────────────
    private fun peekWebOverlay(popupW: Int, popupH: Int, toggleBtn: TextView) {
        val webView = webOverlayView ?: return
        val topBar = webTopBarView ?: return
        val webParams = webOverlayParams ?: return

        webOverlayFullX = webParams.x
        webOverlayFullY = webParams.y
        webOverlayIsPeeked = true

        // 상단 바 + 웹뷰 제거
        try { windowManager.removeView(webView) } catch (_: Exception) {}
        try { windowManager.removeView(topBar) } catch (_: Exception) {}

        // 오른쪽 가운데 peek 탭 표시
        showPeekTab(popupW, popupH)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun showPeekTab(popupW: Int, popupH: Int) {
        if (peekTabView != null) return
        val density = resources.displayMetrics.density
        val tabW = (44 * density).toInt()
        val tabH = (44 * density).toInt()

        val tab = TextView(this).apply {
            text = "⠿"
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1A1A1A.toInt())
                cornerRadius = 12 * density
                setStroke((1 * density).toInt(), 0xFF444444.toInt())
            }
            elevation = 32 * density
        }

        val params = WindowManager.LayoutParams(
            tabW, tabH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
            x = 0
            y = 0
        }

        tab.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                hidePeekTab()
                expandWebOverlay(popupW, popupH)
            }
            true
        }

        peekTabView = tab
        try { windowManager.addView(tab, params) } catch (_: Exception) {}
    }

    private fun hidePeekTab() {
        peekTabView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        peekTabView = null
    }

    // ── peek 복원 ────────────────────────────────────────────────────────────
    private fun expandWebOverlay(popupW: Int, popupH: Int) {
        val webView = webOverlayView ?: return
        val topBar = webTopBarView ?: return
        val webParams = webOverlayParams ?: return
        val topParams = webTopBarParams ?: return

        webOverlayIsPeeked = false

        // 현재 설정값으로 실제 크기 재계산 (peek 이후 설정이 바뀌었을 수 있으므로)
        val metricsEx = resources.displayMetrics
        val minWPct = if (isTouchable) 50 else 25
        val wPct = (if (isTouchable) widthOnPct else widthOffPct).coerceAtLeast(minWPct)
        val hPct = if (isTouchable) heightOnPct else heightOffPct
        val actualW = (metricsEx.widthPixels  * wPct / 100.0).toInt()
        val actualH = (metricsEx.heightPixels * hPct / 100.0).toInt()

        // 웹뷰 + 상단 바 다시 추가
        webParams.width = actualW
        webParams.height = actualH
        webParams.x = webOverlayFullX
        webParams.y = webOverlayFullY
        try { windowManager.addView(webView, webParams) } catch (_: Exception) {
            try { windowManager.updateViewLayout(webView, webParams) } catch (_: Exception) {}
        }

        // 상단 바 복원 (너비 + y 동기화)
        topParams.width = actualW
        topParams.x = webOverlayFullX
        topParams.y = webOverlayFullY - (actualH + topBarH) / 2
        try { windowManager.addView(topBar, topParams) } catch (_: Exception) {
            try { windowManager.updateViewLayout(topBar, topParams) } catch (_: Exception) {}
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showWebOverlay(entry: ResolvedEntry) {
        dismissWebOverlay()
        val url = entry.url
        val ctx = this
        val metrics = resources.displayMetrics
        val density = metrics.density
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val popupW = (screenWidth * 0.92).toInt()
        val popupH = (screenHeight * 0.5).toInt()
        topBarH = (44 * density).toInt()    // 상단 바 높이 초기값 (실측 후 갱신됨)

        var currentX = 0
        var currentY = 0

        // ── 1. WebView 영역 ───────────────────────────────────────────────────
        val webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false

            // ── 렉 감소 최적화 ────────────────────────────────────────────────
            settings.loadsImagesAutomatically = false  // 이미지 로드 안 함 (JS로 빈칸 처리)
            settings.textZoom = 100
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.toString()?.let { view?.loadUrl(it) }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 이미지를 빈칸으로 처리 (레이아웃 공간 유지, 이미지만 안 보임)
                    view?.evaluateJavascript(
                        "document.querySelectorAll('img').forEach(function(img){ img.style.visibility='hidden'; });",
                        null
                    )
                }
            }
            loadUrl(url)
        }

        // 초기: 터치 on
        val webParams = WindowManager.LayoutParams(
            popupW, popupH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = currentX
            y = currentY
        }
        webOverlayView = webView
        webOverlayParams = webParams
        isTouchable = true

        // 설정에서 투명도 + 창 크기 로드 및 초기 적용 (터치 ON 상태이므로 ON 값 적용)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        alphaOn  = prefs.getInt(MainActivity.KEY_ALPHA_ON,  100) / 100f
        alphaOff = prefs.getInt(MainActivity.KEY_ALPHA_OFF,  60) / 100f
        widthOnPct   = prefs.getInt(MainActivity.KEY_WIDTH_ON,   MainActivity.DEFAULT_WIDTH_ON)
        widthOffPct  = prefs.getInt(MainActivity.KEY_WIDTH_OFF,  MainActivity.DEFAULT_WIDTH_OFF)
        heightOnPct  = prefs.getInt(MainActivity.KEY_HEIGHT_ON,  MainActivity.DEFAULT_HEIGHT_ON)
        heightOffPct = prefs.getInt(MainActivity.KEY_HEIGHT_OFF, MainActivity.DEFAULT_HEIGHT_OFF)
        webView.alpha = alphaOn
        // 상단 바 생성 후 applySize를 호출해야 너비가 함께 적용되므로 여기서는 webParams만 미리 세팅
        val minWPct0 = 50
        val clampedW0 = widthOnPct.coerceAtLeast(minWPct0)
        webParams.width  = (screenWidth  * clampedW0   / 100.0).toInt()
        webParams.height = (screenHeight * heightOnPct / 100.0).toInt()

        // ── 2. 상단 바 ────────────────────────────────────────────────────────
        // 구조:
        //   버튼행: [⠿] [url...] [터치ON/OFF] [✕]
        //   슬라이더행: [══════ 투명도 슬라이더 ══════]
        val topBarRoot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1A1A1A.toInt())
                cornerRadius = 16 * density
                setStroke((1 * density).toInt(), 0xFF444444.toInt())
            }
            elevation = 32 * density
        }

        // ── 버튼행 ────────────────────────────────────────────────────────────
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val vPad = (12 * density).toInt()
            val hPad = (12 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            gravity = Gravity.CENTER_VERTICAL
        }

        // 드래그 핸들
        btnRow.addView(TextView(ctx).apply {
            text = "⠿"
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 0, (8 * density).toInt(), 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // URL 표시
        btnRow.addView(TextView(ctx).apply {
            text = url.removePrefix("https://").take(48)
            textSize = 11f
            setTextColor(0xFF777777.toInt())
            typeface = AppFont.regular(ctx)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 터치 토글 버튼 (초기: ON)
        val toggleBtn = TextView(ctx).apply {
            text = "터치 ON"
            textSize = 11f
            setTextColor(0xFF4CAF50.toInt())
            gravity = Gravity.CENTER
            val hp = (10 * density).toInt()
            val vp = (5 * density).toInt()
            setPadding(hp, vp, hp, vp)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF2A2A2A.toInt())
                cornerRadius = 10 * density
                setStroke((1 * density).toInt(), 0xFF555555.toInt())
            }
            setOnClickListener { applyTouchable(!isTouchable, this) }
        }
        btnRow.addView(toggleBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = (8 * density).toInt() })

        // 닫기 버튼
        btnRow.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 15f
            setTextColor(0xFF888888.toInt())
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
            setOnClickListener { dismissWebOverlay() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = (4 * density).toInt() })

        topBarRoot.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val topParams = WindowManager.LayoutParams(
            popupW, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = currentX
            y = currentY - (popupH + topBarH) / 2
        }
        webTopBarView = topBarRoot
        webTopBarParams = topParams

        // ── 상단 바 드래그 ────────────────────────────────────────────────────
        var dragInitX = 0; var dragInitY = 0
        var dragTouchX = 0f; var dragTouchY = 0f
        var isTopBarDragging = false

        topBarRoot.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (webOverlayIsPeeked) {
                        expandWebOverlay(popupW, popupH)
                        return@setOnTouchListener true
                    }
                    dragInitX = webParams.x; dragInitY = webParams.y
                    dragTouchX = event.rawX; dragTouchY = event.rawY
                    isTopBarDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (webOverlayIsPeeked) return@setOnTouchListener true
                    val dx = event.rawX - dragTouchX
                    val dy = event.rawY - dragTouchY
                    if (!isTopBarDragging && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) {
                        isTopBarDragging = true
                        showPeekDropZone()
                    }
                    if (isTopBarDragging) {
                        val curW = webParams.width
                        val curH = webParams.height
                        val halfW = curW / 2; val halfH = curH / 2
                        val newX = (dragInitX + dx.toInt()).coerceIn(
                            -(screenWidth / 2 + halfW - topBarH),
                            screenWidth / 2 + halfW - topBarH
                        )
                        val newY = (dragInitY + dy.toInt()).coerceIn(
                            -(screenHeight / 2 + halfH - topBarH),
                            screenHeight / 2 + halfH - topBarH
                        )
                        currentX = newX; currentY = newY

                        webParams.x = newX; webParams.y = newY
                        try { windowManager.updateViewLayout(webView, webParams) } catch (_: Exception) {}
                        topParams.x = newX; topParams.y = newY - (curH + topBarH) / 2
                        try { windowManager.updateViewLayout(topBarRoot, topParams) } catch (_: Exception) {}

                        val inPeek = isInPeekDropZone(event.rawX, event.rawY)
                        peekDropZoneView?.background = (peekDropZoneView?.background as? GradientDrawable)?.apply {
                            setColor(if (inPeek) 0xFFE6C15A.toInt() else 0xCCE6C15A.toInt())
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (webOverlayIsPeeked) return@setOnTouchListener true
                    if (isTopBarDragging) {
                        isTopBarDragging = false
                        val inPeek = isInPeekDropZone(event.rawX, event.rawY)
                        hidePeekDropZone()
                        if (inPeek) peekWebOverlay(popupW, popupH, toggleBtn)
                    }
                    true
                }
                else -> false
            }
        }

        // ── View 추가 ─────────────────────────────────────────────────────────
        try {
            windowManager.addView(webView, webParams)
        } catch (e: Exception) {
            Log.e("Seoul2033Wiki", "WebOverlay webView 추가 오류", e)
            Toast.makeText(ctx, "WebView 팝업 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            windowManager.addView(topBarRoot, topParams)
            // 상단 바 + 웹뷰 모두 추가된 시점에 applySize로 너비 동기화
            applySize(widthOnPct, heightOnPct)
            // 실제 렌더 높이로 y 좌표 재조정 (WRAP_CONTENT이므로 측정 후 보정)
            topBarRoot.viewTreeObserver.addOnGlobalLayoutListener(object :
                android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    topBarRoot.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val actualTopH = topBarRoot.height
                    if (actualTopH > 0) {
                        topBarH = actualTopH
                        topParams.y = currentY - (webParams.height + actualTopH) / 2
                        webParams.y = currentY
                        try {
                            windowManager.updateViewLayout(topBarRoot, topParams)
                            windowManager.updateViewLayout(webView, webParams)
                        } catch (_: Exception) {}
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("Seoul2033Wiki", "WebOverlay topBar 추가 오류", e)
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