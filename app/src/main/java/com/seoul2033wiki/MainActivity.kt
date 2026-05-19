package com.seoul2033wiki

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnRequestOverlay: Button
    private lateinit var btnRequestAccessibility: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnManageItems: Button

    private lateinit var switchAutoStart: SwitchCompat
    private lateinit var switchHintDefault: SwitchCompat

    private lateinit var prefs: SharedPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        statusText = findViewById(R.id.statusText)

        btnRequestOverlay = findViewById(R.id.btnRequestOverlay)
        btnRequestAccessibility = findViewById(R.id.btnRequestAccessibility)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnManageItems = findViewById(R.id.btnManageItems)

        switchAutoStart = findViewById(R.id.switchAutoStart)
        switchHintDefault = findViewById(R.id.switchHintDefault)

        // 서울2033 실행 시 자동 시작
        switchAutoStart.isChecked =
            prefs.getBoolean(KEY_AUTO_START, false)

        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_START, isChecked).apply()

            val msg =
                if (isChecked)
                    "서울2033 실행 시 자동으로 오버레이가 시작됩니다."
                else
                    "자동 시작이 꺼졌습니다."

            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 힌트 바로 표시 기능
        switchHintDefault.isChecked =
            prefs.getBoolean(KEY_HINT_DEFAULT, false)

        switchHintDefault.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HINT_DEFAULT, isChecked).apply()
        }

        btnRequestOverlay.setOnClickListener {
            requestOverlayPermission()
        }

        btnRequestAccessibility.setOnClickListener {
            requestAccessibilityPermission()
        }

        btnStart.setOnClickListener {
            startOverlay()
        }

        btnStop.setOnClickListener {
            stopOverlay()
        }

        btnManageItems.setOnClickListener {
            startActivity(Intent(this, CustomItemActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()

        updateStatus()

        scope.launch {
            UpdateChecker.check(this@MainActivity)
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
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            Toast.makeText(
                this,
                "이미 오버레이 권한이 허용되어 있습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun requestAccessibilityPermission() {
        Toast.makeText(
            this,
            "설정에서 '서울2033 리더'를 찾아 활성화해주세요.",
            Toast.LENGTH_LONG
        ).show()

        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startOverlay() {
        startService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "오버레이 시작", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "오버레이 중지", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val KEY_AUTO_START = "auto_start_overlay"
        const val KEY_HINT_DEFAULT = "hint_default"
    }
}
