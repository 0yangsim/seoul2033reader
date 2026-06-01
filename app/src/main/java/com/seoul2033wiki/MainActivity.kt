package com.seoul2033wiki

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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

        showAccessibilityDisclosureIfNeeded()
        setupMainScreen()
        setupSettingsScreen()
        setupHelpScreen()
    }

    // ── 접근성 서비스 명시적 공개 동의 ───────────────────────────────────────
    private fun showAccessibilityDisclosureIfNeeded() {
        if (prefs.getBoolean("accessibility_disclosed", false)) return
        if (prefs.getBoolean("accessibility_declined", false)) return

        AlertDialog.Builder(this)
            .setTitle("접근성 서비스 사용 안내")
            .setMessage("""
                서울2033 리더는 게임 화면의 텍스트를 읽기 위해 접근성 서비스(AccessibilityService)를 사용합니다.

                • 수집 정보: 서울2033 게임 화면에 표시된 텍스트
                • 사용 목적: 나무위키 항목 자동 검색
                • 외부 전송: 없음 (기기 내에서만 처리되며 어떠한 데이터도 외부로 전송되지 않습니다)

                접근성 서비스는 위 목적 외에 사용되지 않습니다.
                거부 시 접근성 기능을 사용할 수 없습니다.
            """.trimIndent())
            .setPositiveButton("동의") { _, _ ->
                prefs.edit().putBoolean("accessibility_disclosed", true).apply()
            }
            .setNegativeButton("거부") { _, _ ->
                prefs.edit().putBoolean("accessibility_declined", true).apply()
                Toast.makeText(this, "접근성 서비스 거부됨 — 접근성 기능을 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
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

    // ── 설정 화면 ──────────────────────────────────────────────────────────
    private fun setupSettingsScreen() {
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
        tvAlphaOnLabel.text = "터치 ON일 때: $savedAlphaOn%"
        seekAlphaOn.progress = savedAlphaOn
        seekAlphaOn.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvAlphaOnLabel.text = "터치 ON일 때: $progress%"
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
        tvAlphaOffLabel.text = "터치 OFF일 때: $savedAlphaOff%"
        seekAlphaOff.progress = savedAlphaOff
        seekAlphaOff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvAlphaOffLabel.text = "터치 OFF일 때: $progress%"
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

        // 예시 팝업 보기
        findViewById<Button>(R.id.btnShowExample).setOnClickListener {
            startService(Intent(this, OverlayService::class.java).apply {
                action = ACTION_SHOW_EXAMPLE
            })
        }
    }

    // ── 도움말 화면 ────────────────────────────────────────────────────────
    private fun setupHelpScreen() {
        findViewById<TextView>(R.id.btnHelpBack).setOnClickListener {
            viewFlipper.displayedChild = SCREEN_MAIN
        }
    }

    // ── 공통 ───────────────────────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        updateStatus()
        scope.launch { UpdateChecker.check(this@MainActivity) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (viewFlipper.displayedChild != SCREEN_MAIN) {
            viewFlipper.displayedChild = SCREEN_MAIN
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
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
            AlertDialog.Builder(this)
                .setTitle("접근성 서비스 사용 안내")
                .setMessage("""
                    서울2033 리더는 게임 화면의 텍스트를 읽기 위해 접근성 서비스(AccessibilityService)를 사용합니다.

                    • 수집 정보: 서울2033 게임 화면에 표시된 텍스트
                    • 사용 목적: 나무위키 항목 자동 검색
                    • 외부 전송: 없음 (기기 내에서만 처리되며 어떠한 데이터도 외부로 전송되지 않습니다)

                    접근성 서비스는 위 목적 외에 사용되지 않습니다.
                    거부 시 접근성 기능을 사용할 수 없습니다.
                """.trimIndent())
                .setPositiveButton("동의") { _, _ ->
                    prefs.edit()
                        .putBoolean("accessibility_disclosed", true)
                        .putBoolean("accessibility_declined", false)
                        .apply()
                    Toast.makeText(this, "설정에서 '서울2033 리더'를 찾아 활성화해주세요.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("거부") { _, _ ->
                    Toast.makeText(this, "접근성 서비스 거부됨 — 접근성 기능을 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
                }
                .setCancelable(false)
                .show()
            return
        }
        Toast.makeText(this, "설정에서 '서울2033 리더'를 찾아 활성화해주세요.", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startOverlay() { startService(Intent(this, OverlayService::class.java)) }
    private fun stopOverlay()  { stopService(Intent(this, OverlayService::class.java)) }

    companion object {
        private const val SCREEN_MAIN     = 0
        private const val SCREEN_SETTINGS = 1
        private const val SCREEN_HELP     = 2

        const val KEY_AUTO_START        = "auto_start_overlay"
        const val KEY_AUTO_STOP         = "auto_stop_overlay"
        const val KEY_ALPHA_ON          = "web_alpha_on"
        const val KEY_ALPHA_OFF         = "web_alpha_off"
        const val ACTION_SAVE_POSITION  = "action_save_position"
        const val ACTION_RESET_POSITION = "action_reset_position"
        const val ACTION_SET_ALPHA_ON   = "action_set_alpha_on"
        const val ACTION_SET_ALPHA_OFF  = "action_set_alpha_off"
        const val ACTION_SHOW_EXAMPLE   = "action_show_example"
        const val EXTRA_ALPHA_VALUE     = "extra_alpha_value"
    }
}