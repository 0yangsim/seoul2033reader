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

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        statusText              = findViewById(R.id.statusText)
        btnRequestOverlay       = findViewById(R.id.btnRequestOverlay)
        btnRequestAccessibility = findViewById(R.id.btnRequestAccessibility)
        btnStart                = findViewById(R.id.btnStart)
        btnStop                 = findViewById(R.id.btnStop)
        btnManageItems          = findViewById(R.id.btnManageItems)
        switchAutoStart         = findViewById(R.id.switchAutoStart)

        // 저장된 설정 불러오기 (기본값 false)
        switchAutoStart.isChecked = prefs.getBoolean(KEY_AUTO_START, false)

        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_START, isChecked).apply()
            val msg = if (isChecked) "서울2033 실행 시 자동으로 오버레이가 시작됩니다."
                      else "자동 시작이 꺼졌습니다."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnRequestOverlay.setOnClickListener       { requestOverlayPermission() }
        btnRequestAccessibility.setOnClickListener { requestAccessibilityPermission() }
        btnStart.setOnClickListener                { startOverlay() }
        btnStop.setOnClickListener                 { stopOverlay() }
        btnManageItems.setOnClickListener          { startActivity(Intent(this, CustomItemActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        // 앱 실행 시 업데이트 확인 (백그라운드)
        scope.launch { UpdateChecker.check(this@MainActivity) }
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
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        } else {
            Toast.makeText(this, "이미 오버레이 권한이 허용되어 있습니다.", Toast.LENGTH_SHORT).show()
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
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 오버레이 권한을 허용해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        if (!Seoul2033AccessibilityService.isAlive(this)) {
            Toast.makeText(this, "접근성 서비스를 먼저 활성화해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        startService(Intent(this, OverlayService::class.java))
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "오버레이가 중지되었습니다.", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val KEY_AUTO_START = "auto_start"
    }
}
