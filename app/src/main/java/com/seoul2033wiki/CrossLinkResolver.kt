package com.seoul2033wiki

import android.util.Log
import java.net.URLEncoder

/**
 * 두 가지 예외 케이스를 통합 처리하는 Resolver.
 *
 * ── Case A. 태그 없는 확장팩 본문 ────────────────────────────────────────────
 *   확장팩 태그가 화면에 표시되지 않아도 본문 키구문으로 확장팩+섹션을 특정.
 *   key → Pair(확장팩명, 섹션앵커)
 *
 * ── Case B. 크로스링크 (확장팩 ↔ 주요 스토리 인카운터) ──────────────────────
 *   STEP 0에서 확장팩 이름이 잡힌 경우에만 사용.
 *   확장팩을 메인으로 보여주되 버튼으로 주요 스토리 인카운터로 전환 가능.
 *   key → CrossLinkEntry(확장팩명, 주요스토리앵커, 버튼라벨)
 */
object CrossLinkResolver {

    private const val TAG = "CrossLinkResolver"
    private const val BASE = "https://namu.wiki"

    // ── Case A ───────────────────────────────────────────────────────────────
    private val TAGLESS_MAP: List<Pair<String, Pair<String, String>>> = listOf(

        // 언더 월드 — 소셜 네트워킹 사업 (45층 노량진역)
        "마본좌에게소셜네트워킹사업을전수받은지도꽤나시간이흘렀습니다" to ("언더 월드" to "45층 (노량진역)"),
        "파트너님제동업자들이에요소셜네트워크사업을전수했던여행자가" to ("언더 월드" to "45층 (노량진역)"),
        "파트너님제동업자의동업자들이에요왜이렇게얼굴보기가힘들어요하하" to ("언더 월드" to "45층 (노량진역)"),

        // 밀라노 칼리브로
        "누군가가당신의등을가볍게칩니다동지여기서보네밀라노가꽁지머리를만지작거리며인사합니다" to ("밀라노 칼리브로" to "밀라노와 미군 커플"),
        "미쳤습니까의료진허락없이이런걸환자한테들이댈순없어요악마같은자식" to ("밀라노 칼리브로" to "밀라노와 심장 제세동기"),
        "동지동지흙먼지를일으키며당신옆에낯익은오토바이가멈춰섭니다" to ("밀라노 칼리브로" to "제세동기 후속 인카운터"),

        // 구로혈액공단
        "당신은구석에몸을숨기고그들이일을마치기를기다립니다얼마뒤헌혈하려는사람들의발길이끊기자그들이자리를정리합니다" to ("구로혈액공단" to "미행하기"),

        // 무기속 위임 — 진범에게 화약공급
        "당신이여정을계속하고있는데뒤에서누군가가헐레벌떡당신을향해달려옵니다성균관대에서봤던진범입니다" to ("무기속 위임" to "진범에게 화약공급"),
        "내총본적있지내머스킷말이야이게워낙옛날방식이다보니화약이많이필요하거든" to ("무기속 위임" to "진범에게 화약공급")
    )

    // ── Case B ───────────────────────────────────────────────────────────────
    data class CrossLinkEntry(
        val expansion: String,
        val expansionAnchor: String,   // 확장팩 섹션 앵커 (비어있으면 인덱스 페이지)
        val mainStoryAnchor: String,
        val crossLinkLabel: String
    )

    private val CROSSLINK_MAP: List<Pair<String, CrossLinkEntry>> = listOf(

        // 짐승소년
        "유전자스캐너입니다핏방울을떨어뜨리는것만으로도유전자형질을분석하게해주는기계라고하네요소장이연구용으로쓰던물건같습니다" to CrossLinkEntry(
            expansion = "바운티 헌터",
            expansionAnchor = "생명사랑연구소",
            mainStoryAnchor = "바운티 헌터",
            crossLinkLabel = "짐승소년"
        ),

        "특히고맙다승돌아할배구리가희미하게웃습니다어떻게당신의이름을알고있는거죠당신은깜짝놀랍니다" to CrossLinkEntry(
            expansion = "황소너구리 왕국",
            expansionAnchor = "황소너구리 부대",
            mainStoryAnchor = "황소너구리 왕국",
            crossLinkLabel = "짐승소년"
        ),

        "특히자네같은사람에게는그가방독면으로가려진당신의얼굴을가리킵니다돌연변이들을하도많이보면난가려져있어도알수있거든" to CrossLinkEntry(
            expansion = "언더 월드",
            expansionAnchor = "",
            mainStoryAnchor = "언더 월드",
            crossLinkLabel = "짐승소년"
        ),

        "네가해준말이필요한것은비단나뿐만이아닌것같구나너에게도필요한말이라는걸알고있나소년" to CrossLinkEntry(
            expansion = "신인 작가 단편선 2",
            expansionAnchor = "커다란 아치형입구 Zoo",
            mainStoryAnchor = "신인 작가 단편선 2",
            crossLinkLabel = "짐승소년"
        ),

        "그런데당신그런모습이어도인간들은인간인걸알아보나요이런녀석을얕봤습니다당신의본모습을이미꿰뚫어보고있었던것같군요" to CrossLinkEntry(
            expansion = "동물 친구들 2",
            expansionAnchor = "데메테르 원",
            mainStoryAnchor = "동물 친구들 2",
            crossLinkLabel = "짐승소년"
        ),

    )

    // ── URL 경로 리매핑 ───────────────────────────────────────────────────────
    private val URL_REMAP: Map<String, String> = mapOf(
        "준비된 모험가" to "준비된 모험가&전문가",
        "준비된 전문가" to "준비된 모험가&전문가"
    )

    // Case A: 확장팩 태그 없이 본문만으로 매칭
    fun resolveCaseA(beforePageNorm: String, pageNum: String): ResolvedEntry? {
        val match = TAGLESS_MAP.firstOrNull { (key, _) ->
            key.length >= 8 && beforePageNorm.contains(key)
        } ?: return null

        val (expansion, anchor) = match.second
        Log.d(TAG, "CaseA 매칭: $expansion / $anchor")
        return ResolvedEntry(
            title = if (anchor.isEmpty()) expansion else "$expansion - $anchor",
            pageNum = pageNum,
            type = EntryType.EXPANSION_ENCOUNTER,
            url = buildExpansionUrl(expansion, anchor)
        )
    }

    // Case B: STEP 0에서 확장팩 이름이 잡힌 후 크로스링크 보강
    // 반환: Triple(expansionUrl, crossLinkUrl, crossLinkLabel) 또는 null
    //   expansionUrl — 확장팩 섹션 앵커가 있으면 해당 URL, 없으면 null (기존 폴백 유지)
    //   crossLinkUrl — 주요 스토리 인카운터 URL
    fun resolveCaseB(beforePageNorm: String, knownExpansion: String): Triple<String?, String, String>? {
        val match = CROSSLINK_MAP.firstOrNull { (key, entry) ->
            entry.expansion == knownExpansion &&
                    key.length >= 8 && beforePageNorm.contains(key)
        } ?: return null

        val entry = match.second
        Log.d(TAG, "CaseB 매칭: ${entry.expansion} ↔ 주요스토리/${entry.mainStoryAnchor}")
        val expansionUrl = if (entry.expansionAnchor.isNotEmpty())
            buildExpansionUrl(entry.expansion, entry.expansionAnchor)
        else null
        return Triple(expansionUrl, buildMainStoryUrl(entry.mainStoryAnchor), entry.crossLinkLabel)
    }

    private fun buildExpansionUrl(expansion: String, anchor: String): String {
        val urlPath = URL_REMAP[expansion] ?: expansion
        val basePath = URLEncoder.encode("서울 2033/랜덤 인카운터/$urlPath", "UTF-8")
            .replace("+", "%20")
        if (anchor.isEmpty()) return "$BASE/w/$basePath"
        val anchorEncoded = URLEncoder.encode(anchor, "UTF-8").replace("+", "%20")
        return "$BASE/w/$basePath#$anchorEncoded"
    }

    private fun buildMainStoryUrl(anchor: String): String {
        val basePath = URLEncoder.encode("서울 2033/랜덤 인카운터/주요 스토리 인카운터", "UTF-8")
            .replace("+", "%20")
        val anchorEncoded = URLEncoder.encode(anchor, "UTF-8").replace("+", "%20")
        return "$BASE/w/$basePath#$anchorEncoded"
    }
}