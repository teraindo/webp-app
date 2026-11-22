package com.teraindo.renamer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.random.Random

class MainActivity : Activity() {

    data class Item(val docId: String, val name: String, val modified: Long, val mime: String)
    data class Job(val docId: String, val oldName: String, val newName: String)

    private val reqPickFolder = 41
    private val prefsName = "renamer"
    private val imageExts = setOf(
        "jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp",
        "tif", "tiff", "dng", "avif", "raw"
    )

    private val ui = Handler(Looper.getMainLooper())
    private var treeUri: Uri? = null
    private var allItems = listOf<Item>()
    private var images = listOf<Item>()
    private var jobs = listOf<Job>()
    private var busy = false

    private lateinit var folderText: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var uuidBox: LinearLayout
    private lateinit var imgBox: LinearLayout
    private lateinit var caseGroup: RadioGroup
    private lateinit var prefixEdit: EditText
    private lateinit var startEdit: EditText
    private lateinit var numGroup: RadioGroup
    private lateinit var sortGroup: RadioGroup
    private lateinit var extGroup: RadioGroup
    private lateinit var previewTitle: TextView
    private lateinit var previewText: TextView
    private lateinit var runButton: Button
    private lateinit var rerollButton: Button
    private lateinit var undoButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        restoreFolder()
        refreshUndo()
    }

    // ---------- 화면 구성 ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun radios(vararg labels: String, horizontal: Boolean = true): RadioGroup =
        RadioGroup(this).apply {
            orientation = if (horizontal) RadioGroup.HORIZONTAL else RadioGroup.VERTICAL
            labels.forEachIndexed { i, l ->
                addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    text = l
                    setPadding(0, 0, dp(12), 0)
                })
                if (i == 0) this.check(getChildAt(0).id)
            }
        }

    private fun RadioGroup.index(): Int {
        for (i in 0 until childCount) if (getChildAt(i).id == checkedRadioButtonId) return i
        return 0
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(40))
        }

        root.addView(header("1. 폴더"))
        folderText = TextView(this).apply { text = "선택한 폴더가 없습니다." }
        root.addView(folderText)
        root.addView(Button(this).apply {
            text = "폴더 선택"
            setOnClickListener { pickFolder() }
        })

        root.addView(header("2. 이름 형식"))
        modeGroup = radios("UUID", "IMG_1234")
        root.addView(modeGroup)

        uuidBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        uuidBox.addView(TextView(this).apply { text = "대소문자"; setPadding(0, dp(8), 0, 0) })
        caseGroup = radios("대문자 (E621B27A-…)", "소문자 (e621b27a-…)", horizontal = false)
        uuidBox.addView(caseGroup)
        root.addView(uuidBox)

        imgBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        imgBox.addView(TextView(this).apply { text = "앞 글자"; setPadding(0, dp(8), 0, 0) })
        prefixEdit = EditText(this).apply { setText("IMG_"); isSingleLine = true }
        imgBox.addView(prefixEdit)
        imgBox.addView(TextView(this).apply { text = "시작 번호" })
        startEdit = EditText(this).apply { setText("1"); inputType = InputType.TYPE_CLASS_NUMBER }
        imgBox.addView(startEdit)
        imgBox.addView(TextView(this).apply { text = "번호 방식" })
        numGroup = radios("순서대로", "무작위")
        imgBox.addView(numGroup)
        imgBox.addView(TextView(this).apply { text = "순서 기준"; setPadding(0, dp(8), 0, 0) })
        sortGroup = radios("파일 이름", "찍은(수정) 날짜")
        imgBox.addView(sortGroup)
        root.addView(imgBox)

        root.addView(TextView(this).apply { text = "확장자"; setPadding(0, dp(10), 0, 0) })
        extGroup = radios("원래대로", ".JPG", ".jpg")
        root.addView(extGroup)

        root.addView(header("3. 미리보기"))
        previewTitle = TextView(this)
        root.addView(previewTitle)
        previewText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(previewText)
        rerollButton = Button(this).apply {
            text = "이름 다시 만들기"
            setOnClickListener { rebuild() }
        }
        root.addView(rerollButton)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
        }
        root.addView(progress)

        runButton = Button(this).apply {
            text = "이름 바꾸기 실행"
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { confirmRun() }
        }
        root.addView(runButton)

        undoButton = Button(this).apply {
            text = "마지막 작업 되돌리기"
            setOnClickListener { confirmUndo() }
        }
        root.addView(undoButton)

        statusText = TextView(this).apply { setPadding(0, dp(10), 0, 0) }
        root.addView(statusText)

        setContentView(ScrollView(this).apply { addView(root) })

        val onChange = RadioGroup.OnCheckedChangeListener { _, _ -> onOptionsChanged() }
        modeGroup.setOnCheckedChangeListener(onChange)
        caseGroup.setOnCheckedChangeListener(onChange)
        numGroup.setOnCheckedChangeListener(onChange)
        sortGroup.setOnCheckedChangeListener(onChange)
        extGroup.setOnCheckedChangeListener(onChange)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { rebuild() }
        }
        prefixEdit.addTextChangedListener(watcher)
        startEdit.addTextChangedListener(watcher)

        rebuild()
    }

    private fun onOptionsChanged() {
        val uuid = modeGroup.index() == 0
        uuidBox.visibility = if (uuid) View.VISIBLE else View.GONE
        imgBox.visibility = if (uuid) View.GONE else View.VISIBLE
        rebuild()
    }

    // ---------- 폴더 ----------

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(i, reqPickFolder)
        } catch (e: Exception) {
            status("폴더 선택 화면을 열 수 없습니다: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqPickFolder || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        getSharedPreferences(prefsName, MODE_PRIVATE).edit().putString("tree", uri.toString()).apply()
        treeUri = uri
        loadFolder()
    }

    private fun restoreFolder() {
        val s = getSharedPreferences(prefsName, MODE_PRIVATE).getString("tree", null) ?: return
        val uri = Uri.parse(s)
        val ok = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!ok) return
        treeUri = uri
        loadFolder()
    }

    private fun folderLabel(uri: Uri): String {
        val id = try { DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { return uri.toString() }
        val path = id.substringAfter(':', id)
        val vol = id.substringBefore(':', "")
        val volName = if (vol == "primary") "내장 저장공간" else vol
        return if (path.isEmpty()) volName else "$volName / $path"
    }

    private fun queryChildren(tree: Uri): List<Item> {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Item>()
        contentResolver.query(
            children,
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_LAST_MODIFIED,
                Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mod = if (c.isNull(2)) 0L else c.getLong(2)
                val mime = c.getString(3) ?: ""
                if (mime == Document.MIME_TYPE_DIR) continue
                out.add(Item(id, name, mod, mime))
            }
        }
        return out
    }

    private fun isImage(it: Item): Boolean =
        it.mime.startsWith("image/") || extOf(it.name).lowercase(Locale.ROOT) in imageExts

    private fun loadFolder() {
        val tree = treeUri ?: return
        folderText.text = folderLabel(tree)
        refreshUndo()
        status("사진을 읽는 중…")
        thread {
            val result = try { queryChildren(tree) } catch (e: Exception) { null }
            ui.post {
                if (result == null) {
                    status("폴더를 읽지 못했습니다. 폴더를 다시 선택해 주세요.")
                    allItems = emptyList(); images = emptyList()
                } else {
                    allItems = result
                    images = result.filter { isImage(it) }
                    status("")
                }
                rebuild()
            }
        }
    }

    // ---------- 이름 만들기 ----------

    private fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0 && i < name.length - 1) name.substring(i + 1) else ""
    }

    private fun baseOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }

    private fun naturalCompare(a: String, b: String): Int {
        val re = Regex("\\d+|\\D+")
        val pa = re.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val pb = re.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]; val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0'); val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return pa.size - pb.size
    }

    private fun formatExt(ext: String): String {
        if (ext.isEmpty()) return ""
        val e = when (extGroup.index()) {
            1 -> ext.uppercase(Locale.ROOT)
            2 -> ext.lowercase(Locale.ROOT)
            else -> ext
        }
        return ".$e"
    }

    private fun rebuild() {
        if (!::statusText.isInitialized) return
        val uuidMode = modeGroup.index() == 0
        val ordered = if (uuidMode) {
            images.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        } else if (sortGroup.index() == 1) {
            images.sortedBy { it.modified }
        } else {
            images.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        }

        val selectedIds = ordered.map { it.docId }.toSet()
        val taken = HashSet<String>()
        allItems.filter { it.docId !in selectedIds }.forEach { taken.add(it.name.lowercase(Locale.ROOT)) }

        val prefix = prefixEdit.text.toString()
        val start = startEdit.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val randomNum = numGroup.index() == 1
        val usedNums = HashSet<Int>()

        val list = ArrayList<Job>()
        ordered.forEachIndexed { k, item ->
            val ext = formatExt(extOf(item.name))
            val base = if (uuidMode) {
                val u = UUID.randomUUID().toString()
                if (caseGroup.index() == 0) u.uppercase(Locale.ROOT) else u.lowercase(Locale.ROOT)
            } else if (randomNum) {
                var n: Int
                do { n = Random.nextInt(10000) } while (n in usedNums && usedNums.size < 10000)
                usedNums.add(n)
                prefix + n.toString().padStart(4, '0')
            } else {
                prefix + (start + k).toString().padStart(4, '0')
            }
            var name = base + ext
            var dup = 1
            while (name.lowercase(Locale.ROOT) in taken) {
                name = base + "_" + dup++ + ext
            }
            taken.add(name.lowercase(Locale.ROOT))
            list.add(Job(item.docId, item.name, name))
        }
        jobs = list

        if (treeUri == null) {
            previewTitle.text = "폴더를 먼저 선택해 주세요."
            previewText.text = ""
        } else if (jobs.isEmpty()) {
            previewTitle.text = "이 폴더에는 사진이 없습니다."
            previewText.text = ""
        } else {
            previewTitle.text = "사진 ${jobs.size}장"
            val shown = jobs.take(150).joinToString("\n") { "${it.oldName}\n  → ${it.newName}" }
            previewText.text = if (jobs.size > 150) "$shown\n…외 ${jobs.size - 150}장" else shown
        }
        val randomish = uuidMode || randomNum
        rerollButton.visibility = if (randomish && jobs.isNotEmpty()) View.VISIBLE else View.GONE
        runButton.isEnabled = jobs.isNotEmpty() && !busy
    }

    // ---------- 실행 ----------

    private fun confirmRun() {
        if (jobs.isEmpty() || busy) return
        AlertDialog.Builder(this)
            .setTitle("이름 바꾸기")
            .setMessage("사진 ${jobs.size}장의 파일 이름을 바꿉니다.\n'마지막 작업 되돌리기'로 원래 이름으로 돌릴 수 있습니다.")
            .setPositiveButton("실행") { _, _ -> runJobs(jobs, saveUndo = true) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun rename(uri: Uri, name: String): Uri? = try {
        DocumentsContract.renameDocument(contentResolver, uri, name)
    } catch (_: Exception) { null }

    private fun runJobs(todo: List<Job>, saveUndo: Boolean) {
        val tree = treeUri ?: return
        busy = true
        runButton.isEnabled = false
        undoButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.max = todo.size * 2
        progress.progress = 0
        status("이름을 바꾸는 중…")

        thread {
            val failed = ArrayList<String>()
            val undo = ArrayList<Pair<String, String>>() // 현재 이름 → 원래 이름
            var step = 0

            // 1단계: 이름이 겹치지 않도록 임시 이름으로 바꿈
            val staged = ArrayList<Pair<Uri, Job>>()
            for (j in todo) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, j.docId)
                val ext = extOf(j.oldName)
                val tmp = "rn_tmp_" + UUID.randomUUID().toString().replace("-", "") +
                    (if (ext.isEmpty()) "" else ".$ext")
                val r = rename(uri, tmp)
                if (r == null) failed.add(j.oldName) else staged.add(r to j)
                step++
                val s = step
                ui.post { progress.progress = s }
            }

            // 2단계: 최종 이름으로 바꿈
            for ((uri, j) in staged) {
                val r = rename(uri, j.newName)
                if (r == null) {
                    rename(uri, j.oldName) // 실패하면 원래 이름으로 복구
                    failed.add(j.oldName)
                } else {
                    val actual = displayName(r) ?: j.newName
                    undo.add(actual to j.oldName)
                }
                step++
                val s = step
                ui.post { progress.progress = s }
            }

            if (saveUndo && undo.isNotEmpty()) {
                val text = undo.joinToString("\n") { "${it.first}\t${it.second}" }
                getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                    .putString("undo", text)
                    .putString("undoTree", tree.toString())
                    .apply()
            } else if (!saveUndo) {
                getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                    .remove("undo").remove("undoTree").apply()
            }

            ui.post {
                busy = false
                progress.visibility = View.GONE
                val ok = todo.size - failed.size
                val head = if (saveUndo) "${ok}장의 이름을 바꿨습니다." else "${ok}장을 원래 이름으로 되돌렸습니다."
                status(
                    if (failed.isEmpty()) head
                    else "$head\n바꾸지 못한 파일 ${failed.size}개:\n" + failed.take(20).joinToString("\n")
                )
                refreshUndo()
                loadFolder()
            }
        }
    }

    // ---------- 되돌리기 ----------

    private fun readUndo(): List<Pair<String, String>> {
        val p = getSharedPreferences(prefsName, MODE_PRIVATE)
        val text = p.getString("undo", null) ?: return emptyList()
        return text.split("\n").mapNotNull {
            val parts = it.split("\t")
            if (parts.size == 2) parts[0] to parts[1] else null
        }
    }

    private fun refreshUndo() {
        val p = getSharedPreferences(prefsName, MODE_PRIVATE)
        val has = readUndo().isNotEmpty() && p.getString("undoTree", null) == treeUri?.toString()
        undoButton.visibility = if (has) View.VISIBLE else View.GONE
        undoButton.isEnabled = !busy
    }

    private fun confirmUndo() {
        val pairs = readUndo()
        if (pairs.isEmpty() || busy) return
        AlertDialog.Builder(this)
            .setTitle("되돌리기")
            .setMessage("마지막으로 바꾼 사진 ${pairs.size}장을 원래 이름으로 되돌립니다.")
            .setPositiveButton("되돌리기") { _, _ ->
                val byName = allItems.associateBy { it.name }
                val todo = pairs.mapNotNull { (cur, orig) ->
                    byName[cur]?.let { Job(it.docId, cur, orig) }
                }
                if (todo.isEmpty()) status("되돌릴 파일을 찾지 못했습니다.")
                else runJobs(todo, saveUndo = false)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun status(s: String) {
        statusText.text = s
    }
}
