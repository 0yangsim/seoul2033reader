package com.seoul2033wiki

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri

/**
 * 인식 결과를 표시하는 Activity (레거시).
 *
 * 현재는 OverlayService.showResultPopup()이 WindowManager 팝업으로 직접 처리하므로
 * 이 Activity는 일반적인 경로에서 실행되지 않는다.
 * 외부 Intent 등 Activity 컨텍스트가 필요한 경우를 위해 유지.
 */
class ResultPopupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_popup)

        val title = intent.getStringExtra("title") ?: ""
        val type = intent.getStringExtra("type") ?: ""
        val url = intent.getStringExtra("url") ?: ""

        findViewById<TextView>(R.id.tvType).text = getString(R.string.label_type, type)
        findViewById<TextView>(R.id.tvTitle).text = title
        findViewById<TextView>(R.id.tvUrl).text = url

        findViewById<Button>(R.id.btnOpen).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            finish()
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
    }
}
