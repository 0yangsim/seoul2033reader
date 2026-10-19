package com.seoul2033wiki

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 토스트 헬퍼 — 새 토스트를 띄울 때 아직 떠 있거나 대기 중인 이전 토스트를 먼저 취소한다.
 * (Toast.makeText().show()를 그대로 쓰면 연타 시 토스트가 큐에 줄줄이 쌓임)
 *
 * MainActivity / OverlayService가 같은 프로세스라 이전 토스트 참조를 공유한다.
 * 어느 스레드에서 불러도 메인 스레드에서 실행된다.
 */
object ToastHelper {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: Toast? = null

    fun show(context: Context, message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        val app = context.applicationContext
        val action = Runnable {
            current?.cancel()
            current = Toast.makeText(app, message, duration).also { it.show() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }
}
