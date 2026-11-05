package com.fred.progress

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.fred.progress.model.Bar
import com.fred.progress.model.BarStore
import com.fred.progress.prefs.SyncPrefs
import com.fred.progress.sync.Sync

/**
 * 普通 App 的主界面：打开就能看到所有进度条，增删改 + 同步入口都在这里。
 * 刻意不引 RecyclerView / AppCompat / Material —— 零第三方依赖，全用系统控件。
 */
class MainActivity : Activity() {

    private lateinit var store: BarStore
    private lateinit var sync: Sync
    private lateinit var barsContainer: LinearLayout
    private lateinit var emptyHint: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable { doSync() }

    /** 30 分钟兜底（可选）：App 一直开着时，每半小时补一次同步 */
    private val fallbackRunnable = object : Runnable {
        override fun run() {
            if (SyncPrefs(this@MainActivity).ready) doSync()
            ui.postDelayed(this, FALLBACK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = BarStore(this)
        sync = Sync(this, store)
        barsContainer = findViewById(R.id.barsContainer)
        emptyHint = findViewById(R.id.emptyHint)

        // 顶部按钮当菜单用：新建 / 同步设置 / 立即同步
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showBarDialog(null) }
        findViewById<Button>(R.id.btnSyncSettings).setOnClickListener { showSyncDialog() }
        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            // 手动"立即同步"：不走 10 秒攒批，马上跑一次
            ui.removeCallbacks(syncRunnable)
            ui.post(syncRunnable)
        }

        render()
    }

    override fun onStart() {
        super.onStart()
        // 打开 App 拉一次：这一步既把云端更新拉下来，
        // 也会通过 merge 把本地还没传的改动带上去（所以不依赖那条 10 秒延迟任务）。
        if (SyncPrefs(this).ready) {
            ui.removeCallbacks(syncRunnable)
            ui.post(syncRunnable)
        }
        // 30 分钟兜底：可选，长驻时每半小时补一次
        ui.removeCallbacks(fallbackRunnable)
        ui.postDelayed(fallbackRunnable, FALLBACK_MS)
    }

    override fun onStop() {
        ui.removeCallbacks(fallbackRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacks(syncRunnable)
        super.onDestroy()
    }

    // ===== 列表 =====

    private fun render() {
        barsContainer.removeAllViews()
        val bars = store.bars()                 // UI 只显示 deleted=false 的条目
        emptyHint.visibility = if (bars.isEmpty()) View.VISIBLE else View.GONE
        for (bar in bars) {
            val row = layoutInflater.inflate(R.layout.item_bar, barsContainer, false)
            row.findViewById<TextView>(R.id.barName).text = bar.name
            row.findViewById<TextView>(R.id.barTheme).text = themeLabel(bar.theme)
            row.findViewById<TextView>(R.id.barCount).text = "${bar.current}/${bar.total}"
            row.findViewById<ProgressView>(R.id.barProgress).set(bar)

            row.findViewById<Button>(R.id.btnMinus).setOnClickListener {
                store.step(bar.uuid, -1)
                render()
                scheduleSync()
            }
            row.findViewById<Button>(R.id.btnPlus).setOnClickListener {
                store.step(bar.uuid, +1)
                render()
                scheduleSync()
            }
            row.findViewById<Button>(R.id.btnEdit).setOnClickListener { showBarDialog(bar) }
            row.findViewById<Button>(R.id.btnDelete).setOnClickListener { confirmDelete(bar) }
            barsContainer.addView(row)
        }
    }

    private fun themeLabel(theme: String): String = when (theme) {
        Bar.THEME_METRO -> getString(R.string.theme_metro)
        Bar.THEME_DOTS -> getString(R.string.theme_dots)
        else -> getString(R.string.theme_stairs)
    }

    private fun confirmDelete(bar: Bar) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_title))
            .setMessage(getString(R.string.dialog_delete_msg, bar.name))
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                store.delete(bar.uuid)          // 写墓碑，不物理删
                render()
                scheduleSync()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ===== 新建 / 修改 =====

    private fun showBarDialog(existing: Bar?) {
        val view = layoutInflater.inflate(R.layout.dialog_bar, null)
        val nameEdit = view.findViewById<EditText>(R.id.nameEdit)
        val totalEdit = view.findViewById<EditText>(R.id.totalEdit)
        val group = view.findViewById<RadioGroup>(R.id.themeGroup)

        if (existing != null) {
            nameEdit.setText(existing.name)
            totalEdit.setText(existing.total.toString())
            when (existing.theme) {
                Bar.THEME_METRO -> group.check(R.id.rbMetro)
                Bar.THEME_DOTS -> group.check(R.id.rbDots)
                else -> group.check(R.id.rbStairs)
            }
        } else {
            group.check(R.id.rbStairs)
            totalEdit.setText("20")
        }

        AlertDialog.Builder(this)
            .setTitle(getString(if (existing == null) R.string.dialog_add_title else R.string.dialog_edit_title))
            .setView(view)
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                val name = nameEdit.text.toString().trim()
                val total = totalEdit.text.toString().toIntOrNull() ?: 0
                if (name.isEmpty()) {
                    toast(getString(R.string.toast_name_required))
                    return@setPositiveButton
                }
                if (total <= 0) {
                    toast(getString(R.string.toast_total_invalid))
                    return@setPositiveButton
                }
                val theme = when (group.checkedRadioButtonId) {
                    R.id.rbMetro -> Bar.THEME_METRO
                    R.id.rbDots -> Bar.THEME_DOTS
                    else -> Bar.THEME_STAIRS
                }
                if (existing == null) store.add(name, total, theme)
                else store.update(existing.uuid, name, total, theme)
                render()
                scheduleSync()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ===== 同步设置 =====

    private fun showSyncDialog() {
        val prefs = SyncPrefs(this)
        val view = layoutInflater.inflate(R.layout.dialog_sync, null)
        val urlEdit = view.findViewById<EditText>(R.id.urlEdit)
        val userEdit = view.findViewById<EditText>(R.id.userEdit)
        val passEdit = view.findViewById<EditText>(R.id.passEdit)
        urlEdit.setText(prefs.url)
        userEdit.setText(prefs.user)
        passEdit.setText(prefs.password)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_sync_title))
            .setView(view)
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                prefs.url = urlEdit.text.toString()
                prefs.user = userEdit.text.toString()
                prefs.password = passEdit.text.toString()
                toast(getString(R.string.toast_saved))
                // 填好设置后立刻同步一次（顺便把本地已有数据传上去）
                ui.removeCallbacks(syncRunnable)
                ui.post(syncRunnable)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ===== 同步调度 =====

    /** 改动后攒 10 秒再传：连点 +1 不会每下都打一次网络 */
    private fun scheduleSync() {
        if (!SyncPrefs(this).ready) return
        ui.removeCallbacks(syncRunnable)
        ui.postDelayed(syncRunnable, DEBOUNCE_MS)
    }

    private fun doSync() {
        if (!SyncPrefs(this).ready) return   // 没配置账号就别打扰用户
        Thread {
            val result = sync.syncNow()
            ui.post {
                toast(result.message)
                render()                     // 云端拉下来的更新刷新到界面
            }
        }.start()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DEBOUNCE_MS = 10_000L
        private const val FALLBACK_MS = 30L * 60L * 1000L
    }
}
