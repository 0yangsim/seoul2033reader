package com.seoul2033wiki

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnRequestOverlay: Button
    private lateinit var btnRequestAccessibility: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnManageItems: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText               = findViewById(R.id.statusText)
        btnRequestOverlay        = findViewById(R.id.btnRequestOverlay)
        btnRequestAccessibility  = findViewById(R.id.btnRequestAccessibility)
        btnStart                 = findViewById(R.id.btnStart)
        btnStop                  = findViewById(R.id.btnStop)
        btnManageItems           = findViewById(R.id.btnManageItems)

        btnRequestOverlay.setOnClickListener       { requestOverlayPermission() }
        btnRequestAccessibility.setOnClickListener { requestAccessibilityPermission() }
        btnStart.setOnClickListener                { startOverlay() }
        btnStop.setOnClickListener                 { stopOverlay() }
        btnManageItems.setOnClickListener          { startActivity(Intent(this, CustomItemActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
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
}
