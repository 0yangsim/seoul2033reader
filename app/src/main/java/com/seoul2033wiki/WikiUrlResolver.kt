package com.seoul2033wiki

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * 화면 하단에서 인식된 텍스트를 분석해 나무위키 URL을 결정한다.
 *
 * ─ OCR 캡처 영역은 화면 하단 87~93% 구간 ─
 *
 * ── 판별 흐름 ──────────────────────────────────────────────────────────────
 *
 * STEP 0. 제목 직접 스캔 (페이지번호 없어도 동작)
 *   [이야기] 태그 or 확장팩/이야기 이름 포함 → 즉시 매칭
 *   미탐지 → STEP 1로
 *
 * STEP 1. "- 숫자 -" 패턴 탐색
 *   발견됨 → STEP 2로
 *   없음   → [Case 4] 기본 인카운터 (빈 인벤토리 포함)
 *
 * STEP 2. 페이지번호 앞 전체 텍스트 분석
 *   괄호로 끝나는 패턴  → [Case 3] 선택지 오인식 (괄호 안 이름으로 매칭)
 *   이야기 태그 있음    → [Case 2] 이야기 계열
 *   이야기 태그 없음    → [Case 1] 확장팩 (매칭 실패 시 기본 인카운터로 처리)
 *
 * ── URL 규칙 ────────────────────────────────────────────────────────────────
 *   확장팩       → /w/서울 2033/랜덤 인카운터/(확장팩이름)
 *   이야기       → /w/서울 2033/랜덤 인카운터/이야기 인카운터#(이야기이름)
 *   레벨업 보상  → /w/서울 2033/랜덤 인카운터/레벨 인카운터#(이야기이름 (n레벨))
 *   시즌패스     → /w/서울 2033/랜덤 인카운터/시즌 패스 인카운터#(이야기이름)
 *   기본 인카운터→ /w/서울 2033/랜덤 인카운터/기본 인카운터
 *
 *
 * ── 이름 충돌 주의사항 ──────────────────────────────────────────────────────
 *   "보물찾기" (시즌패스) vs "탐욕의 보물찾기" (이야기) : exactMatch 우선
 *   "동물 친구들" vs "동물 친구들 2"                    : exactMatch 우선
 *
 * ── 축약 처리 ────────────────────────────────────────────────────────────────
 *   게임이 긴 제목을 생략할 경우 TITLE_ALIAS_MAP의 별칭으로 매칭
 *   예) "메인스토리 : 시뮬라" → "메인 스토리 : 시뮬라크르"
 */
object WikiUrlResolver {

    private const val BASE = "https://namu.wiki"
    private const val TAG = "WikiUrlResolver"

    // ── 하드코딩 목록 ─────────────────────────────────────────────────────────

    private val EXPANSION_LIST: Set<String> = setOf(
        "고양이를 부탁해", "구로혈액공단", "노 맨즈 랜드", "노 맨즈 랜드 2",
        "동물 친구들", "동물 친구들 2", "뒷골목의 제왕", "드래곤 마운틴",
        "마님의 바텐더", "맛집 기행", "무기속 위임", "물물 교환",
        "미. 연. 시", "밀라노 칼리브로", "바운티 헌터", "방사능 갤러리",
        "발명의 날", "분노의 도로", "블랙 아웃", "사건 25시",
        "사실상 공무원의 이론", "서울 2015 : 예삐전", "서울림픽", "서울 무림맹",
        "서울의 밤", "세상에 나쁜 쥐는 없다", "신인 작가 단편선", "신인 작가 단편선 2",
        "아기 황소너구리", "언더 월드", "엽총과 송곳니", "옛날 옛적에",
        "온 에어 서울", "요리왕", "원 피스 쉽", "잠실 칼리파",
        "장미의 이름으로", "재건", "죄와 벌", "준비된 모험가",
        "준비된 전문가", "천공의 묵시록", "카지노 로얄", "프린세슘 메이커",
        "한강 러닝", "핵겨울왕국", "황소너구리 왕국"
    )

    // 사용자 등록 확장팩은 런타임에 CustomItemManager.getExpansions(ctx)로 조회

    private val STORY_LIST: Set<String> = setOf(
        "산타의 선물", "랜덤 박", "지저복학생", "프랭크 박스", "표낭도 블루스",
        "애기애기 돌보미", "서울안전가스총회", "피서바이벌", "미래대비 통조림 저장고",
        "은평구 뉴뉴뉴타운", "담력 테스트", "창작의 고통", "단풍 축제", "빼빼로 데이",
        "버드 스트라이크", "언다월드", "도서관의 날", "로봇 스승",
        "그 시절 우리들의 클럽", "일단 똥을 싸라", "당신을 위한 송편", "로망 24시",
        "리사이클링 사이클러", "교황천국 불신사망", "구호 기사단", "김치 명인 마을",
        "초콜릿 캐슬", "핵기사 이야기", "다람쥐와 모기와 도박 중독자", "낮은 곳의 쇼핑몰",
        "브레멘 음악대", "U.S.A. 사이언스", "탐욕의 보물찾기", "인간 동물원", "로그아웃",
        "캐러밴", "황야의 크리스마스 파티", "작은 친구들을 위한 설빔", "진범의 고백 공격",
        "사랑은 움직이는거야", "아크로 리버사이드 마린", "뉴 서울 공화국", "목련호의 기적",
        "수험생 키우기 프로젝트", "이북 리더", "호텔의 규칙", "은관우의 오관참육장",
        "도봉산의 등산객들", "생명의 다리", "텅 빈 소각장", "누더기 왕의 대관식",
        "하비와 꿩 사냥", "살인망치와 사람 사냥", "수유동 탐사",
        "아무거나 마시면 안 돼", "아무데서나 자면 안 돼", "둥이와 제이크", "실종 전단지",
        "까르트코 마트", "차량촌", "유년의 끝", "예삐너구리", "육즙팡팡 황소너구리",
        "이모의 무료 급식", "신자유당", "의원의 자격", "메카 켈베로스",
        "블랙 맘바의 컴퓨터 교실", "도봉산 마을 : 코마", "도봉산 마을 : 짐승소년",
        "미역이를 위한 선물", "엘리트 기계교실",
        "할로윈 마피아", "지하 세계로의 초대장", "감자밭의 파수꾼", "로맨틱 어사일럼",
        "하수도 사냥꾼", "초콜릿 분수", "검은 고양이", "마님의 단골", "생일 2032",
        "그들이 살아가는 이야기", "구두장이인데 너무 강함", "멸망한 세계의 건물주",
        "수험생 키우기 콜라보레이션", "미스터 택시", "서울 여행자를 위한 안내서",
        "농협은행으로의 초대장", "강북으로의 초대장", "뜨겁구리의 PT 교실",
        "일신 편의점에 어서오세요", "실전! 소매치기", "실전! 서울 생태 지식",
        "실전! 강력한 직감", "연금술사", "해와 달 용병단"
    )

    // 사용자 등록 이야기/레벨/시즌패스는 런타임에 CustomItemManager.get*(ctx)로 조회

    // 위키 제목: "이야기 제목 (n레벨)" / 게임 화면: "(n레벨)" 생략됨
    private val LEVEL_LIST: Set<String> = setOf(
        "이상한 꿈 (2레벨)", "엄마가 된 리트리버 (3레벨)", "지뢰나라 왕자님 (5레벨)", "헌옷수거함 (6레벨)",
        "불꽃놀이 할아버지 (8레벨)", "사우나 (9레벨)", "무지개 드라이크리닝 (11레벨)", "한강 낚시 (12레벨)",
        "별똥별 (14레벨)", "뒷골목에도 사랑은 핀다 (15레벨)", "사격 연습 (17레벨)", "운수 좋은 날 (18레벨)",
        "초보자 가르치기(20레벨)", "반지하 가족(21레벨)", "미술 경매(23레벨)", "미군 밀매상(24레벨)",
        "방사능 블랙잭(26레벨)", "핵물리학 연구소(27레벨)", "고기 농사(29레벨)", "메인 스토리 : 코마(30레벨)",
        "거대 지렁이(32레벨)"
    )

    private val LEVEL_TITLE_ONLY_MAP: Map<String, String> = LEVEL_LIST.associateBy(
        keySelector = { full -> full.replace(Regex("""\s*\(\d+레벨\)"""), "").trim() },
        valueTransform = { it }
    )

    private val SEASON_LIST: Set<String> = setOf(
        "쓰레기장에서 태어난 아이", "배달의 민족", "유도왕 유도탄 신간", "열지 않은 통조림",
        "인형과 동심", "트레쉬 헌터", "기억 발굴 프로젝트", "마음을 전하는 법",
        "핵겨울촌 제설 작업", "강아지 대 고양이(물리)", "라디오 만들기", "마을의 해결사",
        "삼청동 탐사", "최강의 무기", "네잎병아리", "상자 요새", "원조 붕어빵",
        "헌옷수거함 패밀리가 떴다", "강아지와 공놀이",
        "찍찍이의 쳇바퀴", "다람쥐의 식량 창고", "병아리 장수", "금단의 사랑",
        "은혜 갚는 앵무새", "달팽이 사냥꾼", "독수리사냥", "감염된 인간",
        "전통찻집", "신기한 생태사전", "점 치는 문어", "짬타이거 타이쿤",
        "무궁무진화", "담아고치", "운동장 평원", "생태계 교란종 헌터",
        "두더지 외교관", "메인 스토리 : 짐승소년",
        "하늘을 본 지 얼마나 됐습니까?", "괴수 조각가", "죽마고우", "지하실 괴물",
        "아이오딘 상인", "굼벵이", "언덕 위의 괴물", "유전자 자판기", "솔방울 소녀",
        "부활의 대가", "미스터 일루미나티", "희망 성형외과", "주주총회",
        "삼족오 마을", "예삐와 해치 사냥", "이심전심", "페이퍼맨 비긴즈",
        "오늘도 풍작", "메인스토리 : 괴물",
        "보물찾기", "문제적 경품 기계", "황소너구리의 보금자리", "포커의 신",
        "미의 기준", "인공지능 채찍 PT", "환각성냥버섯", "괴식 떡볶이",
        "명륜동 탐사", "오솔길의 돌멩이", "동물 유치원", "닥터 장",
        "사과와 능금나무", "주중 농장", "클로버 풀밭", "전생 체험 이야기",
        "서울 카페 부흥기", "우리 콩 연구소", "고로 나는 돌아간다",
        "메인 스토리 : 시뮬라크르"
    )

    // ── 축약 별칭 맵 ───────────────────────────────────────────────────────────
    // 게임이 긴 제목을 잘라낼 경우 축약형 → 정식 위키 제목으로 매핑
    // key: normalize() 적용 후 비교, value: 정식 제목
    // 이야기/시즌/레벨 가리지 않고 모든 카테고리 커버
    private val TITLE_ALIAS_MAP: Map<String, String> = mapOf(
        // 시뮬라크르 관련 (가장 긴 제목 중 하나)
        "메인스토리:시뮬라크르"    to "메인 스토리 : 시뮬라크르",
        "메인스토리:시뮬라"        to "메인 스토리 : 시뮬라크르",
        "메인스토리시뮬라크르"     to "메인 스토리 : 시뮬라크르",
        "메인스토리시뮬라"         to "메인 스토리 : 시뮬라크르",
        "메인스토리:시뮬"          to "메인 스토리 : 시뮬라크르",
        // 짐승소년 관련
        "메인스토리:짐승소년"      to "메인 스토리 : 짐승소년",
        "메인스토리짐승소년"       to "메인 스토리 : 짐승소년",
        // 코마 관련 (레벨)
        "메인스토리:코마"          to "메인 스토리 : 코마(30레벨)",
        "메인스토리코마"           to "메인 스토리 : 코마(30레벨)",
        // 괴물 관련
        "메인스토리:괴물"          to "메인스토리 : 괴물",
        "메인스토리괴물"           to "메인스토리 : 괴물",
        // 도봉산 마을
        "도봉산마을:코마"          to "도봉산 마을 : 코마",
        "도봉산마을:짐승소년"      to "도봉산 마을 : 짐승소년",
        "도봉산마을코마"           to "도봉산 마을 : 코마",
        "도봉산마을짐승소년"       to "도봉산 마을 : 짐승소년",
        // 서울2015
        "서울2015:예삐전"          to "서울 2015 : 예삐전",
        "서울2015예삐전"           to "서울 2015 : 예삐전",
        // 하늘을 본 지 얼마나 됐습니까? (긴 제목)
        "하늘을본지얼마나됐습니까" to "하늘을 본 지 얼마나 됐습니까?",
        "하늘을본지얼마나"         to "하늘을 본 지 얼마나 됐습니까?",
        // 다람쥐와 모기와 도박 중독자
        "다람쥐와모기와도박중독자"  to "다람쥐와 모기와 도박 중독자",
        "다람쥐와모기와도박"        to "다람쥐와 모기와 도박 중독자",
        // 수험생 키우기
        "수험생키우기프로젝트"     to "수험생 키우기 프로젝트",
        "수험생키우기콜라보레이션"  to "수험생 키우기 콜라보레이션",
        "수험생키우기콜라보"        to "수험생 키우기 콜라보레이션",
        // 사실상 공무원의 이론
        "사실상공무원의이론"        to "사실상 공무원의 이론",
        // 일신 편의점
        "일신편의점에어서오세요"    to "일신 편의점에 어서오세요",
        "일신편의점에어서"          to "일신 편의점에 어서오세요",
        // 서울 여행자
        "서울여행자를위한안내서"    to "서울 여행자를 위한 안내서",
        "서울여행자안내서"          to "서울 여행자를 위한 안내서",
        // 작은 친구들
        "작은친구들을위한설빔"      to "작은 친구들을 위한 설빔",
        // 황야의 크리스마스
        "황야의크리스마스파티"      to "황야의 크리스마스 파티"
    )

    // ── 나무위키 폴백용 동적 목록 ───────────────────────────────────────────
    private var dynamicLevelStories: Set<String> = emptySet()
    private var dynamicSeasonStories: Set<String> = emptySet()
    private var loaded = false

    suspend fun loadStoryLists(ctx: android.content.Context? = null) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        try {
            dynamicLevelStories = fetchTitlesFromWiki("$BASE/w/서울%202033/랜덤%20인카운터/레벨%20인카운터")
            dynamicSeasonStories = fetchTitlesFromWiki("$BASE/w/서울%202033/랜덤%20인카운터/시즌%20패스%20인카운터")
            loaded = true
            Log.d(TAG, "동적 목록 로딩 완료: 레벨 ${dynamicLevelStories.size}개, 시즌 ${dynamicSeasonStories.size}개")
        } catch (e: Exception) {
            Log.e(TAG, "동적 목록 로딩 실패: ${e.message}")
        }
    }

    private fun fetchTitlesFromWiki(url: String): Set<String> {
        val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10_000).get()
        val titles = doc.select("div#toc a, .toc a, [class*=toc] a")
            .map { it.text().trim() }
            .map { it.replace(Regex("^[\\d.\\s]+"), "").trim() }
            .filter { it.length >= 2 }
            .toSet()
        if (titles.isEmpty()) {
            return doc.select("h2 .toc-text, h3 .toc-text, h2 a, h3 a")
                .map { it.text().trim() }
                .filter { it.length >= 2 }
                .toSet()
        }
        return titles
    }


    // ── 메인 resolve 함수 ─────────────────────────────────────────────────────

    fun resolve(rawText: String, ctx: android.content.Context? = null): ResolvedEntry? {
        val cleaned = rawText.trim()

        // ────────────────────────────────────────────────────────────────────
        // STEP 1. "- 숫자 -" 패턴 탐색 (페이지 번호가 핵심 앵커)
        //   있음 → 페이지번호 앞 텍스트만 추출 → STEP 0 · STEP 2로
        //   없음 → 팝업 미표시
        // ────────────────────────────────────────────────────────────────────
        val pagePattern = Regex("""[-–—ー.]\s*([\d\]?]+)\s*[-–—ー.]""")
        val pageMatch = pagePattern.find(cleaned)

        if (pageMatch == null) {
            Log.d(TAG, "페이지 번호 없음 → null 반환 (팝업 미표시)")
            return null
        }

        // ] → 1 치환, ? 는 그대로 유지 (숫자 미인식 표시)
        val pageNum = pageMatch.groupValues[1].replace("]", "1")
        // 페이지 번호 앞쪽 텍스트만 분석 (뒤는 인벤토리·UI 노이즈)
        val beforePage = cleaned.substring(0, pageMatch.range.first).trim()
        Log.d(TAG, "페이지번호=$pageNum / 앞텍스트=[$beforePage]")

        // ────────────────────────────────────────────────────────────────────
        // STEP 0. 페이지번호 앞 텍스트에서 제목 직접 매칭
        //
        // 접근성 API는 오인식이 없으므로 확장팩/이야기 이름이 그대로 포함됨.
        // beforePage 범위만 탐색해 인벤토리·UI 노이즈를 차단한다.
        //
        // 이 단계에서 매칭되면 페이지번호는 이미 추출된 값을 전달.
        // ────────────────────────────────────────────────────────────────────
        val step0result = resolveByTitleScan(beforePage, ctx, pageNum)
        if (step0result != null) {
            Log.d(TAG, "STEP0 제목 직접 매칭 성공: ${step0result.title}")
            return step0result
        }

        // beforePage가 비어있으면 제목 정보 없음 → 기본 인카운터
        if (beforePage.isBlank()) {
            Log.d(TAG, "앞텍스트 없음 → 기본 인카운터")
            return ResolvedEntry(
                title = "기본 인카운터", pageNum = pageNum,
                type = EntryType.BASIC,
                url = buildUrl("랜덤 인카운터/기본 인카운터")
            )
        }

        // ────────────────────────────────────────────────────────────────────
        // STEP 2-A. [Case 3] 상단 선택지 오인식 감지:
        //   페이지 번호 앞 텍스트가 "어떤 텍스트 (이름)" 형태로 끝남
        //   → 마지막 괄호 안 이름으로 매칭
        // ────────────────────────────────────────────────────────────────────
        val choicePattern = Regex(""".*\(([가-힣a-zA-Z0-9\s!?.,·':]+)\)\s*$""")
        val choiceMatch = choicePattern.find(beforePage)
        if (choiceMatch != null) {
            val candidate = choiceMatch.groupValues[1].trim()
            if (candidate.length >= 2) {
                val fromChoice = resolveByTitle(candidate, pageNum)
                if (fromChoice != null) {
                    Log.d(TAG, "선택지 오인식 감지 → 괄호 내 이름 '$candidate' 매칭")
                    return fromChoice
                }
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // STEP 2-B. [이야기] 태그 판별
        val lines = beforePage.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val storyTagIdx = lines.indexOfFirst { line ->
            line == "이야기" || line == "[이야기]" ||
            line.contains(Regex("""\[이야기]""")) ||
            line.startsWith("이야기")
        }
        val storyLine: String? = if (storyTagIdx >= 0) {
            val tagLine = lines[storyTagIdx]
            val inlineTitle = tagLine
                .replace(Regex("""^\[이야기]\s*"""), "")
                .replace(Regex("""^이야기\s*"""), "")  // \s* 로 변경: 단독 "이야기"도 제거
                .trim()
            if (inlineTitle.isNotEmpty()) {
                // 인라인 제목: "이야기 차량촌" → "[이야기] 차량촌"
                "[이야기] $inlineTitle"
            } else {
                // 다음 줄이 제목: "이야기\n차량촌" → "[이야기] 차량촌"
                lines.getOrNull(storyTagIdx + 1)?.let { nextLine ->
                    if (nextLine.length >= 2 && !nextLine.matches(Regex("""\d+""")))
                        "[이야기] $nextLine"
                    else null
                }
            }
        } else null
        val hasStoryTag = storyLine != null

        // [이야기] 태그 이후 또는 전체를 제목으로 사용
        val rawTitle = if (hasStoryTag && storyLine != null) {
            storyLine
                .replace(Regex("""^\[이야기]\s*"""), "")
                .trim()
        } else {
            beforePage
        }

        // 별칭 우선 해소
        val resolvedAlias = resolveAlias(rawTitle)
        val ocrTitle = resolvedAlias ?: rawTitle

        return if (!hasStoryTag) {
            // ── [Case 1] 확장팩 ──────────────────────────────────────────
            // 하드코딩 목록 우선 탐색
            val hardTitle = exactMatchPrecise(ocrTitle, EXPANSION_LIST)
                ?: fuzzyFind(ocrTitle, EXPANSION_LIST)

            if (hardTitle != null) {
                ResolvedEntry(
                    title = hardTitle, pageNum = pageNum,
                    type = EntryType.EXPANSION,
                    url = buildUrl("랜덤 인카운터/$hardTitle")
                )
            } else {
                // 사용자 등록 확장팩 탐색
                val customExpansions = ctx?.let { CustomItemManager.getExpansions(it) } ?: emptySet()
                val customTitle = exactMatchPrecise(ocrTitle, customExpansions)
                    ?: fuzzyFind(ocrTitle, customExpansions)

                if (customTitle == null) {
                    // 태그도 없고 확장팩 매칭 실패 → 기본 인카운터로 처리
                    Log.d(TAG, "태그 없음 + 확장팩 매칭 실패 → null 반환")
                    null
                } else {
                    Log.d(TAG, "사용자 등록 확장팩 매칭: '$customTitle'")
                    ResolvedEntry(
                        title = customTitle, pageNum = pageNum,
                        type = EntryType.EXPANSION,
                        url = buildUrl("랜덤 인카운터/$customTitle")
                    )
                }
            }
        } else {
            // ── [Case 2] 이야기 계열 ─────────────────────────────────────
            resolveStory(ocrTitle, pageNum)
        }
    }

    // ── 별칭 해소 ─────────────────────────────────────────────────────────────

    /**
     * normalize된 키로 TITLE_ALIAS_MAP 탐색.
     * 1) 정확 일치
     * 2) 입력이 키로 시작하는 경우 (접두 일치) — 게임이 잘라낸 케이스
     * 반환값: 정식 위키 제목, 없으면 null
     */
    private fun resolveAlias(raw: String): String? {
        val cleanInput = normalize(raw)
        // 정확 일치
        TITLE_ALIAS_MAP.entries.firstOrNull { normalize(it.key) == cleanInput }
            ?.let { return it.value }
        // 접두 일치: 맵의 키가 입력을 접두로 포함하거나, 입력이 키의 접두인 경우
        // (단, 최소 4글자 이상이어야 오인식 방지)
        return TITLE_ALIAS_MAP.entries
            .filter { (key, _) ->
                val cleanKey = normalize(key)
                cleanKey.length >= 4 && (
                    cleanInput.startsWith(cleanKey) ||
                    cleanKey.startsWith(cleanInput) && cleanInput.length >= 4
                )
            }
            .minByOrNull { kotlin.math.abs(normalize(it.key).length - cleanInput.length) }
            ?.value
    }

    // ── STEP0: 전체 텍스트에서 제목 직접 스캔 (페이지번호 없어도 동작) ─────

    /**
     * OCR 전체 텍스트에서 '이야기' 태그 또는 확장팩/이야기 이름을 직접 탐색.
     * 페이지번호가 없거나 오인식되어도 제목이 있으면 연결.
     *
     * 매칭 우선순위:
     *   1) '이야기' 태그 있음 → 태그 뒤 제목 추출 → 이야기 계열 매칭
     *   2) 확장팩 이름이 텍스트에 포함됨 → 확장팩으로 연결
     *   3) 이야기/레벨/시즌 이름이 텍스트에 포함됨 → 해당 계열로 연결
     *
     * 오매칭 방지: 제목 길이 4글자(normalize 후) 이상만 허용
     */
    private fun resolveByTitleScan(cleaned: String, ctx: android.content.Context? = null, pageNum: String = "?"): ResolvedEntry? {
        val cleanedNorm = normalize(cleaned)

        // 1) [이야기] 태그로 이야기 계열 판별
        val lines = cleaned.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val storyTagIdx = lines.indexOfFirst { line ->
            line == "이야기" || line == "[이야기]" ||
            line.contains("[이야기]") || line.trimStart().startsWith("이야기")
        }
        val storyLine: String? = if (storyTagIdx >= 0) {
            val tagLine = lines[storyTagIdx]
            // [이야기] 또는 "이야기 " 접두사를 제거한 뒤 남은 인라인 제목
            // "이야기" 단독 줄은 ^이야기\s+ 패턴에 매칭되지 않으므로 inlineTitle = "이야기" 그대로 남음
            // → 태그 워드 자체와 같은 경우엔 제목이 없는 것으로 처리
            val inlineTitle = tagLine
                .replace(Regex("""^\[이야기]\s*"""), "")
                .replace(Regex("""^이야기\s*"""), "")  // \s+ → \s* 로 변경: 공백 없는 단독 "이야기"도 제거
                .trim()
            if (inlineTitle.isNotEmpty()) {
                // 인라인 제목이 있는 경우: "이야기 차량촌" → inlineTitle = "차량촌"
                "[이야기] $inlineTitle"
            } else {
                // 다음 줄을 제목으로 사용: "이야기\n차량촌" → "[이야기] 차량촌"
                lines.getOrNull(storyTagIdx + 1)?.let { nextLine ->
                    if (nextLine.length >= 2 && !nextLine.matches(Regex("""\d+""")))
                        "[이야기] $nextLine"
                    else null
                }
            }
        } else null
        if (storyLine != null) {
            val rawTitle = storyLine
                .replace(Regex("""^\[이야기]\s*"""), "")
                .trim()
            val resolved = resolveAlias(rawTitle) ?: rawTitle

            // 주요 스토리 인터셉트: 코마 등은 이야기 URL 대신 주요 스토리로
            val mainStoryHit = MAIN_STORY_REDIRECT_TITLES.firstOrNull { title ->
                normalize(resolved).contains(normalize(title)) ||
                normalize(rawTitle).contains(normalize(title))
            }
            if (mainStoryHit != null) {
                Log.d(TAG, "STEP0 주요 스토리 인터셉트: '$mainStoryHit'")
                return ResolvedEntry(
                    title = mainStoryHit, pageNum = pageNum,
                    type = EntryType.MAIN_STORY,
                    url = buildUrl("랜덤 인카운터/주요 스토리 인카운터")
                )
            }

            val entry = resolveStoryExact(resolved, pageNum) ?: resolveStoryFuzzy(resolved, pageNum)
            if (entry != null) return entry
            if (resolved != rawTitle) {
                return resolveStoryExact(rawTitle, pageNum) ?: resolveStoryFuzzy(rawTitle, pageNum)
            }
        }

        // 2) 확장팩 이름 포함 매칭
        // 접근성 API는 오인식이 없으므로 최소 길이를 2자로 완화 (재건 등 짧은 이름 허용)
        // 하드코딩 확장팩 우선, 사용자 등록 확장팩도 동일하게 EXPANSION으로 처리
        val hardExpansionHit = EXPANSION_LIST
            .filter { candidate ->
                val cn = normalize(candidate)
                cn.length >= 2 && cleanedNorm.contains(cn)
            }
            .maxByOrNull { normalize(it).length }
        if (hardExpansionHit != null) {
            Log.d(TAG, "STEP0-2 하드코딩 확장팩 매칭: '$hardExpansionHit'")
            return ResolvedEntry(
                title = hardExpansionHit, pageNum = pageNum,
                type = EntryType.EXPANSION,
                url = buildUrl("랜덤 인카운터/$hardExpansionHit")
            )
        }
        val customExpansions0 = ctx?.let { CustomItemManager.getExpansions(it) } ?: emptySet()
        val customExpansionHit = customExpansions0
            .filter { candidate ->
                val cn = normalize(candidate)
                cn.length >= 2 && cleanedNorm.contains(cn)
            }
            .maxByOrNull { normalize(it).length }
        if (customExpansionHit != null) {
            Log.d(TAG, "STEP0-2 사용자 등록 확장팩 매칭: '$customExpansionHit'")
            return ResolvedEntry(
                title = customExpansionHit, pageNum = pageNum,
                type = EntryType.EXPANSION,
                url = buildUrl("랜덤 인카운터/$customExpansionHit")
            )
        }

        // 3) 이야기/레벨/시즌 이름 포함 매칭 (이야기 태그 없이도)
        val customStories0 = ctx?.let { CustomItemManager.getStories(it) } ?: emptySet()
        val customLevels0   = ctx?.let { CustomItemManager.getLevels(it) } ?: emptySet()
        val customSeasons0  = ctx?.let { CustomItemManager.getSeasons(it) } ?: emptySet()
        val allStories = STORY_LIST + customStories0 + LEVEL_LIST + customLevels0 + SEASON_LIST + customSeasons0
        val storyHit = allStories
            .filter { candidate ->
                val cn = normalize(candidate)
                cn.length >= 4 && cleanedNorm.contains(cn)
            }
            .maxByOrNull { normalize(it).length }
        if (storyHit != null) {
            // 주요 스토리 인터셉트: 시즌/레벨/이야기로 분류된 제목이어도 주요 스토리면 우선 처리
            val mainStoryHit3 = MAIN_STORY_REDIRECT_TITLES.firstOrNull { title ->
                normalize(storyHit).contains(normalize(title)) ||
                normalize(title).contains(normalize(storyHit))
            }
            if (mainStoryHit3 != null) {
                Log.d(TAG, "STEP0-3 주요 스토리 인터셉트: '$mainStoryHit3'")
                return ResolvedEntry(
                    title = mainStoryHit3, pageNum = pageNum,
                    type = EntryType.MAIN_STORY,
                    url = buildUrl("랜덤 인카운터/주요 스토리 인카운터")
                )
            }
            val customLevels0b  = ctx?.let { CustomItemManager.getLevels(it) } ?: emptySet()
            val customSeasons0b = ctx?.let { CustomItemManager.getSeasons(it) } ?: emptySet()
            val type = when (storyHit) {
                in LEVEL_LIST, in customLevels0b  -> EntryType.LEVEL
                in SEASON_LIST, in customSeasons0b -> EntryType.SEASON
                else -> EntryType.STORY
            }
            return makeStoryEntry(storyHit, pageNum, type)
        }

        return null
    }

    // ── 제목만으로 카테고리 자동 판별 (선택지 오인식용) ─────────────────────

    private fun resolveByTitle(candidate: String, pageNum: String = ""): ResolvedEntry? {
        val customExpansions1 = emptySet<String>() // ctx 없는 경로, 하드코딩 목록만 사용
        val allExpansions = EXPANSION_LIST + customExpansions1
        val aliasResolved = resolveAlias(candidate)
        val title = aliasResolved ?: candidate

        // 확장팩 정확 일치 우선
        exactMatchPrecise(title, allExpansions)?.let { matched ->
            return ResolvedEntry(
                title = matched, pageNum = pageNum,
                type = EntryType.EXPANSION,
                url = buildUrl("랜덤 인카운터/$matched")
            )
        }
        // 이야기 계열 정확 일치
        resolveStoryExact(title, pageNum)?.let { return it }
        // 확장팩 fuzzy
        fuzzyFind(title, allExpansions)?.let { matched ->
            return ResolvedEntry(
                title = matched, pageNum = pageNum,
                type = EntryType.EXPANSION,
                url = buildUrl("랜덤 인카운터/$matched")
            )
        }
        // 이야기 계열 fuzzy
        return resolveStoryFuzzy(title, pageNum)
    }

    private fun resolveStoryExact(ocr: String, pageNum: String): ResolvedEntry? {
        val allStories = STORY_LIST
        matchLevelTitle(ocr)?.let { return makeStoryEntry(it, pageNum, EntryType.LEVEL) }
        exactMatchPrecise(ocr, LEVEL_LIST)?.let { return makeStoryEntry(it, pageNum, EntryType.LEVEL) }
        exactMatchPrecise(ocr, SEASON_LIST)?.let { return makeStoryEntry(it, pageNum, EntryType.SEASON) }
        exactMatchPrecise(ocr, allStories)?.let {
            return makeStoryEntry(it, pageNum, EntryType.STORY)
        }
        return null
    }

    private fun resolveStoryFuzzy(ocr: String, pageNum: String): ResolvedEntry? {
        val allStories = STORY_LIST
        fuzzyFind(ocr, LEVEL_LIST)?.let { return makeStoryEntry(it, pageNum, EntryType.LEVEL) }
        fuzzyFind(ocr, SEASON_LIST)?.let { return makeStoryEntry(it, pageNum, EntryType.SEASON) }
        fuzzyFind(ocr, allStories)?.let {
            return makeStoryEntry(it, pageNum, EntryType.STORY)
        }
        return null
    }

    // ── 주요 스토리 인카운터로 라우팅할 메인 스토리 제목 목록 ─────────────────
    // 이 목록에 포함된 제목이 이야기 태그와 함께 OCR에 잡히면,
    // WikiUrlResolver의 이야기/레벨 URL 대신 MainStoryEncounterResolver로 위임
    private val MAIN_STORY_REDIRECT_TITLES: Set<String> = setOf(
        // 코마
        "메인 스토리 : 코마", "메인 스토리 : 코마(30레벨)",
        "도봉산 마을 : 코마",
        // 짐승소년
        "메인 스토리 : 짐승소년",
        "도봉산 마을 : 짐승소년",
        // 괴물
        "메인스토리 : 괴물",
        "메인 스토리 : 괴물",
        // 시뮬라크르
        "메인 스토리 : 시뮬라크르"
    )

    private fun resolveStory(ocrTitle: String, pageNum: String): ResolvedEntry? {
        val allStories = STORY_LIST

        // ── 주요 스토리 인카운터 인터셉트 ────────────────────────────────────
        // "이야기 메인 스토리 : 코마" 등이 OCR에 잡히면 이야기/레벨 URL이 아닌
        // MainStoryEncounterResolver(주요 스토리 인카운터)로 라우팅
        val normalizedOcr = normalize(ocrTitle)
        val redirectMatch = MAIN_STORY_REDIRECT_TITLES.firstOrNull { title ->
            normalizedOcr.contains(normalize(title))
        }
        if (redirectMatch != null) {
            Log.d(TAG, "주요 스토리 인터셉트: '$redirectMatch' → MainStoryEncounterResolver")
            return ResolvedEntry(
                title = redirectMatch, pageNum = pageNum,
                type = EntryType.MAIN_STORY,
                url = buildUrl("랜덤 인카운터/주요 스토리 인카운터")
            )
        }
        // ─────────────────────────────────────────────────────────────────────

        val levelTitle = matchLevelTitle(ocrTitle)
            ?: exactMatchPrecise(ocrTitle, LEVEL_LIST)
            ?: fuzzyFind(ocrTitle, LEVEL_LIST)
        val seasonTitle = exactMatchPrecise(ocrTitle, SEASON_LIST)
            ?: fuzzyFind(ocrTitle, SEASON_LIST)
        val storyTitle = exactMatchPrecise(ocrTitle, allStories)
            ?: fuzzyFind(ocrTitle, allStories)

        val type: EntryType
        val finalTitle: String
        when {
            levelTitle != null  -> { type = EntryType.LEVEL;  finalTitle = levelTitle }
            seasonTitle != null -> { type = EntryType.SEASON; finalTitle = seasonTitle }
            storyTitle != null  -> {
                type = EntryType.STORY
                finalTitle = storyTitle
            }
            dynamicLevelStories.any { fuzzyMatch(it, ocrTitle) } -> {
                type = EntryType.LEVEL
                finalTitle = fuzzyFind(ocrTitle, dynamicLevelStories) ?: ocrTitle
            }
            dynamicSeasonStories.any { fuzzyMatch(it, ocrTitle) } -> {
                type = EntryType.SEASON
                finalTitle = fuzzyFind(ocrTitle, dynamicSeasonStories) ?: ocrTitle
            }
            // 하드코딩·동적 목록 어디에도 없으면 팝업 없이 종료.
            // 미등록 이야기/시즌/레벨은 사용자가 직접 등록해야만 연결됨.
            else -> return null
        }

        return makeStoryEntry(finalTitle, pageNum, type)
    }

    private fun makeStoryEntry(title: String, pageNum: String, type: EntryType): ResolvedEntry {
        val basePath = when (type) {
            EntryType.LEVEL  -> "랜덤 인카운터/레벨 인카운터"
            EntryType.SEASON -> "랜덤 인카운터/시즌 패스 인카운터"
            else             -> "랜덤 인카운터/이야기 인카운터"
        }
        return ResolvedEntry(
            title = title, pageNum = pageNum, type = type,
            url = buildUrl(basePath) + "#" + URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        )
    }

    // ── 매칭 유틸 ─────────────────────────────────────────────────────────────

    /**
     * normalize 후 완전 일치.
     * "동물 친구들" vs "동물 친구들 2" 처럼 포함 관계가 있는 이름의 오버매칭을 막기 위해
     * exactMatch를 항상 먼저 시도하고, 실패했을 때만 fuzzy로 넘어간다.
     */
    private fun exactMatchPrecise(ocr: String, list: Set<String>): String? {
        val cleanOcr = normalize(ocr)
        return list.firstOrNull { normalize(it) == cleanOcr }
    }

    /** 레벨 이야기: "(n레벨)" 없는 게임 제목 → 위키 전체 제목 복원 */
    private fun matchLevelTitle(ocr: String): String? {
        val cleanOcr = normalize(ocr)
        // 정확 일치
        LEVEL_TITLE_ONLY_MAP.entries.firstOrNull { normalize(it.key) == cleanOcr }
            ?.let { return it.value }
        // 편집 거리 기반 매칭
        return LEVEL_TITLE_ONLY_MAP.entries
            .filter { (key, _) ->
                val c = normalize(key)
                editDistanceOk(cleanOcr, c)
            }
            .minByOrNull { editDistance(normalize(it.key), cleanOcr) }
            ?.value
    }

    /**
     * 편집 거리 기반 fuzzy 매칭.
     *
     * OCR 오인식 패턴:
     *   - 한 글자 오인식: 말싸움→말짜움, 물건→물전, 메인→머인
     *   - 한 글자 누락/추가: 공백·특수문자 제거 후 발생 가능
     *
     * 허용 오차 (normalize 후 길이 기준):
     *   1~4 글자  → 거리 0 (정확 일치만, 짧은 이름 오버매칭 방지)
     *   5~7 글자  → 거리 1 (1글자 오인식 허용)
     *   8~11 글자 → 거리 2 (2글자 오인식 허용)
     *   12+ 글자  → 거리 3 (긴 제목 3글자까지 허용)
     *
     * exactMatchPrecise 이후에 호출할 것 (오버매칭 방지).
     */
    private fun fuzzyFind(ocr: String, list: Set<String>): String? {
        val cleanOcr = normalize(ocr)
        return list
            .filter { candidate -> editDistanceOk(cleanOcr, normalize(candidate)) }
            .minByOrNull { editDistance(normalize(it), cleanOcr) }
    }

    private fun fuzzyMatch(a: String, b: String): Boolean {
        val ca = normalize(a); val cb = normalize(b)
        return editDistanceOk(ca, cb)
    }

    /**
     * 허용 오차 내인지 확인.
     * 길이 차이가 허용 오차를 초과하면 바로 false (레벤슈타인 계산 생략, 성능 최적화).
     */
    private fun editDistanceOk(a: String, b: String): Boolean {
        val maxAllowed = allowedDistance(maxOf(a.length, b.length))
        if (maxAllowed == 0) return a == b
        if (kotlin.math.abs(a.length - b.length) > maxAllowed) return false
        return editDistance(a, b) <= maxAllowed
    }

    /** 제목 길이에 따른 허용 편집 거리 */
    private fun allowedDistance(len: Int): Int = when {
        len <= 4  -> 0
        len <= 7  -> 1
        len <= 11 -> 2
        else      -> 3
    }

    /**
     * 레벤슈타인 편집 거리 (삽입/삭제/교체 각 1).
     * 한글 한 글자 단위로 비교 (자모 분리 없이 글자 단위로도 OCR 오인식 패턴 충분히 커버).
     *
     * 최적화: 목표 거리 초과 시 조기 종료 (earlyExit).
     */
    private fun editDistance(a: String, b: String, earlyExit: Int = 4): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > earlyExit) return earlyExit + 1

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                curr[j] = if (a[i - 1] == b[j - 1]) {
                    prev[j - 1]
                } else {
                    1 + minOf(prev[j], curr[j - 1], prev[j - 1])
                }
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            // 이 행의 최솟값이 이미 earlyExit 초과면 조기 종료
            if (rowMin > earlyExit) return earlyExit + 1
            prev.indices.forEach { prev[it] = curr[it] }
        }
        return curr[b.length]
    }

    private fun normalize(s: String) = s.replace(Regex("[\\s·!?.,':ー–—-]"), "").lowercase()

    private fun buildUrl(path: String): String {
        val encoded = URLEncoder.encode("서울 2033/$path", "UTF-8").replace("+", "%20")
        return "$BASE/w/$encoded"
    }
}

enum class EntryType(val label: String) {
    EXPANSION("확장팩"),
    STORY("이야기"),
    LEVEL("레벨업 보상"),
    SEASON("시즌패스"),
    BASIC("기본 인카운터"),
    BASIC_ENCOUNTER("기본 인카운터"),         // 기본 인카운터 섹션 직접 연결용
    ACTIVE_ENCOUNTER("액티브 인카운터"),      // 액티브 인카운터 섹션 직접 연결용
    MAIN_STORY("주요 스토리 인카운터"),       // 주요 스토리 인카운터 섹션 직접 연결용
    EXPANSION_ENCOUNTER("확장팩 인카운터"),   // 확장팩 내 섹션 직접 연결용
    HARD_MODE_ENCOUNTER("하드 모드")           // 하드 모드 / 명예 도전 전용 인카운터
}

data class ResolvedEntry(
    val title: String, val pageNum: String, val type: EntryType, val url: String
)
