package com.seoul2033wiki

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 앱 자체 다크모드/라이트모드 전환 관리.
 *
 * 폰의 시스템 다크모드 설정과는 완전히 무관하게 동작한다.
 * AppCompatDelegate.setDefaultNightMode()에 MODE_NIGHT_YES / MODE_NIGHT_NO를
 * 직접 지정하고 MODE_NIGHT_FOLLOW_SYSTEM은 절대 쓰지 않는다.
 *
 * 기본값: 다크모드 (앱을 처음 설치했을 때).
 */
object ThemeManager {

    private const val PREFS_NAME = "settings"
    private const val KEY_DARK_MODE = "app_dark_mode"

    /**
     * 저장된 모드를 실제로 적용.
     * MainActivity.onCreate()에서 super.onCreate()보다 먼저 호출해야 한다.
     */
    fun applySavedTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode(context)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun isDarkMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, true) // 기본값: 다크모드

    /**
     * 토글 버튼에서 호출. 값을 저장하고 즉시 반영한 뒤 새 상태(다크 여부)를 반환한다.
     * 호출부에서 activity.recreate()를 실행해야 화면에 반영된다.
     */
    fun toggle(context: Context): Boolean {
        val newIsDark = !isDarkMode(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, newIsDark)
            .apply()
        AppCompatDelegate.setDefaultNightMode(
            if (newIsDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        return newIsDark
    }
}