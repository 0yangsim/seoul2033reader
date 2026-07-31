package com.seoul2033wiki

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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

        fun isChoiceBlockEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_CHOICE_BLOCK_ENABLED, false)

        // 콘텐츠 변경 이벤트 스로틀 — 연속 이벤트라도 최대 100ms 안에는 반드시 한 번 확인
        private const val CONTENT_CHECK_THROTTLE_MS = 100L

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
            inst.isGameForeground = inst.isGameVisible()
            Log.d(TAG, "오버레이 수동 시작 감지 → 플래그 동기화 (게임포그라운드=${inst.isGameForeground})")
        }
    }

    private var isGameForeground = false
    private var isOverlayRunning = false
    // startForegroundService() 직후 연속 이벤트로 인한 즉시 종료 방지
    private var overlayStartedAt = 0L
    private val OVERLAY_START_COOLDOWN_MS = 3_000L

    // 선택지 자동 차단용 콘텐츠 변경 스로틀
    private val contentChangeHandler = Handler(Looper.getMainLooper())
    private var lastFirstLine: String? = null
    private var throttleScheduled = false

    private val contentChangeRunnable = Runnable {
        throttleScheduled = false

        // 1) 경량 확인 — 텍스트 있는 첫 노드까지만 순회 (전체 트리 순회보다 훨씬 쌈)
        val firstText = extractFirstText()
        if (firstText.isNullOrEmpty() || firstText == lastFirstLine) return@Runnable
        Log.d(TAG, "선택지차단: 첫 노드 변경 감지 → '${firstText.take(20)}...'")
        lastFirstLine = firstText

        // 2) 실제로 바뀐 경우에만 전체 추출 + 매칭 수행
        val fullText = extractGameText() ?: return@Runnable
        Log.d(TAG, "선택지차단: 전체 추출 완료 (${fullText.length}자) → OverlayService 전달")
        OverlayService.instance?.autoDetectAndBlockChoice(fullText)
    }

    /** 현재 윈도우 목록에 서울2033 패키지가 보이는지 확인 */
    private fun isGameVisible(): Boolean =
        windows?.any { isTargetPackage(it.root?.packageName?.toString() ?: "") } == true

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isGameForeground = false
        isOverlayRunning = false
        lastFirstLine = null
        Log.d(TAG, "AccessibilityService 연결됨")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isGameForeground = false
        isOverlayRunning = false
        lastFirstLine = null
        throttleScheduled = false
        contentChangeHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "AccessibilityService 해제됨")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 선택지 자동 차단: 연속 이벤트가 몰려도 최대 100ms 안에는 반드시 한 번 경량 확인 실행
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (!isOverlayRunning) return
            if (!isChoiceBlockEnabled(this)) return
            val pkg = event.packageName?.toString() ?: return
            if (!isTargetPackage(pkg)) return
            if (!throttleScheduled) {
                throttleScheduled = true
                contentChangeHandler.postDelayed(contentChangeRunnable, CONTENT_CHECK_THROTTLE_MS)
            }
            return
        }

        // 게임 윈도우가 완전히 사라졌는지 감지 (앱 완전 종료 시)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (!isAutoStopEnabled(this)) return
            if (!isOverlayRunning) return
            if (!isGameVisible()) {
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
                val gameStillVisible = isGameVisible()

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

    // 변경 감지 전용 — 텍스트가 있는 첫 노드를 찾으면 그 즉시 순회를 멈춘다.
    // extractGameText()처럼 트리 전체를 다 훑지 않아서 상시 호출해도 부담이 적음.
    //
    // "진행도" 라벨이 맨 처음(첫 유의미한 텍스트)으로 나오면 인벤토리 화면이 열려 있다는
    // 뜻이므로 순회를 아예 중단하고 null을 반환한다. 인벤토리 중에는 선택지 차단이
    // 필요 없고, lastFirstLine도 갱신하지 않아 인벤토리를 닫은 뒤 실제 콘텐츠 변화는
    // 그대로 정상 감지된다.
    @Suppress("DEPRECATION")
    private fun extractFirstText(): String? {
        val windows = windows ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString()?.let { isTargetPackage(it) } != true) {
                root.recycle()
                continue
            }
            val abort = booleanArrayOf(false)
            val found = findFirstText(root, abort)
            root.recycle()
            if (abort[0]) return null
            if (found != null) return found
        }
        return null
    }

    // findFirstText 변경 감지에서 건너뛸 정적 HUD 라벨.
    // 화면 상단에 항상 고정으로 떠 있어서 변경 감지 기준으로 쓰면
    // 최초 1회 이후 영원히 "변경 없음"으로 오판되어 선택지 자동 차단이 멈춘다.
    private val hudSkipLabels = setOf("체력", "멘탈", "돈")

    // 맨 처음(첫 유의미한 텍스트)으로 이 라벨이 나오면 인벤토리 화면 → 순회 전체 중단.
    private val abortOnFirstLabels = setOf("진행도")

    @Suppress("DEPRECATION")
    private fun findFirstText(node: AccessibilityNodeInfo, abort: BooleanArray): String? {
        if (abort[0]) return null
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            if (abortOnFirstLabels.any { text.startsWith(it) }) {
                abort[0] = true
                return null
            }
            if (hudSkipLabels.none { text.startsWith(it) }) return text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstText(child, abort)
            child.recycle()
            if (abort[0]) return null
            if (found != null) return found
        }
        return null
    }
}