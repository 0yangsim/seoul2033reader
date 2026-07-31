package com.seoul2033wiki

import android.content.Context

/**
 * 선택지 자동 차단 트리거 키 + 카테고리 목록.
 *
 * 인식된 게임 화면 텍스트(rawText)에 아래 키구문 중 하나라도 포함되면
 * 화면 하단 선택지를 잠시 차단한다 (OverlayService.autoDetectAndBlockChoice 참고).
 *
 * ── 카테고리 ──────────────────────────────────────────────────────────────
 *   CATEGORIES에 등록된 카테고리만 "선택지 자동 차단 설정" 화면에 토글로 나타난다.
 *   필요한 카테고리를 등록하고, KEYS에서 categoryId로 연결해서 쓰면 됨.
 *
 * ── 사용법 ────────────────────────────────────────────────────────────────
 *   1) 새 카테고리가 필요하면 CATEGORIES에 추가 (id는 "확장팩:", "이야기:", "메인스토리:" 형식)
 *   2) KEYS에 차단하고 싶은 인카운터의 키구문을 KeyEntry(phrase, categoryId) 형태로 추가
 *      각 리졸버(HardModeEncounterResolver 등)의 ENCOUNTER_MAP에 있는 key와
 *      동일한 방식 — 공백·특수문자는 무시하고 비교하므로 원문 그대로 붙여넣으면 됨.
 *
 * ── 매칭 방식 ─────────────────────────────────────────────────────────────
 *   전체 resolver 매칭(resolveEntry)을 타지 않고, 여기 등록된 키만 부분 문자열로
 *   포함 여부를 확인하기 때문에 resolveEntry보다 훨씬 가볍다.
 */
object ChoiceBlockKeys {

    enum class Group(val label: String) {
        MAIN_STORY("메인스토리"),
        EXPANSION("확장팩"),
        STORY("이야기")
    }

    data class Category(val id: String, val label: String, val group: Group)
    data class KeyEntry(val phrase: String, val categoryId: String)

    // 설정 화면에 표시할 카테고리 — 필요한 것만 등록해서 사용
    val CATEGORIES: List<Category> = listOf(
        // Category("메인스토리:도봉산", "메인 스토리 : 도봉산", Group.MAIN_STORY),
        Category("확장팩:황소너구리 왕국", "황소너구리 왕국", Group.EXPANSION),
        Category("이야기:이북 리더", "이북 리더", Group.STORY)
        // TODO: 카테고리 추가 시 여기에 등록
        // Category("확장팩:확장팩명", "확장팩명", Group.EXPANSION),
        // Category("이야기:이야기명", "이야기명", Group.STORY),
    )

    val KEYS: List<KeyEntry> = listOf(
        // KeyEntry("핵전쟁으로세상이멸망하고난뒤", "메인스토리:도봉산")
        // TODO: 차단할 인카운터의 키구문을 여기에 추가
        KeyEntry("알록달록하고왠지모르겠지만북슬북슬한전단지가담벼락에붙어있습니다", "확장팩:황소너구리 왕국"),
        KeyEntry("아글쎄오해라니까요한적한공터를지나는데어딘가에서시끌벅적하게실랑", "이야기:이북 리더"),
        KeyEntry("당신은오랜만에북커버그의작업장을찾아가보기로합니다", "이야기:이북 리더"),
        // KeyEntry("예시키구문1", "확장팩:황소너구리 왕국"),
        // KeyEntry("예시키구문2", "이야기:이북 리더"),
    )

    val mainStoryCategories: List<Category> get() = CATEGORIES.filter { it.group == Group.MAIN_STORY }
    val expansionCategories: List<Category> get() = CATEGORIES.filter { it.group == Group.EXPANSION }
    val storyCategories: List<Category> get() = CATEGORIES.filter { it.group == Group.STORY }

    private const val PREFS_NAME = "settings"
    const val KEY_DISABLED_CATEGORIES = "choice_block_disabled_categories"

    private val NORMALIZE_REGEX = Regex("""[^\p{L}\p{N}]""")
    private fun normalize(s: String) = s.replace(NORMALIZE_REGEX, "").lowercase()

    private data class NormalizedEntry(val normalized: String, val categoryId: String)

    private val normalizedKeys: List<NormalizedEntry> by lazy {
        KEYS.map { NormalizedEntry(normalize(it.phrase), it.categoryId) }
    }

    /**
     * rawText 안에 등록된 키 중, 꺼지지 않은 카테고리에 속한 키가 하나라도 포함돼 있으면 true.
     * disabledCategoryIds에 포함된 카테고리의 키구문은 매칭 대상에서 제외된다.
     */
    fun matches(rawText: String, disabledCategoryIds: Set<String>): Boolean {
        if (normalizedKeys.isEmpty()) return false
        val clean = normalize(rawText)
        return normalizedKeys.any { entry ->
            entry.categoryId !in disabledCategoryIds && clean.contains(entry.normalized)
        }
    }

    /** 설정 화면에서 사용자가 끈 카테고리 id 집합을 읽어온다. */
    fun disabledCategories(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_DISABLED_CATEGORIES, emptySet()) ?: emptySet()
}