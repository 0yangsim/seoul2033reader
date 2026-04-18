package com.seoul2033wiki

import android.content.Context
import android.graphics.Typeface

/**
 * KoPub 바탕체 폰트 헬퍼.
 *
 * ── 폰트 파일 설치 방법 ────────────────────────────────────────────────────
 * 아래 두 파일을 res/font/ 폴더에 추가해주세요:
 *   - kopub_batang_regular.ttf  (KoPub 바탕체 Light 또는 Regular)
 *   - kopub_batang_medium.ttf   (KoPub 바탕체 Medium 또는 Bold)
 *
 * 다운로드: https://www.kopus.org/biz-electronic-font2/
 * (한국출판인회의 무료 배포, 상업적 이용 가능)
 *
 * ── 사용법 ────────────────────────────────────────────────────────────────
 *   textView.typeface = AppFont.regular(context)
 *   textView.typeface = AppFont.bold(context)
 */
object AppFont {

    private var _regular: Typeface? = null
    private var _bold: Typeface? = null

    fun regular(ctx: Context): Typeface {
        if (_regular == null) {
            _regular = runCatching {
                ctx.resources.getFont(R.font.kopub_batang_regular)
            }.getOrElse { Typeface.SERIF }
        }
        return _regular!!
    }

    fun bold(ctx: Context): Typeface {
        if (_bold == null) {
            _bold = runCatching {
                ctx.resources.getFont(R.font.kopub_batang_medium)
            }.getOrElse { Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        }
        return _bold!!
    }
}
