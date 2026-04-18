package com.seoul2033wiki

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 서울2033 앱의 화면 텍스트를 Accessibility API로 읽어 오버레이 서비스에 전달.
 *
 * ── 동작 방식 ──────────────────────────────────────────────────────────────
 *  - OverlayService가 버튼 탭을 감지하면 extractGameText()를 호출
 *  - 현재 화면의 노드 트리에서 텍스트를 수집해 반환
 *  - 이벤트 기반 자동 감지는 하지 않음 (버튼 탭 시에만 동작)
 *
 * ── 텍스트 수집 전략 ────────────────────────────────────────────────────────
 *  - 패키지가 com.banjihagames.seoul2033_backer 인 윈도우만 탐색
 *  - 모든 텍스트 노드를 깊이 우선으로 수집
 *  - 중복 제거 후 줄바꿈으로 합쳐서 반환
 */
class Seoul2033AccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Seoul2033Accessibility"
        private const val TARGET_PACKAGE = "com.banjihagames.seoul2033_backer"

        // OverlayService에서 참조할 싱글톤 인스턴스
        @Volatile
        var instance: Seoul2033AccessibilityService? = null
            private set

        /**
         * instance가 존재하고 시스템에서도 활성화 상태인지 확인.
         * 앱 강제종료 시 onDestroy()가 호출되지 않아 instance가 죽은 객체를
         * 가리키는 문제를 방어한다.
         * 비활성화 감지 시 instance를 null로 정리해 다음 호출에서 재확인하지 않도록 함.
         */
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
                // 시스템에서 비활성화됨 → 죽은 instance 정리
                instance = null
                Log.d(TAG, "접근성 서비스 비활성화 감지 → instance 해제")
            }
            return active
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService 연결됨")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "AccessibilityService 해제됨")
    }

    // 이벤트는 사용하지 않음 (버튼 탭 시 pull 방식으로 동작)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * 현재 서울2033 화면에서 텍스트를 수집해 반환.
     * 서울2033 윈도우를 찾지 못하면 null 반환.
     *
     * AccessibilityNodeInfo.recycle()은 API 33부터 deprecated.
     * 시스템이 자동으로 관리하므로 호출 불필요.
     */
    @Suppress("DEPRECATION")
    fun extractGameText(): String? {
        val windows = windows ?: return null

        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != TARGET_PACKAGE) {
                root.recycle()
                continue
            }
            val texts = LinkedHashSet<String>()  // O(1) 중복 체크 (List.contains는 O(n))
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
