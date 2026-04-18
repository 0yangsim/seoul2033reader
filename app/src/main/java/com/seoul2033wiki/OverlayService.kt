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
    private val channelId = "overlay_channel"
    private var isCapturing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
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
        // 기본 상태: 연노랑 텍스트
        btnText.setTextColor(0xFFF5F0C8.toInt())
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        newView.isClickable = true
        newView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(newView, params); true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - initialTouchX) > 10 ||
                            kotlin.math.abs(event.rawY - initialTouchY) > 10
                    if (!moved) {
                        v.performClick()
                        if (isCapturing) {
                            Toast.makeText(this, "인식 중입니다. 잠깐만요...", Toast.LENGTH_SHORT).show()
                        } else if (!Seoul2033AccessibilityService.isAlive(this)) {
                            // 앱 강제종료 등으로 접근성 서비스가 끊긴 경우 버튼에 경고 표시
                            btnText.text = "!"
                            btnText.setBackgroundResource(R.drawable.overlay_btn_bg_active)
                            btnText.setTextColor(0xFFFF4444.toInt())
                            Toast.makeText(
                                this,
                                "접근성 서비스가 끊겼습니다.\n설정 → 접근성 → 서울2033 리더를 재활성화해주세요.",
                                Toast.LENGTH_LONG
                            ).show()
                            // 3초 후 버튼 복귀
                            handler.postDelayed({
                                btnText.text = getString(R.string.btn_read)
                                btnText.setBackgroundResource(R.drawable.overlay_btn_bg)
                                btnText.setTextColor(0xFFF5F0C8.toInt())
                            }, 3000)
                        } else {
                            isCapturing = true
                            btnText.text = getString(R.string.btn_recognizing)
                            // 인식중: 은색 테두리 + 흰 텍스트
                            btnText.setBackgroundResource(R.drawable.overlay_btn_bg_active)
                            btnText.setTextColor(0xFFFFFFFF.toInt())
                            // 버튼 텍스트가 실제로 화면에 그려진 다음 프레임 이후 백그라운드 시작
                            // → 렌더링 완료를 보장한 뒤 작업 시작해 버튼이 즉시 바뀌어 보이도록 함
                            btnText.post {
                                scope.launch(Dispatchers.Default) {
                                    readAndResolve {
                                        btnText.text = getString(R.string.btn_read)
                                        // 완료: 기본 스타일로 복귀
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

        // extractGameText()는 AccessibilityNodeInfo를 읽으므로 메인 스레드에서 실행
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

        // ── 무거운 탐색 연산: 백그라운드(Default) 스레드에서 실행 ──────────
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

        // ── 결과 처리: 메인 스레드로 복귀 ────────────────────────────────
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

    // ── 오버레이 팝업 ────────────────────────────────────────────────────────────
    private var popupView: View? = null

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun showResultPopup(entry: ResolvedEntry) {
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
                    setStroke((1.5f * density).toInt(), 0xFFC0C0C0.toInt())  // 은색 테두리
                }
                elevation = 24 * density
            }

            root.addView(TextView(ctx).apply {
                text = getString(R.string.label_type, entry.type.label)
                textSize = 11f
                setTextColor(0xFFC8A84B.toInt())  // 골드
                typeface = AppFont.regular(ctx)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() })

            root.addView(TextView(ctx).apply {
                text = entry.title
                textSize = 18f
                setTextColor(0xFFEEEEEE.toInt())  // 밝은 흰색
                typeface = AppFont.bold(ctx)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() })

            root.addView(TextView(ctx).apply {
                text = entry.url
                textSize = 11f
                setTextColor(0xFF888888.toInt())  // 회색
                typeface = AppFont.regular(ctx)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * density).toInt() })

            val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

            btnRow.addView(Button(ctx).apply {
                text = "나무위키에서 열기"
                textSize = 14f
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
                text = "닫기"
                textSize = 14f
                setTextColor(0xFFAAAAAA.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0xFF2A2A2A.toInt())
                    cornerRadius = 24 * density
                    setStroke((1 * density).toInt(), 0xFF555555.toInt())
                }
                setOnClickListener { dismissPopup() }
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

    private fun releaseAll() {
        dismissPopup()
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayView = null
        isCapturing = false
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(channelId, "오버레이 서비스", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, channelId)
            .setContentTitle("서울2033 리더 실행 중")
            .setContentText("상단 Read 버튼을 눌러 나무위키 검색")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        releaseAll()
    }
}
