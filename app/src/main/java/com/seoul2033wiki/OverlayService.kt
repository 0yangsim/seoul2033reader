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
        scope.launch { WikiUrlResolver.loadStoryLists(applicationContext) }
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
        )
        // 마지막 저장 위치 복원 (없으면 상단 가로 중앙)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val savedGravity = prefs.getInt("overlay_gravity", Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        params.gravity = savedGravity
        params.x = prefs.getInt("overlay_x", 0)
        params.y = prefs.getInt("overlay_y", 80)
        // CENTER_HORIZONTAL 기준일 때 드래그 시작 시 LEFT로 정규화
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
    private var webOverlayIsPeeked = false
    private var webOverlayFullX = 0
    private var webOverlayFullY = 0

    // peek 드롭존 (오른쪽 가장자리 세로 중앙)
    private var peekDropZoneView: View? = null

    // peek 상태에서 터치 차단 + 탭 감지용 투명 오버레이
    private var peekTapOverlayView: View? = null

    private fun showPeekDropZone() {
        if (peekDropZoneView != null) return
        val density = resources.displayMetrics.density
        val screenHeight = resources.displayMetrics.heightPixels
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
        // 뷰가 아직 layout 안 됐으면 width/height == 0 → 화면 좌표 기반 폴백
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val density = resources.displayMetrics.density
        // 시각적 바(6dp)보다 넓은 48dp 여유를 양옆에 추가
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
        webOverlayView = null
        webOverlayParams = null
        webOverlayIsPeeked = false
        hidePeekDropZone()
        hidePeekTapOverlay()
    }

    // peek 중 터치 차단 + 탭 복원용 투명 오버레이
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun showPeekTapOverlay(popupW: Int, popupH: Int) {
        hidePeekTapOverlay()
        val view = View(this).apply {
            setBackgroundColor(0x00000000)  // 완전 투명
        }
        // 실제 peek 탭 크기와 동일하게 설정해야 터치 영역이 일치함
        val p = webOverlayParams ?: return
        val params = WindowManager.LayoutParams(
            p.width, p.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        params.x = p.x; params.y = p.y
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                hidePeekTapOverlay()
                // 절반만 튀어나오도록: 오른쪽 가장자리 기준 popupW/2 위치
                val p = webOverlayParams ?: return@setOnTouchListener true
                val sw = resources.displayMetrics.widthPixels
                webOverlayIsPeeked = false
                p.width = popupW
                p.height = popupH
                // 팝업 왼쪽 절반이 화면 밖, 오른쪽 절반이 화면 안에 걸치도록
                // screenWidth/2 + x + popupW/2 = screenWidth + popupW/2 → x = sw/2
                p.x = sw / 2
                p.y = webOverlayFullY
                try { windowManager.updateViewLayout(webOverlayView ?: return@setOnTouchListener true, p) } catch (_: Exception) {}
            }
            true
        }
        peekTapOverlayView = view
        try { windowManager.addView(view, params) } catch (_: Exception) {}
    }

    private fun hidePeekTapOverlay() {
        peekTapOverlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        peekTapOverlayView = null
    }

    // peek 진입: 너비 80dp, 높이는 popupH 유지 → 세로로 긴 갤럭시 팝업뷰 스타일
    private fun peekWebOverlay(popupW: Int, popupH: Int) {
        val root = webOverlayView ?: return
        val params = webOverlayParams ?: return
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val peekW = (20 * density).toInt()

        webOverlayFullX = params.x
        webOverlayFullY = params.y
        webOverlayIsPeeked = true

        // 너비 20dp, 높이 화면의 30%로 축소
        val peekH = (screenHeight * 0.3f).toInt()
        params.width = peekW
        params.height = peekH

        // 오른쪽 가장자리에 붙이기 (gravity=CENTER 기준)
        // screenWidth/2 + x + peekW/2 = screenWidth  →  x = screenWidth/2 - peekW/2
        params.x = screenWidth / 2 - peekW / 2

        // 세로는 현재 위치 유지, 화면 밖 클램프
        val maxY = (screenHeight - peekH) / 2
        params.y = params.y.coerceIn(-maxY, maxY)

        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}

        // peek 진입 후: 터치 차단 + 탭 복원 오버레이 띄우기
        showPeekTapOverlay(popupW, popupH)
    }

    // peek → 전체 복원
    private fun expandWebOverlay(fullW: Int, fullH: Int) {
        val root = webOverlayView ?: return
        val params = webOverlayParams ?: return
        webOverlayIsPeeked = false
        hidePeekTapOverlay()
        params.width = fullW
        params.height = fullH
        params.x = webOverlayFullX
        params.y = webOverlayFullY
        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showWebOverlay(url: String) {
        dismissWebOverlay()
        val ctx = this
        val metrics = resources.displayMetrics
        val density = metrics.density
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val popupW = (screenWidth * 0.92).toInt()
        val popupH = (screenHeight * 0.5).toInt()

        val overlayParams = WindowManager.LayoutParams(
            popupW, popupH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        webOverlayParams = overlayParams

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

        // ── 상단 바 ───────────────────────────────────────────────────────
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val vPad = (12 * density).toInt()
            val hPad = (14 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1A1A1A.toInt())
                cornerRadii = floatArrayOf(
                    16 * density, 16 * density,
                    16 * density, 16 * density,
                    0f, 0f, 0f, 0f
                )
            }
        }

        topBar.addView(TextView(ctx).apply {
            text = "⠿"
            textSize = 14f
            setTextColor(0xFF555555.toInt())
            setPadding(0, 0, (8 * density).toInt(), 0)
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

        root.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(View(ctx).apply {
            setBackgroundColor(0xFF2A2A2A.toInt())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()))

        val webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    request?.url?.toString()?.let { view?.loadUrl(it) }
                    return true
                }
            }
            loadUrl(url)
        }
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ── 드래그-to-close 상태 ─────────────────────────────────────────
        var webCloseDropZone: View? = null
        var webDragInitX2 = 0; var webDragInitY2 = 0
        var webDragTouchX2 = 0f; var webDragTouchY2 = 0f
        var isWebDragging = false

        fun showWebCloseDropZone() {
            if (webCloseDropZone != null) return
            val sizePx = (56 * density).toInt()
            val tv = TextView(ctx).apply {
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
            val dzParams = WindowManager.LayoutParams(
                sizePx, sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (72 * density).toInt()
            }
            webCloseDropZone = tv
            try { windowManager.addView(tv, dzParams) } catch (_: Exception) {}
        }

        fun hideWebCloseDropZone() {
            webCloseDropZone?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
            webCloseDropZone = null
        }

        fun isInWebCloseZone(rawX: Float, rawY: Float): Boolean {
            val v = webCloseDropZone ?: return false
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val cx = loc[0] + v.width / 2f; val cy = loc[1] + v.height / 2f
            val r = v.width / 2f * 2.0f
            val dx = rawX - cx; val dy = rawY - cy
            return dx * dx + dy * dy <= r * r
        }

        // topBar에 드래그-to-close 추가 (기존 topBar 터치리스너를 wrapping)
        topBar.setOnTouchListener { v2, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (webOverlayIsPeeked) {
                        expandWebOverlay(popupW, popupH)
                        return@setOnTouchListener true
                    }
                    webDragInitX2 = overlayParams.x; webDragInitY2 = overlayParams.y
                    webDragTouchX2 = event.rawX; webDragTouchY2 = event.rawY
                    isWebDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (webOverlayIsPeeked) return@setOnTouchListener true
                    val dx = event.rawX - webDragTouchX2
                    val dy = event.rawY - webDragTouchY2
                    if (!isWebDragging && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) {
                        isWebDragging = true
                        showWebCloseDropZone()
                        showPeekDropZone()
                    }
                    if (isWebDragging) {
                        val density2 = resources.displayMetrics.density
                        val topBarH = (44 * density2).toInt()
                        val halfW = popupW / 2; val halfH = popupH / 2
                        val clampedX = (webDragInitX2 + dx.toInt()).coerceIn(-(screenWidth / 2 + halfW - topBarH), screenWidth / 2 + halfW - topBarH)
                        val clampedY = (webDragInitY2 + dy.toInt()).coerceIn(-(screenHeight / 2 + halfH - topBarH), screenHeight / 2 + halfH - topBarH)
                        overlayParams.x = clampedX; overlayParams.y = clampedY
                        try { windowManager.updateViewLayout(root, overlayParams) } catch (_: Exception) {}

                        val inClose = isInWebCloseZone(event.rawX, event.rawY)
                        val inPeek  = isInPeekDropZone(event.rawX, event.rawY)
                        (webCloseDropZone as? TextView)?.apply {
                            setTextColor(if (inClose) 0xFFFF4444.toInt() else 0xFFFFFFFF.toInt())
                            (background as? GradientDrawable)?.apply {
                                setColor(if (inClose) 0xCC3D0000.toInt() else 0xCC333333.toInt())
                                setStroke((2 * density).toInt(), if (inClose) 0xFFFF4444.toInt() else 0xFFAAAAAA.toInt())
                            }
                        }
                        peekDropZoneView?.background = (peekDropZoneView?.background as? GradientDrawable)?.apply {
                            setColor(if (inPeek) 0xFFE6C15A.toInt() else 0xCCE6C15A.toInt())
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (webOverlayIsPeeked) return@setOnTouchListener true
                    if (isWebDragging) {
                        isWebDragging = false
                        val shouldClose = isInWebCloseZone(event.rawX, event.rawY)
                        val shouldPeek  = isInPeekDropZone(event.rawX, event.rawY)
                        hideWebCloseDropZone()
                        hidePeekDropZone()
                        when {
                            shouldClose -> dismissWebOverlay()
                            shouldPeek  -> peekWebOverlay(popupW, popupH)
                        }
                    }
                    true
                }
                else -> false
            }
        }

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