package com.seoul2033wiki

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class Seoul2033AccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Seoul2033Accessibility"
        const val TARGET_PACKAGE_BACKER = "com.banjihagames.seoul2033_backer"
        const val TARGET_PACKAGE_FREE   = "com.banjihagames.seoul2033"

        private fun isTargetPackage(pkg: String) =
            pkg == TARGET_PACKAGE_BACKER || pkg == TARGET_PACKAGE_FREE

        @Volatile
        var instance: Seoul2033AccessibilityService? = null
            private set

        fun isAlive(ctx: Context): Boolean {
            if (instance == null) return false
            val component = ComponentName(ctx, Seoul2033AccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val active = enabled.split(':').any { it.equals(component, ignoreCase = true) }
            if (!active) {
                instance = null
                Log.d(TAG, "접근성 서비스 비활성화 감지 → instance 해제")
            }
            return active
        }

        fun isAutoStartEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_AUTO_START, false)

        fun isAutoStopEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_AUTO_STOP, false)

        // OverlayService.onDestroy()에서 호출 — 수동 중지 시 플래그 리셋
        fun notifyOverlayStopped() {
            instance?.isOverlayRunning = false
            instance?.isGameForeground = false
            Log.d(TAG, "오버레이 수동 중지 감지 → 자동 시작 플래그 리셋")
        }

        // OverlayService.onCreate()에서 호출 — 수동 시작 시에도 플래그 동기화
        fun notifyOverlayStarted() {
            instance?.isOverlayRunning = true
            // 게임이 현재 포그라운드에 있는지 확인해서 isGameForeground 동기화
            val inst = instance ?: return
            val gameVisible = inst.windows?.any {
                isTargetPackage(it.root?.packageName?.toString() ?: "")
            } ?: false
            inst.isGameForeground = gameVisible
            Log.d(TAG, "오버레이 수동 시작 감지 → 플래그 동기화 (게임포그라운드=$gameVisible)")
        }
    }

    private var isGameForeground = false
    private var isOverlayRunning = false
    // startForegroundService() 직후 연속 이벤트로 인한 즉시 종료 방지
    private var overlayStartedAt = 0L
    private val OVERLAY_START_COOLDOWN_MS = 3_000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isGameForeground = false
        isOverlayRunning = false
        Log.d(TAG, "AccessibilityService 연결됨")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isGameForeground = false
        isOverlayRunning = false
        Log.d(TAG, "AccessibilityService 해제됨")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 게임 윈도우가 완전히 사라졌는지 감지 (앱 완전 종료 시)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (!isAutoStopEnabled(this)) return
            if (!isOverlayRunning) return
            val gameVisible = windows?.any {
                isTargetPackage(it.root?.packageName?.toString() ?: "")
            } ?: false
            if (!gameVisible) {
                isGameForeground = false
                isOverlayRunning = false
                Log.d(TAG, "서울2033 윈도우 사라짐 감지 → 오버레이 자동 종료")
                stopService(Intent(this, OverlayService::class.java))
            }
            return
        }

        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!isAutoStartEnabled(this)) return

        val pkg = event.packageName?.toString() ?: return

        when {
            // 서울2033이 포그라운드로 왔고 오버레이가 꺼져 있으면 시작
            isTargetPackage(pkg) && !isOverlayRunning -> {
                isGameForeground = true
                isOverlayRunning = true
                overlayStartedAt = System.currentTimeMillis()
                Log.d(TAG, "서울2033 포그라운드 감지 ($pkg) → 오버레이 자동 시작")
                startForegroundService(Intent(this, OverlayService::class.java))
            }
            // 서울2033이 아닌 앱이 포그라운드로 왔고, 서울2033이 켜져 있던 상태였으면 종료
            !isTargetPackage(pkg) && isGameForeground && isOverlayRunning -> {
                if (!isAutoStopEnabled(this)) {
                    isGameForeground = false
                    return
                }
                // 시작 직후 쿨다운 중이면 종료 무시 (startForeground 5초 타임아웃 방지)
                if (System.currentTimeMillis() - overlayStartedAt < OVERLAY_START_COOLDOWN_MS) {
                    Log.d(TAG, "오버레이 시작 쿨다운 중 → 종료 이벤트 무시")
                    return
                }
                val gameStillVisible = windows?.any {
                    isTargetPackage(it.root?.packageName?.toString() ?: "")
                } ?: false

                if (!gameStillVisible) {
                    isGameForeground = false
                    isOverlayRunning = false
                    Log.d(TAG, "서울2033 이탈 감지 → 오버레이 자동 종료")
                    stopService(Intent(this, OverlayService::class.java))
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun extractGameText(): String? {
        val windows = windows ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString()?.let { isTargetPackage(it) } != true) {
                root.recycle()
                continue
            }
            val texts = LinkedHashSet<String>()
            collectTexts(root, texts)
            root.recycle()
            if (texts.isEmpty()) continue
            return texts.joinToString("\n")
        }
        Log.d(TAG, "서울2033 윈도우를 찾을 수 없음")
        return null
    }

    @Suppress("DEPRECATION")
    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableSet<String>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) out.add(text)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out)
            child.recycle()
        }
    }
}