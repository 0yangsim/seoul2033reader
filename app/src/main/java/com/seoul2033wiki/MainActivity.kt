package com.seoul2033wiki

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private lateinit var statusText: TextView
    private lateinit var switchAutoStart: SwitchCompat
    private lateinit var switchAutoStop: SwitchCompat
    private lateinit var btnThemeToggle: ImageView
    private lateinit var prefs: SharedPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 알림 권한(POST_NOTIFICATIONS, Android 13+) 요청 결과 콜백.
    // registerForActivityResult는 Activity가 STARTED 상태가 되기 전(필드 초기화 시점)에 등록해야 한다.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d("MainActivity", "알림 권한 요청 결과: $granted")
            if (!granted) {
                ToastHelper.show(this, "알림 권한이 거부되어 공지 알림을 받을 수 없습니다.", Toast.LENGTH_SHORT)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 폰 시스템 다크모드와 무관하게 앱 자체 저장값으로 다크/라이트 강제 적용.
        // 반드시 super.onCreate()보다 먼저 호출해야 첫 화면부터 반영된다.
        ThemeManager.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        viewFlipper = findViewById(R.id.viewFlipper)
        onBackPressedDispatcher.addCallback(this, backPressCallback)

        showAccessibilityDisclosureIfNeeded()
        setupMainScreen()
        setupSettingsScreen()
        setupHelpScreen()
        setupChoiceBlockScreen()
        setupExperimentalScreen()
    }

    // ── 접근성 서비스 명시적 공개 동의 ───────────────────────────────────────
    // 이 다이얼로그가 끝난 뒤에만 알림 권한 다이얼로그를 띄운다 (동시에 show()하면
    // 뒤에 뜬 다이얼로그가 앞 걸 바로 덮어버려서 접근성 안내가 안 보이는 것처럼 되어버림).
    private fun showAccessibilityDisclosureIfNeeded() {
        if (prefs.getBoolean("accessibility_disclosed", false) ||
            prefs.getBoolean("accessibility_declined", false)
        ) {
            requestNotificationPermissionIfNeeded()
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("접근성 서비스 사용 안내")
            .setMessage("""
                서울2033 리더는 게임 화면의 텍스트를 읽기 위해 접근성 서비스(AccessibilityService)를 사용합니다.

                • 수집 정보: 서울2033 게임 화면에 표시된 텍스트
                • 사용 목적: 나무위키 항목 자동 검색, 선택지 자동 차단(설정에서 켠 경우)
                • 읽는 시점: '읽기' 버튼을 눌렀을 때. '선택지 자동 차단'을 켠 경우에는
                  인카운터 등장 여부를 확인하기 위해 게임 화면이 바뀔 때마다 추가로 읽습니다.
                • 외부 전송: 없음 (기기 내에서만 처리되며 어떠한 데이터도 외부로 전송되지 않습니다)

                접근성 서비스는 위 목적 외에 사용되지 않습니다.
                거부 시 접근성 기능을 사용할 수 없습니다.
            """.trimIndent())
            .setPositiveButton("동의") { _, _ ->
                prefs.edit().putBoolean("accessibility_disclosed", true).apply()
                Log.d("MainActivity", "접근성 안내 동의 (버튼)")
                requestNotificationPermissionIfNeeded()
            }
            .setNegativeButton("거부") { _, _ ->
                markAccessibilityDeclined("버튼")
                requestNotificationPermissionIfNeeded()
            }
            .setCancelable(true)
            .create()
        // 뒤로가기는 시스템이 알아서 cancel()을 호출해주지만, 일부 기기에서는
        // setCanceledOnTouchOutside(true)만으로 바깥 터치 dismiss가 씹히는 경우가 있어
        // 좌표를 직접 계산해서 판정한다.
        dialog.setOnCancelListener {
            markAccessibilityDeclined("뒤로가기")
            requestNotificationPermissionIfNeeded()
        }
        attachManualOutsideTouchDismiss(dialog) {
            markAccessibilityDeclined("바깥 터치")
            requestNotificationPermissionIfNeeded()
        }
        dialog.show()
    }

    // ── 알림 권한 (Android 13 / API 33 이상만 런타임 요청이 필요) ───────────────
    // 별도 안내 다이얼로그 없이 시스템 권한 팝업만 바로 띄움.
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return // 13 미만은 설치 시 자동 허용됨

        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) return

        // 한 번 물어본 뒤에는 앱을 다시 켤 때마다 또 띄우지 않음
        if (prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 거부 상태 저장 + 로그 + 사용자 피드백 (바깥 터치 시에도 실제로 반영됐음을 알 수 있도록)
    private fun markAccessibilityDeclined(via: String) {
        prefs.edit().putBoolean("accessibility_declined", true).apply()
        Log.d("MainActivity", "접근성 안내 거부됨 ($via) → accessibility_declined=true")
        ToastHelper.show(this, "접근성 서비스 거부됨 — 접근성 기능을 사용할 수 없습니다.", Toast.LENGTH_LONG)
    }

    // 다이얼로그의 실제 콘텐츠(카드) 영역 바깥을 터치했을 때 dismiss + 콜백 실행.
    // 일부 OEM(특히 One UI 등)에서 setCanceledOnTouchOutside(true)가 먹지 않는
    // 경우를 우회하기 위해 decorView에 직접 터치 좌표 판정을 건다.
    private fun attachManualOutsideTouchDismiss(dialog: AlertDialog, onOutside: () -> Unit) {
        dialog.setCanceledOnTouchOutside(false) // 기본 동작은 끄고 아래 수동 판정만 사용 (중복 실행 방지)
        dialog.setOnShowListener {
            val decor = dialog.window?.decorView ?: return@setOnShowListener
            val content = decor.findViewById<View>(android.R.id.content)
            decor.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN && content != null) {
                    val rect = android.graphics.Rect()
                    content.getGlobalVisibleRect(rect)
                    if (!rect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        onOutside()
                        dialog.dismiss()
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }
    }

    // ── 메인 화면 ──────────────────────────────────────────────────────────
    private fun setupMainScreen() {
        statusText = findViewById(R.id.statusText)
        switchAutoStart = findViewById(R.id.switchAutoStart)

        switchAutoStart.isChecked = prefs.getBoolean(KEY_AUTO_START, false)
        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_START, isChecked).apply()
            ToastHelper.show(
                this,
                if (isChecked) "서울2033 실행 시 자동으로 오버레이가 시작됩니다." else "자동 시작이 꺼졌습니다.",
                Toast.LENGTH_SHORT
            )
        }

        switchAutoStop = findViewById(R.id.switchAutoStop)
        switchAutoStop.isChecked = prefs.getBoolean(KEY_AUTO_STOP, false)
        switchAutoStop.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_STOP, isChecked).apply()
            ToastHelper.show(
                this,
                if (isChecked) "서울2033 종료 시 자동으로 오버레이가 꺼집니다." else "자동 종료가 꺼졌습니다.",
                Toast.LENGTH_SHORT
            )
        }

        findViewById<Button>(R.id.btnRequestOverlay).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnRequestAccessibility).setOnClickListener { requestAccessibilityPermission() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startOverlay() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopOverlay() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { viewFlipper.displayedChild = SCREEN_SETTINGS }
        findViewById<Button>(R.id.btnHelp).setOnClickListener { viewFlipper.displayedChild = SCREEN_HELP }

        setupThemeToggle()
    }

    // ── 다크모드/라이트모드 전환 버튼 (메인화면 우측 상단 고정) ────────────────
    private fun setupThemeToggle() {
        btnThemeToggle = findViewById(R.id.btnThemeToggle)

        // 상태 바 높이만큼 위쪽 여백을 더해서 노치/상태바와 겹치지 않게 함
        ViewCompat.setOnApplyWindowInsetsListener(btnThemeToggle) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val lp = v.layoutParams as android.view.ViewGroup.MarginLayoutParams
            lp.topMargin = top + BASE_THEME_BTN_MARGIN_DP.dpToPx()
            v.layoutParams = lp
            insets
        }

        updateThemeToggleIcon()
        btnThemeToggle.setOnClickListener {
            ThemeManager.toggle(this)
            recreate()
        }
    }

    private fun updateThemeToggleIcon() {
        // 아이콘은 "지금 상태"가 아니라 "누르면 바뀔 상태"를 보여줌
        btnThemeToggle.setImageResource(
            if (ThemeManager.isDarkMode(this)) R.drawable.ic_theme_sun else R.drawable.ic_theme_moon
        )
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    // 실험용 기능 화면의 선택지 자동 차단 항목에 현재 상태(켜짐/꺼짐 + 차단 시간)를 요약해서 보여줌
    private lateinit var tvChoiceBlockSummary: TextView

    private fun updateChoiceBlockSummary() {
        val enabled = prefs.getBoolean(KEY_CHOICE_BLOCK_ENABLED, false)
        if (!enabled) {
            tvChoiceBlockSummary.text = "꺼짐"
            return
        }
        val ms = prefs.getLong(KEY_CHOICE_BLOCK_MS, DEFAULT_CHOICE_BLOCK_MS)
        val sec = ms / 1000.0
        val secLabel = if (sec == sec.toLong().toDouble()) "${sec.toLong()}초" else "${sec}초"
        val disabledCount = ChoiceBlockKeys.disabledCategories(this).size
        val totalCount = ChoiceBlockKeys.CATEGORIES.size
        tvChoiceBlockSummary.text = "켜짐 · $secLabel · ${totalCount - disabledCount}/$totalCount 항목"
    }

    // ── 실험용 기능 화면 ───────────────────────────────────────────────────
    private fun setupExperimentalScreen() {
        val topBar = findViewById<android.view.View>(R.id.experimentalTopBar)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }

        findViewById<TextView>(R.id.btnExperimentalBack).setOnClickListener {
            viewFlipper.displayedChild = SCREEN_SETTINGS
        }

        tvChoiceBlockSummary = findViewById(R.id.tvChoiceBlockSummary)
        updateChoiceBlockSummary()
        findViewById<View>(R.id.rowChoiceBlockSettings).setOnClickListener {
            viewFlipper.displayedChild = SCREEN_CHOICE_BLOCK
        }
    }

    // ── Read 버튼 크기 조절 (동적 생성) ──────────────────────────────────────
    // XML에 전용 슬라이더가 없어서, "버튼 위치 초기화" 버튼 다음에 코드로
    // 크기 슬라이더 + 초기화 행을 끼워 넣는다.
    private fun setupOverlayBtnScaleRow() {
        val parent = findViewById<View>(R.id.btnResetPosition).parent as? LinearLayout ?: return
        val anchorIndex = parent.indexOfChild(findViewById(R.id.btnResetPosition))

        val density = resources.displayMetrics.density

        // 다른 설정 카드들과 동일한 카드 배경 + 여백
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.status_card)
            val hPad = (20 * density).toInt()
            val vPad = (14 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        }

        val savedScale = prefs.getFloat(KEY_OVERLAY_BTN_SCALE, DEFAULT_BTN_SCALE)
        val tvLabel = TextView(this).apply {
            text = "Read 버튼 크기: ${"%.1f".format(savedScale)}배"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_text_body))
            typeface = AppFont.regular(this@MainActivity)
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        container.addView(tvLabel)

        // SeekBar는 정수만 다루므로 0.5~2.0배를 5~20(0.1 단위)로 매핑
        val seekScale = SeekBar(this).apply {
            max = 15 // 5(0.5배) ~ 20(2.0배)
            progress = (savedScale * 10).toInt() - 5
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
        }
        seekScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = (progress + 5) / 10f
                tvLabel.text = "Read 버튼 크기: ${"%.1f".format(scale)}배"
                if (fromUser) {
                    prefs.edit().putFloat(KEY_OVERLAY_BTN_SCALE, scale).apply()
                    startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                        action = ACTION_SET_BTN_SCALE
                        putExtra(EXTRA_BTN_SCALE_VALUE, scale)
                    })
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        container.addView(seekScale)

        val btnResetScale = Button(this).apply {
            text = "버튼 크기 초기화"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_text_tertiary))
            textSize = 13f
            background = getDrawable(R.drawable.btn_stop)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (46 * density).toInt()
            ).apply { topMargin = (12 * density).toInt() }
        }
        btnResetScale.setOnClickListener {
            prefs.edit().putFloat(KEY_OVERLAY_BTN_SCALE, DEFAULT_BTN_SCALE).apply()
            seekScale.progress = (DEFAULT_BTN_SCALE * 10).toInt() - 5
            tvLabel.text = "Read 버튼 크기: ${"%.1f".format(DEFAULT_BTN_SCALE)}배"
            startService(Intent(this, OverlayService::class.java).apply {
                action = ACTION_SET_BTN_SCALE
                putExtra(EXTRA_BTN_SCALE_VALUE, DEFAULT_BTN_SCALE)
            })
            ToastHelper.show(this, "버튼 크기가 초기화됐습니다.", Toast.LENGTH_SHORT)
        }
        container.addView(btnResetScale)

        parent.addView(container, anchorIndex + 1)
    }

    // ── 설정 화면 ──────────────────────────────────────────────────────────
    private fun setupSettingsScreen() {
        // 상단 바 status bar 높이만큼 padding 추가 (뒤로가기 버튼 겹침 방지)
        val settingsBar = findViewById<android.view.View>(R.id.settingsTopBar)
        ViewCompat.setOnApplyWindowInsetsListener(settingsBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }

        findViewById<TextView>(R.id.btnSettingsBack).setOnClickListener {
            viewFlipper.displayedChild = SCREEN_MAIN
        }

        // 버튼 위치
        findViewById<Button>(R.id.btnSavePosition).setOnClickListener {
            startService(Intent(this, OverlayService::class.java).apply {
                action = ACTION_SAVE_POSITION
            })
        }
        findViewById<Button>(R.id.btnResetPosition).setOnClickListener {
            startService(Intent(this, OverlayService::class.java).apply {
                action = ACTION_RESET_POSITION
            })
        }

        setupOverlayBtnScaleRow()

        // 터치 ON일 때 투명도
        val tvAlphaOnLabel = findViewById<TextView>(R.id.tvAlphaOnLabel)
        val seekAlphaOn = findViewById<SeekBar>(R.id.seekAlphaOn)
        val savedAlphaOn = prefs.getInt(KEY_ALPHA_ON, 100)
        tvAlphaOnLabel.text = "터치 ON 투명도: $savedAlphaOn%"
        seekAlphaOn.progress = savedAlphaOn
        seekAlphaOn.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvAlphaOnLabel.text = "터치 ON 투명도: $progress%"
                if (fromUser) {
                    prefs.edit().putInt(KEY_ALPHA_ON, progress).apply()
                    startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                        action = ACTION_SET_ALPHA_ON
                        putExtra(EXTRA_ALPHA_VALUE, progress)
                    })
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 터치 OFF일 때 투명도
        val tvAlphaOffLabel = findViewById<TextView>(R.id.tvAlphaOffLabel)
        val seekAlphaOff = findViewById<SeekBar>(R.id.seekAlphaOff)
        val savedAlphaOff = prefs.getInt(KEY_ALPHA_OFF, 60)
        tvAlphaOffLabel.text = "터치 OFF 투명도: $savedAlphaOff%"
        seekAlphaOff.progress = savedAlphaOff
        seekAlphaOff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvAlphaOffLabel.text = "터치 OFF 투명도: $progress%"
                if (fromUser) {
                    prefs.edit().putInt(KEY_ALPHA_OFF, progress).apply()
                    startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                        action = ACTION_SET_ALPHA_OFF
                        putExtra(EXTRA_ALPHA_VALUE, progress)
                    })
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 터치 ON일 때 너비
        val tvWidthOnLabel = findViewById<TextView>(R.id.tvWidthOnLabel)
        val seekWidthOn = findViewById<SeekBar>(R.id.seekWidthOn)
        val savedWidthOn = prefs.getInt(KEY_WIDTH_ON, DEFAULT_WIDTH_ON)
        tvWidthOnLabel.text = "터치 ON 너비: $savedWidthOn%"
        seekWidthOn.progress = savedWidthOn
        seekWidthOn.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceAtLeast(50)
                if (fromUser && progress < 50) { sb?.progress = 50; return }
                tvWidthOnLabel.text = "터치 ON 너비: $clamped%"
                if (fromUser) {
                    prefs.edit().putInt(KEY_WIDTH_ON, clamped).apply()
                    startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                        action = ACTION_SET_SIZE_ON
                        putExtra(EXTRA_WIDTH_VALUE, clamped)
                        putExtra(EXTRA_HEIGHT_VALUE, prefs.getInt(KEY_HEIGHT_ON, DEFAULT_HEIGHT_ON))
                    })
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 터치 ON일 때 높이
        val tvHeightOnLabel = findViewById<TextView>(R.id.tvHeightOnLabel)
        val seekHeightOn = findViewById<SeekBar>(R.id.seekHeightOn)
        val savedHeightOn = prefs.getInt(KEY_HEIGHT_ON, DEFAULT_HEIGHT_ON)
        tvHeightOnLabel.text = "터치 ON 높이: $savedHeightOn%"
        seekHeightOn.progress = savedHeightOn
        seekHeightOn.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvHeightOnLabel.text = "터치 ON 높이: $progress%"
                if (fromUser) {
                    prefs.edit().putInt(KEY_HEIGHT_ON, progress).apply()
                    startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                        action = ACTION_SET_SIZE_ON
                        putExtra(EXTRA_WIDTH_VALUE, prefs.getInt(KEY_WIDTH_ON, DEFAULT_WIDTH_ON))
                        putExtra(EXTRA_HEIGHT_VALUE, progress)
                    })
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 터치 OFF일 때 너비
        val tvWidthOffLabel = findViewById<TextView>(R.id.tvWidthOffLabel)
        val seekWidthOff = findViewById<SeekBar>(R.id.seekWidthOff)
        val savedWidthOff = prefs.getInt(KEY_WIDTH_OFF, DEFAULT_WIDTH_OFF)
        tvWidthOffLabel.text = "터치 OFF 너비: $savedWidthOff%"
        seekWidthOff.progress = savedWidthOff
        seekWidthOff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceAtLeast(25)
                if (fromUser && progress < 25) { sb?.progress = 25; return }
                tvWidthOffLabel.text = "터치 OFF 너비: $clamped%"
                if (fromUser) {
                    prefs.edit().putInt(KEY_WIDTH_OFF, clamped).apply()
                    startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                        action = ACTION_SET_SIZE_OFF
                        putExtra(EXTRA_WIDTH_VALUE, clamped)
                        putExtra(EXTRA_HEIGHT_VALUE, prefs.getInt(KEY_HEIGHT_OFF, DEFAULT_HEIGHT_OFF))
                    })
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 터치 OFF일 때 높이
        val tvHeightOffLabel = findViewById<TextView>(R.id.tvHeightOffLabel)
        val seekHeightOff = findViewById<SeekBar>(R.id.seekHeightOff)
        val savedHeightOff = prefs.getInt(KEY_HEIGHT_OFF, DEFAULT_HEIGHT_OFF)
        tvHeightOffLabel.text = "터치 OFF 높이: $savedHeightOff%"
        seekHeightOff.progress = savedHeightOff
        seekHeightOff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvHeightOffLabel.text = "터치 OFF 높이: $progress%"
                if (fromUser) {
                    prefs.edit().putInt(KEY_HEIGHT_OFF, progress).apply()
                    startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                        action = ACTION_SET_SIZE_OFF
                        putExtra(EXTRA_WIDTH_VALUE, prefs.getInt(KEY_WIDTH_OFF, DEFAULT_WIDTH_OFF))
                        putExtra(EXTRA_HEIGHT_VALUE, progress)
                    })
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 설정값 초기화
        findViewById<Button>(R.id.btnResetAllSettings).setOnClickListener {
            prefs.edit()
                .putInt(KEY_ALPHA_ON,   100)
                .putInt(KEY_ALPHA_OFF,   60)
                .putInt(KEY_WIDTH_ON,  DEFAULT_WIDTH_ON)
                .putInt(KEY_WIDTH_OFF, DEFAULT_WIDTH_OFF)
                .putInt(KEY_HEIGHT_ON,  DEFAULT_HEIGHT_ON)
                .putInt(KEY_HEIGHT_OFF, DEFAULT_HEIGHT_OFF)
                .apply()
            // UI 갱신
            tvAlphaOnLabel.text   = "터치 ON 투명도: 100%";  seekAlphaOn.progress   = 100
            tvAlphaOffLabel.text  = "터치 OFF 투명도: 60%";  seekAlphaOff.progress  = 60
            tvWidthOnLabel.text   = "터치 ON 너비: ${DEFAULT_WIDTH_ON}%";   seekWidthOn.progress   = DEFAULT_WIDTH_ON
            tvHeightOnLabel.text  = "터치 ON 높이: ${DEFAULT_HEIGHT_ON}%";  seekHeightOn.progress  = DEFAULT_HEIGHT_ON
            tvWidthOffLabel.text  = "터치 OFF 너비: ${DEFAULT_WIDTH_OFF}%"; seekWidthOff.progress  = DEFAULT_WIDTH_OFF
            tvHeightOffLabel.text = "터치 OFF 높이: ${DEFAULT_HEIGHT_OFF}%";seekHeightOff.progress = DEFAULT_HEIGHT_OFF
            // 실행 중인 오버레이에 반영
            startService(Intent(this, OverlayService::class.java).apply { action = ACTION_RESET_SETTINGS })
            ToastHelper.show(this, "설정값이 초기화됐습니다.", Toast.LENGTH_SHORT)
        }

        // 예시 팝업 보기
        findViewById<Button>(R.id.btnShowExample).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                ToastHelper.show(this, "먼저 오버레이 권한을 허용해주세요.", Toast.LENGTH_SHORT)
                return@setOnClickListener
            }
            startService(Intent(this, OverlayService::class.java).apply {
                action = ACTION_SHOW_EXAMPLE
            })
        }

        // 실험용 기능
        findViewById<Button>(R.id.btnExperimentalFeatures).setOnClickListener {
            viewFlipper.displayedChild = SCREEN_EXPERIMENTAL
        }
    }

    // ── 선택지 자동 차단 설정 화면 ─────────────────────────────────────────
    private fun setupChoiceBlockScreen() {
        val topBar = findViewById<android.view.View>(R.id.choiceBlockTopBar)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }

        findViewById<TextView>(R.id.btnChoiceBlockBack).setOnClickListener {
            viewFlipper.displayedChild = SCREEN_EXPERIMENTAL
        }

        // 켜기/끄기
        val switchChoiceBlock: SwitchCompat = findViewById(R.id.switchChoiceBlock)
        val radioGroupChoiceBlockDuration: RadioGroup = findViewById(R.id.radioGroupChoiceBlockDuration)

        switchChoiceBlock.isChecked = prefs.getBoolean(KEY_CHOICE_BLOCK_ENABLED, false)
        radioGroupChoiceBlockDuration.isEnabled = switchChoiceBlock.isChecked
        for (i in 0 until radioGroupChoiceBlockDuration.childCount) {
            radioGroupChoiceBlockDuration.getChildAt(i).isEnabled = switchChoiceBlock.isChecked
        }

        when (prefs.getLong(KEY_CHOICE_BLOCK_MS, DEFAULT_CHOICE_BLOCK_MS)) {
            500L  -> radioGroupChoiceBlockDuration.check(R.id.radioBlock500)
            2000L -> radioGroupChoiceBlockDuration.check(R.id.radioBlock2000)
            else  -> radioGroupChoiceBlockDuration.check(R.id.radioBlock1000)
        }

        switchChoiceBlock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_CHOICE_BLOCK_ENABLED, isChecked).apply()
            radioGroupChoiceBlockDuration.isEnabled = isChecked
            for (i in 0 until radioGroupChoiceBlockDuration.childCount) {
                radioGroupChoiceBlockDuration.getChildAt(i).isEnabled = isChecked
            }
            updateChoiceBlockSummary()
        }

        radioGroupChoiceBlockDuration.setOnCheckedChangeListener { _, checkedId ->
            val ms = when (checkedId) {
                R.id.radioBlock500  -> 500L
                R.id.radioBlock2000 -> 2000L
                else                 -> 1000L
            }
            prefs.edit().putLong(KEY_CHOICE_BLOCK_MS, ms).apply()
            updateChoiceBlockSummary()
        }

        // 카테고리별 켜고 끄기
        val disabled = ChoiceBlockKeys.disabledCategories(this).toMutableSet()

        fun persistDisabled() {
            prefs.edit().putStringSet(ChoiceBlockKeys.KEY_DISABLED_CATEGORIES, disabled).apply()
            updateChoiceBlockSummary()
        }

        val mainContainer = findViewById<LinearLayout>(R.id.containerMainStoryCategories)
        val expContainer = findViewById<LinearLayout>(R.id.containerExpansionCategories)
        val storyContainer = findViewById<LinearLayout>(R.id.containerStoryCategories)

        val mainChecks = buildCategoryCheckboxes(mainContainer, ChoiceBlockKeys.mainStoryCategories, disabled, ::persistDisabled)
        val expChecks = buildCategoryCheckboxes(expContainer, ChoiceBlockKeys.expansionCategories, disabled, ::persistDisabled)
        val storyChecks = buildCategoryCheckboxes(storyContainer, ChoiceBlockKeys.storyCategories, disabled, ::persistDisabled)

        fun setAll(checks: List<android.widget.CheckBox>, categories: List<ChoiceBlockKeys.Category>, enable: Boolean) {
            categories.forEach { if (enable) disabled.remove(it.id) else disabled.add(it.id) }
            checks.forEach { it.isChecked = enable }
            persistDisabled()
        }

        findViewById<TextView>(R.id.btnMainAllOn).setOnClickListener {
            setAll(mainChecks, ChoiceBlockKeys.mainStoryCategories, true)
        }
        findViewById<TextView>(R.id.btnMainAllOff).setOnClickListener {
            setAll(mainChecks, ChoiceBlockKeys.mainStoryCategories, false)
        }
        findViewById<TextView>(R.id.btnExpAllOn).setOnClickListener {
            setAll(expChecks, ChoiceBlockKeys.expansionCategories, true)
        }
        findViewById<TextView>(R.id.btnExpAllOff).setOnClickListener {
            setAll(expChecks, ChoiceBlockKeys.expansionCategories, false)
        }
        findViewById<TextView>(R.id.btnStoryAllOn).setOnClickListener {
            setAll(storyChecks, ChoiceBlockKeys.storyCategories, true)
        }
        findViewById<TextView>(R.id.btnStoryAllOff).setOnClickListener {
            setAll(storyChecks, ChoiceBlockKeys.storyCategories, false)
        }
    }

    // 카테고리 목록 하나를 컨테이너에 체크박스로 그려 넣고, 변경 시 disabled 집합에 반영한다.
    // 반환값: 생성된 CheckBox 뷰 목록 (전체 켜기/끄기 버튼에서 UI 일괄 갱신용)
    private fun buildCategoryCheckboxes(
        container: LinearLayout,
        categories: List<ChoiceBlockKeys.Category>,
        disabled: MutableSet<String>,
        onChanged: () -> Unit
    ): List<android.widget.CheckBox> {
        container.removeAllViews()
        return categories.map { category ->
            android.widget.CheckBox(this).apply {
                text = category.label
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_text_body))
                isChecked = category.id !in disabled
                setPadding(0, 12, 0, 12)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) disabled.remove(category.id) else disabled.add(category.id)
                    onChanged()
                }
                container.addView(this)
            }
        }
    }

    // ── 도움말 화면 ────────────────────────────────────────────────────────
    private fun setupHelpScreen() {
        // 상단 바 status bar 높이만큼 padding 추가 (뒤로가기 버튼 겹침 방지)
        val helpBar = findViewById<android.view.View>(R.id.helpTopBar)
        ViewCompat.setOnApplyWindowInsetsListener(helpBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }

        // 문의 이메일 — 탭하면 주소를 클립보드에 복사
        findViewById<TextView>(R.id.btnContactEmail).setOnClickListener {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("이메일", "nigname2@gmail.com"))
            ToastHelper.show(this, "이메일 주소가 클립보드에 복사되었습니다.", android.widget.Toast.LENGTH_SHORT)
        }

        findViewById<TextView>(R.id.btnHelpBack).setOnClickListener {
            viewFlipper.displayedChild = SCREEN_MAIN
        }
    }

    // ── 공통 ───────────────────────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        updateStatus()
        updateChoiceBlockSummary()
        scope.launch { UpdateChecker.check(this@MainActivity) }
    }

    private val backPressCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when (viewFlipper.displayedChild) {
                SCREEN_CHOICE_BLOCK -> viewFlipper.displayedChild = SCREEN_EXPERIMENTAL
                SCREEN_EXPERIMENTAL -> viewFlipper.displayedChild = SCREEN_SETTINGS
                SCREEN_SETTINGS, SCREEN_HELP -> viewFlipper.displayedChild = SCREEN_MAIN
                else -> {
                    // 메인 화면 — 콜백을 잠시 끄고 시스템 기본 동작(앱 종료) 수행
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    private fun updateStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = Seoul2033AccessibilityService.isAlive(this)
        statusText.text = buildString {
            append("오버레이 권한: ${if (hasOverlay) "✓ 허용됨" else "✗ 미허용"}\n")
            append("접근성 서비스: ${if (hasAccessibility) "✓ 연결됨" else "✗ 미연결"}")
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            ToastHelper.show(this, "이미 오버레이 권한이 허용되어 있습니다.", Toast.LENGTH_SHORT)
        }
    }

    private fun requestAccessibilityPermission() {
        if (prefs.getBoolean("accessibility_declined", false)) {
            val dialog = AlertDialog.Builder(this)
                .setTitle("접근성 서비스 사용 안내")
                .setMessage("""
                    서울2033 리더는 게임 화면의 텍스트를 읽기 위해 접근성 서비스(AccessibilityService)를 사용합니다.

                    • 수집 정보: 서울2033 게임 화면에 표시된 텍스트
                    • 사용 목적: 나무위키 항목 자동 검색, 선택지 자동 차단(설정에서 켠 경우)
                    • 읽는 시점: '읽기' 버튼을 눌렀을 때. '선택지 자동 차단'을 켠 경우에는 게임 화면이 바뀔 때마다 추가로 읽습니다.
                    • 외부 전송: 없음 (기기 내에서만 처리되며 어떠한 데이터도 외부로 전송되지 않습니다)

                    접근성 서비스는 위 목적 외에 사용되지 않습니다.
                    거부 시 접근성 기능을 사용할 수 없습니다.
                """.trimIndent())
                .setPositiveButton("동의") { _, _ ->
                    prefs.edit()
                        .putBoolean("accessibility_disclosed", true)
                        .putBoolean("accessibility_declined", false)
                        .apply()
                    Log.d("MainActivity", "접근성 안내 재동의 (버튼)")
                    ToastHelper.show(this, "설정에서 '서울2033 리더'를 찾아 활성화해주세요.", Toast.LENGTH_LONG)
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("거부") { _, _ ->
                    markAccessibilityDeclined("재확인 버튼")
                }
                .setCancelable(true)
                .create()
            // 이미 declined=true인 상태에서 여는 재확인 창이므로, 뒤로가기/바깥 터치는
            // "선택 보류"로 보고 declined 상태를 그대로 유지한다 (별도 처리 불필요).
            dialog.setOnCancelListener {
                Log.d("MainActivity", "접근성 재확인 취소 (뒤로가기) → declined 상태 유지")
            }
            attachManualOutsideTouchDismiss(dialog) {
                Log.d("MainActivity", "접근성 재확인 취소 (바깥 터치) → declined 상태 유지")
            }
            dialog.show()
            return
        }
        ToastHelper.show(this, "설정에서 '서울2033 리더'를 찾아 활성화해주세요.", Toast.LENGTH_LONG)
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            ToastHelper.show(this, "먼저 오버레이 권한을 허용해주세요.", Toast.LENGTH_SHORT)
            return
        }
        startService(Intent(this, OverlayService::class.java))
    }
    private fun stopOverlay()  { stopService(Intent(this, OverlayService::class.java)) }

    companion object {
        private const val SCREEN_MAIN         = 0
        private const val SCREEN_SETTINGS     = 1
        private const val SCREEN_HELP         = 2
        private const val SCREEN_CHOICE_BLOCK = 3
        private const val SCREEN_EXPERIMENTAL = 4

        const val KEY_AUTO_START        = "auto_start_overlay"
        const val KEY_AUTO_STOP         = "auto_stop_overlay"
        const val KEY_CHOICE_BLOCK_ENABLED = "choice_block_enabled"
        const val KEY_CHOICE_BLOCK_MS   = "choice_block_ms"
        const val DEFAULT_CHOICE_BLOCK_MS = 1000L
        const val KEY_ALPHA_ON          = "web_alpha_on"
        const val KEY_ALPHA_OFF         = "web_alpha_off"
        const val KEY_WIDTH_ON          = "web_width_on"
        const val KEY_WIDTH_OFF         = "web_width_off"
        const val KEY_HEIGHT_ON         = "web_height_on"
        const val KEY_HEIGHT_OFF        = "web_height_off"
        const val DEFAULT_WIDTH_ON      = 92
        const val DEFAULT_WIDTH_OFF     = 92
        const val DEFAULT_HEIGHT_ON     = 50
        const val DEFAULT_HEIGHT_OFF    = 50
        const val ACTION_SAVE_POSITION  = "action_save_position"
        const val ACTION_RESET_POSITION = "action_reset_position"
        const val ACTION_SET_ALPHA_ON   = "action_set_alpha_on"
        const val ACTION_SET_ALPHA_OFF  = "action_set_alpha_off"
        const val ACTION_SET_SIZE_ON    = "action_set_size_on"
        const val ACTION_SET_SIZE_OFF   = "action_set_size_off"
        const val ACTION_RESET_SETTINGS = "action_reset_settings"
        const val ACTION_SHOW_EXAMPLE   = "action_show_example"
        const val ACTION_SET_BTN_SCALE  = "action_set_btn_scale"
        const val EXTRA_ALPHA_VALUE     = "extra_alpha_value"
        const val EXTRA_WIDTH_VALUE     = "extra_width_value"
        const val EXTRA_HEIGHT_VALUE    = "extra_height_value"
        const val EXTRA_BTN_SCALE_VALUE = "extra_btn_scale_value"
        const val KEY_OVERLAY_BTN_SCALE = "overlay_btn_scale"
        const val DEFAULT_BTN_SCALE     = 1f
        const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"

        private const val BASE_THEME_BTN_MARGIN_DP = 10
    }
}