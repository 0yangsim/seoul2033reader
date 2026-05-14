package com.seoul2033wiki

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

object UpdateChecker {

    // ← 본인 GitHub 계정명과 저장소명으로 변경하세요
    private const val GITHUB_USER = "0yangsim"
    private const val GITHUB_REPO = "seoul2033reader"

    private const val API_URL =
        "https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases/latest"

    suspend fun check(context: Context) {
        try {
            val latestTag = withContext(Dispatchers.IO) {
                val json = URL(API_URL).readText()
                JSONObject(json).getString("tag_name")
            }

            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: return

            val latest = latestTag.trimStart('v')
            val current = currentVersion.trimStart('v')

            if (latest != current && isNewer(latest, current)) {
                withContext(Dispatchers.Main) {
                    showUpdateDialog(context, latestTag)
                }
            }

        } catch (_: Exception) {
            // 네트워크 오류 등 — 조용히 무시
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    private fun showUpdateDialog(context: Context, latestTag: String) {
        AlertDialog.Builder(context)
            .setTitle("업데이트 안내")
            .setMessage("새로운 버전($latestTag)이 출시되었습니다.\n지금 업데이트하시겠습니까?")
            .setPositiveButton("업데이트") { _, _ ->
                val url = "https://play.google.com/store/apps/details?id=com.seoul2033wiki"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .setNegativeButton("나중에", null)
            .show()
    }
}