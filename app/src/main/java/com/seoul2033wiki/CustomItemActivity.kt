package com.seoul2033wiki

import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * 이야기 / 레벨 / 시즌패스 / 확장팩을 직접 등록·삭제하는 관리 화면.
 *
 * 탭 인덱스: 0 이야기  1 레벨  2 시즌패스  3 확장팩
 */
@android.annotation.SuppressLint("SetTextI18n")
class CustomItemActivity : AppCompatActivity() {

    private val tabs = listOf("이야기", "레벨", "시즌패스", "확장팩")
    private var currentTab = 0

    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var tabBtns: List<Button>
    private lateinit var urlHintView: TextView
    private lateinit var levelHintView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentTab = when (intent.getStringExtra("tab")) {
            "level"     -> 1
            "season"    -> 2
            "expansion" -> 3
            else        -> 0
        }

        val ctx = this
        val density = resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24*density).toInt(), (48*density).toInt(), (24*density).toInt(), (72*density).toInt())
        }

        // 상단: 제목 + 닫기
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8*density).toInt())
        }
        topRow.addView(TextView(ctx).apply {
            text = "항목 관리"; textSize = 22f
            typeface = AppFont.bold(ctx)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(Button(ctx).apply {
            text = "닫기"; textSize = 14f; isSingleLine = true
            setPadding((20*density).toInt(), 0, (20*density).toInt(), 0)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48*density).toInt()))
        root.addView(topRow)

        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8*density).toInt(), 0, (16*density).toInt())
        }

        inner.addView(TextView(ctx).apply {
            text = "등록한 이름이 인식되면 아래 URL로 연결됩니다."
            typeface = AppFont.regular(ctx); textSize = 12f; setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, (12*density).toInt())
        })

        // 탭 버튼
        val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tabBtns = tabs.mapIndexed { idx, label ->
            Button(ctx).apply {
                text = label; textSize = 12.5f
                alpha = if (idx == currentTab) 1f else 0.40f
                setOnClickListener {
                    currentTab = idx
                    tabBtns.forEachIndexed { i, btn -> btn.alpha = if (i == idx) 1f else 0.40f }
                    refreshList()
                }
            }.also { btn ->
                tabRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        inner.addView(tabRow)

        urlHintView = TextView(ctx).apply {
            typeface = AppFont.regular(ctx); textSize = 11f; setTextColor(0xFF1E88E5.toInt())
            setPadding(0, (6*density).toInt(), 0, (2*density).toInt())
        }
        inner.addView(urlHintView)

        levelHintView = TextView(ctx).apply {
            text = "💡 레벨 인카운터는 위키에 표시된 형식 그대로 입력하세요.\n    예: 어느 날의 일 (1레벨)"
            typeface = AppFont.regular(ctx); textSize = 11f; setTextColor(0xFFE65100.toInt())
            setPadding(0, 0, 0, (6*density).toInt())
        }
        inner.addView(levelHintView)

        // 입력 행
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8*density).toInt(), 0, (4*density).toInt())
        }
        val editText = EditText(ctx).apply {
            hint = "이름 입력"; setSingleLine()
        }
        inputRow.addView(editText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(Button(ctx).apply {
            text = "추가"; textSize = 14f; isSingleLine = true
            setPadding((20*density).toInt(), 0, (20*density).toInt(), 0)
            setOnClickListener {
                val name = editText.text.toString().trim()
                if (name.isEmpty()) { Toast.makeText(ctx, "이름을 입력하세요", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                addItem(name); editText.setText(""); refreshList()
                Toast.makeText(ctx, "\"$name\" 추가됨", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48*density).toInt()))
        inner.addView(inputRow)

        // 하단 힌트 + 전체 삭제
        val bottomHintRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8*density).toInt())
        }
        bottomHintRow.addView(TextView(ctx).apply {
            text = "※ 탭: 수정 / 길게 누르면: 삭제"
            typeface = AppFont.regular(ctx); textSize = 11f; setTextColor(0xFF888888.toInt())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottomHintRow.addView(Button(ctx).apply {
            text = "전체 삭제"; textSize = 14f; setTextColor(0xFFCC0000.toInt()); isSingleLine = true
            setPadding((20*density).toInt(), 0, (20*density).toInt(), 0)
            setOnClickListener {
                val tabName = tabs[currentTab]
                AlertDialog.Builder(ctx).setTitle("전체 삭제")
                    .setMessage("\"$tabName\" 탭의 항목을 모두 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ ->
                        clearCurrentTab(); refreshList()
                        Toast.makeText(ctx, "\"$tabName\" 항목 전체 삭제됨", Toast.LENGTH_SHORT).show()
                    }.setNegativeButton("취소", null).show()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48*density).toInt()))
        inner.addView(bottomHintRow)

        // 목록
        adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, mutableListOf<String>())
        listView = ListView(ctx).apply {
            this.adapter = this@CustomItemActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val item = adapter.getItem(pos)?.toString() ?: return@setOnItemClickListener
                showEditDialog(item)
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                val item = adapter.getItem(pos)?.toString() ?: return@setOnItemLongClickListener false
                removeItem(item); refreshList()
                Toast.makeText(ctx, "\"$item\" 삭제됨", Toast.LENGTH_SHORT).show()
                true
            }
        }
        inner.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(inner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshList()
    }

    private fun refreshList() {
        urlHintView.text = when (currentTab) {
            0    -> "→ namu.wiki/w/서울 2033/랜덤 인카운터/이야기 인카운터#(제목)"
            1    -> "→ namu.wiki/w/서울 2033/랜덤 인카운터/레벨 인카운터#(제목 (n레벨))"
            2    -> "→ namu.wiki/w/서울 2033/랜덤 인카운터/시즌 패스 인카운터#(제목)"
            else -> "→ namu.wiki/w/서울 2033/랜덤 인카운터/(제목)"
        }
        levelHintView.visibility = if (currentTab == 1) android.view.View.VISIBLE else android.view.View.GONE
        adapter.clear(); adapter.addAll(currentItems()); adapter.notifyDataSetChanged()
    }

    private fun currentItems(): List<String> = when (currentTab) {
        0    -> CustomItemManager.getStories(this).sorted()
        1    -> CustomItemManager.getLevels(this).sorted()
        2    -> CustomItemManager.getSeasons(this).sorted()
        else -> CustomItemManager.getExpansions(this).sorted()
    }

    private fun addItem(name: String) = when (currentTab) {
        0    -> CustomItemManager.addStory(this, name)
        1    -> CustomItemManager.addLevel(this, name)
        2    -> CustomItemManager.addSeason(this, name)
        else -> CustomItemManager.addExpansion(this, name)
    }

    private fun removeItem(name: String) = when (currentTab) {
        0    -> CustomItemManager.removeStory(this, name)
        1    -> CustomItemManager.removeLevel(this, name)
        2    -> CustomItemManager.removeSeason(this, name)
        else -> CustomItemManager.removeExpansion(this, name)
    }

    private fun clearCurrentTab() = when (currentTab) {
        0    -> CustomItemManager.clearStories(this)
        1    -> CustomItemManager.clearLevels(this)
        2    -> CustomItemManager.clearSeasons(this)
        else -> CustomItemManager.clearExpansions(this)
    }

    private fun showEditDialog(oldName: String) {
        val ctx = this
        val density = resources.displayMetrics.density
        val editText = EditText(ctx).apply {
            setText(oldName); setSingleLine()
            setPadding((16*density).toInt(), (12*density).toInt(), (16*density).toInt(), (12*density).toInt())
        }
        AlertDialog.Builder(ctx).setTitle("항목 수정").setView(editText)
            .setPositiveButton("저장") { _, _ ->
                val newName = editText.text.toString().trim()
                when {
                    newName.isEmpty() -> Toast.makeText(ctx, "이름을 입력하세요", Toast.LENGTH_SHORT).show()
                    newName == oldName -> {}
                    else -> {
                        removeItem(oldName); addItem(newName); refreshList()
                        Toast.makeText(ctx, "\"$oldName\" → \"$newName\" 수정됨", Toast.LENGTH_SHORT).show()
                    }
                }
            }.setNegativeButton("취소", null).show()
    }
}
