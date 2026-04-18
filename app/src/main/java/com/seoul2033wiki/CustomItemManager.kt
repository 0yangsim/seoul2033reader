package com.seoul2033wiki

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 사용자가 앱에서 직접 등록한 이야기·레벨·시즌패스·확장팩 목록을 관리한다.
 *
 * ── 목적 ─────────────────────────────────────────────────────────────────────
 *   기본 하드코딩 목록(STORY_LIST, EXPANSION_LIST 등)에 없는 항목을
 *   코드 수정 없이 앱에서 직접 추가·삭제할 수 있게 한다.
 *   OCR 인식 시 이 목록도 함께 탐색되어 나무위키로 연결된다.
 *
 * ── 저장 ─────────────────────────────────────────────────────────────────────
 *   SharedPreferences에 JSON 배열로 영속 저장.
 *   키: story / level / season / expansion
 *
 * ── URL 생성 규칙 ─────────────────────────────────────────────────────────────
 *   이야기   → /랜덤 인카운터/이야기 인카운터#(제목)
 *   레벨     → /랜덤 인카운터/레벨 인카운터#(제목)
 *   시즌패스 → /랜덤 인카운터/시즌 패스 인카운터#(제목)
 *   확장팩   → /랜덤 인카운터/(제목)
 */
object CustomItemManager {

    private const val PREF_NAME     = "custom_items"
    private const val KEY_STORY     = "story"
    private const val KEY_LEVEL     = "level"
    private const val KEY_SEASON    = "season"
    private const val KEY_EXPANSION = "expansion"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── 읽기 ─────────────────────────────────────────────────────────────────

    fun getStories(ctx: Context):    Set<String> = load(ctx, KEY_STORY)
    fun getLevels(ctx: Context):     Set<String> = load(ctx, KEY_LEVEL)
    fun getSeasons(ctx: Context):    Set<String> = load(ctx, KEY_SEASON)
    fun getExpansions(ctx: Context): Set<String> = load(ctx, KEY_EXPANSION)

    private fun load(ctx: Context, key: String): Set<String> {
        val json = prefs(ctx).getString(key, "[]") ?: "[]"
        val arr  = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    // ── 쓰기 ─────────────────────────────────────────────────────────────────

    fun addStory(ctx: Context, name: String)     = add(ctx, KEY_STORY, name)
    fun addLevel(ctx: Context, name: String)     = add(ctx, KEY_LEVEL, name)
    fun addSeason(ctx: Context, name: String)    = add(ctx, KEY_SEASON, name)
    fun addExpansion(ctx: Context, name: String) = add(ctx, KEY_EXPANSION, name)

    fun removeStory(ctx: Context, name: String)     = remove(ctx, KEY_STORY, name)
    fun removeLevel(ctx: Context, name: String)     = remove(ctx, KEY_LEVEL, name)
    fun removeSeason(ctx: Context, name: String)    = remove(ctx, KEY_SEASON, name)
    fun removeExpansion(ctx: Context, name: String) = remove(ctx, KEY_EXPANSION, name)

    /** 현재 탭의 항목을 모두 삭제 */
    fun clearStories(ctx: Context)    = save(ctx, KEY_STORY, emptySet())
    fun clearLevels(ctx: Context)     = save(ctx, KEY_LEVEL, emptySet())
    fun clearSeasons(ctx: Context)    = save(ctx, KEY_SEASON, emptySet())
    fun clearExpansions(ctx: Context) = save(ctx, KEY_EXPANSION, emptySet())

    private fun add(ctx: Context, key: String, name: String) {
        val set = load(ctx, key).toMutableSet().also { it.add(name.trim()) }
        save(ctx, key, set)
    }

    private fun remove(ctx: Context, key: String, name: String) {
        val set = load(ctx, key).toMutableSet().also { it.remove(name.trim()) }
        save(ctx, key, set)
    }

    private fun save(ctx: Context, key: String, set: Set<String>) {
        val arr = JSONArray().also { a -> set.forEach { a.put(it) } }
        prefs(ctx).edit().putString(key, arr.toString()).apply()
    }
}
