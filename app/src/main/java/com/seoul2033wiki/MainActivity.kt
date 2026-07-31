package com.seoul2033wiki

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var prefs: SharedPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
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
    private fun showAccessibilityDisclosureIfNeeded() {
        if (prefs.getBoolean("accessibility_disclosed", false)) return
        if (prefs.getBoolean("accessibility_declined", false)) return

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
            }
            .setNegativeButton("거부") { _, _ ->
                markAccessibilityDeclined("버튼")
            }
            .setCancelable(true)
            .create()
        // 뒤로가기는 시스템이 알아서 cancel()을 호출해주지만, 일부 기기에서는
        // setCanceledOnTouchOutside(true)만으로 바깥 터치 dismiss가 씹히는 경우가 있어
        // 좌표를 직접 계산해서 판정한다.
        dialog.setOnCancelListener {
            markAccessibilityDeclined("뒤로가기")
        }
        attachManualOutsideTouchDismiss(dialog) { markAccessibilityDeclined("바깥 터치") }
        dialog.show()
    }

    // 거부 상태 저장 + 로그 + 사용자 피드백 (바깥 터치 시에도 실제로 반영됐음을 알 수 있도록)
    private fun markAccessibilityDeclined(via: String) {
        prefs.edit().putBoolean("accessibility_declined", true).apply()
        Log.d("MainActivity", "접근성 안내 거부됨 ($via) → accessibility_declined=true")
        Toast.makeText(this, "접근성 서비스 거부됨 — 접근성 기능을 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(
                this,
                if (isChecked) "서울2033 실행 시 자동으로 오버레이가 시작됩니다." else "자동 시작이 꺼졌습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchAutoStop = findViewById(R.id.switchAutoStop)
        switchAutoStop.isChecked = prefs.getBoolean(KEY_AUTO_STOP, false)
        switchAutoStop.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_STOP, isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "서울2033 종료 시 자동으로 오버레이가 꺼집니다." else "자동 종료가 꺼졌습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<Button>(R.id.btnRequestOverlay).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnRequestAccessibility).setOnClickListener { requestAccessibilityPermission() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startOverlay() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopOverlay() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { viewFlipper.displayedChild = SCREEN_SETTINGS }
        findViewById<Button>(R.id.btnHelp).setOnClickListener { viewFlipper.displayedChild = SCREEN_HELP }
    }

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
            Toast.makeText(this, "설정값이 초기화됐습니다.", Toast.LENGTH_SHORT).show()
        }

        // 예시 팝업 보기
        findViewById<Button>(R.id.btnShowExample).setOnClickListener {
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
                setTextColor(0xFFBBBBBB.toInt())
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
            android.widget.Toast.makeText(this, "이메일 주소가 클립보드에 복사되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "이미 오버레이 권한이 허용되어 있습니다.", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "설정에서 '서울2033 리더'를 찾아 활성화해주세요.", Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, "설정에서 '서울2033 리더'를 찾아 활성화해주세요.", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 오버레이 권한을 허용해주세요.", Toast.LENGTH_SHORT).show()
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
        const val EXTRA_ALPHA_VALUE     = "extra_alpha_value"
        const val EXTRA_WIDTH_VALUE     = "extra_width_value"
        const val EXTRA_HEIGHT_VALUE    = "extra_height_value"
    }
}