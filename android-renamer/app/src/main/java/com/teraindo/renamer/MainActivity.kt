package com.teraindo.renamer

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.random.Random

class MainActivity : Activity() {

    data class Photo(
        val id: Long,
        val uri: Uri,
        val name: String,
        val taken: Long,
        val bucket: String,
        val relPath: String
    )

    data class Job(val id: Long, val uri: Uri, val oldName: String, val newName: String)

    private val reqPerm = 11
    private val reqWrite = 12
    private val prefsName = "renamer"

    private val ui = Handler(Looper.getMainLooper())
    private val thumbPool = Executors.newFixedThreadPool(4)
    private val thumbCache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private var allPhotos = listOf<Photo>()
    private var shown = listOf<Photo>()
    private var buckets = listOf<String>()
    private val selected = LinkedHashSet<Long>()
    private var jobs = listOf<Job>()
    private var busy = false
    private var loading = false
    private var pendingWrite: (() -> Unit)? = null

    // 갤러리 화면
    private lateinit var galleryScreen: LinearLayout
    private lateinit var permBox: LinearLayout
    private lateinit var albumSpinner: Spinner
    private lateinit var selectAllButton: Button
    private lateinit var grid: GridView
    private lateinit var nextButton: Button
    private val photoAdapter = PhotoAdapter()

    // 설정 화면
    private lateinit var optionsScreen: ScrollView
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
        buildGallery()
        buildOptions()
        val frame = FrameLayout(this)
        frame.addView(galleryScreen, FrameLayout.LayoutParams(-1, -1))
        frame.addView(optionsScreen, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        showGallery()
        if (hasReadPermission()) loadPhotos() else askPermission()
    }

    override fun onResume() {
        super.onResume()
        if (hasReadPermission() && allPhotos.isEmpty() && !busy && !loading) loadPhotos()
    }

    override fun onDestroy() {
        thumbPool.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (optionsScreen.visibility == View.VISIBLE) {
            if (!busy) showGallery()
        } else {
            super.onBackPressed()
        }
    }

    // ---------- 공통 ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(0, dp(8), 0, 0)
    }

    private fun radios(vararg labels: String, horizontal: Boolean = true): RadioGroup {
        val g = RadioGroup(this)
        g.orientation = if (horizontal) RadioGroup.HORIZONTAL else RadioGroup.VERTICAL
        labels.forEach { l ->
            val b = RadioButton(this)
            b.id = View.generateViewId()
            b.text = l
            b.setPadding(0, 0, dp(12), 0)
            g.addView(b)
        }
        g.check(g.getChildAt(0).id)
        return g
    }

    private fun RadioGroup.index(): Int {
        for (i in 0 until childCount) if (getChildAt(i).id == checkedRadioButtonId) return i
        return 0
    }

    private fun status(s: String) {
        if (::statusText.isInitialized) statusText.text = s
    }

    // ---------- 권한 ----------

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasReadPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 ->
            granted("android.permission.READ_MEDIA_IMAGES") ||
                granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        Build.VERSION.SDK_INT >= 33 -> granted("android.permission.READ_MEDIA_IMAGES")
        else -> granted("android.permission.READ_EXTERNAL_STORAGE")
    }

    private fun askPermission() {
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf("android.permission.READ_MEDIA_IMAGES")
            else -> arrayOf("android.permission.READ_EXTERNAL_STORAGE")
        }
        requestPermissions(perms, reqPerm)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqPerm) return
        if (hasReadPermission()) loadPhotos() else showPermBox(true)
    }

    private fun showPermBox(show: Boolean) {
        permBox.visibility = if (show) View.VISIBLE else View.GONE
        grid.visibility = if (show) View.GONE else View.VISIBLE
    }

    // ---------- 갤러리 화면 ----------

    private fun buildGallery() {
        galleryScreen = LinearLayout(this)
        galleryScreen.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(8), dp(4), dp(8), dp(4))
        albumSpinner = Spinner(this)
        top.addView(albumSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectAllButton = Button(this)
        selectAllButton.text = "전체 선택"
        selectAllButton.setOnClickListener { toggleAll() }
        top.addView(selectAllButton)
        galleryScreen.addView(top)

        permBox = LinearLayout(this)
        permBox.orientation = LinearLayout.VERTICAL
        permBox.setPadding(dp(24), dp(40), dp(24), dp(24))
        permBox.visibility = View.GONE
        val permText = TextView(this)
        permText.text = "사진을 보여 드리려면 사진 접근 권한이 필요합니다."
        permText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        permBox.addView(permText)
        val permButton = Button(this)
        permButton.text = "권한 허용하기"
        permButton.setOnClickListener { askPermission() }
        permBox.addView(permButton)
        val settingsButton = Button(this)
        settingsButton.text = "설정에서 허용하기"
        settingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        permBox.addView(settingsButton)
        galleryScreen.addView(permBox)

        grid = GridView(this)
        grid.numColumns = 4
        grid.horizontalSpacing = dp(2)
        grid.verticalSpacing = dp(2)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.adapter = photoAdapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            val p = shown[pos]
            if (!selected.remove(p.id)) selected.add(p.id)
            photoAdapter.notifyDataSetChanged()
            updateSelectionUi()
        }
        galleryScreen.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        nextButton = Button(this)
        nextButton.text = "사진을 골라 주세요"
        nextButton.isEnabled = false
        nextButton.setTypeface(nextButton.typeface, Typeface.BOLD)
        nextButton.setOnClickListener { showOptions() }
        galleryScreen.addView(nextButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        albumSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyAlbum()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun queryPhotos(): List<Photo> {
        val list = ArrayList<Photo>()
        val coll = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        try {
            contentResolver.query(coll, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val taken = if (!c.isNull(2) && c.getLong(2) > 0) c.getLong(2) else c.getLong(3) * 1000
                    val bucket = c.getString(4) ?: "기타"
                    val rel = c.getString(5) ?: ""
                    list.add(Photo(id, ContentUris.withAppendedId(coll, id), name, taken, bucket, rel))
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun loadPhotos(after: (() -> Unit)? = null) {
        showPermBox(false)
        loading = true
        thread {
            val list = queryPhotos()
            ui.post {
                loading = false
                allPhotos = list
                selected.retainAll(list.map { it.id }.toSet())

                val counts = list.groupingBy { it.bucket }.eachCount()
                val names = counts.keys.sortedWith { a, b ->
                    val pa = if (a == "Camera") 0 else 1
                    val pb = if (b == "Camera") 0 else 1
                    if (pa != pb) pa - pb else a.compareTo(b)
                }
                val prev = buckets.getOrNull(albumSpinner.selectedItemPosition - 1)
                buckets = names
                val labels = listOf("최근 사진 (${list.size})") + names.map { "$it (${counts[it]})" }
                albumSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                val idx = if (prev == null) -1 else names.indexOf(prev)
                albumSpinner.setSelection(if (idx >= 0) idx + 1 else 0)
                applyAlbum()
                after?.invoke()
            }
        }
    }

    private fun applyAlbum() {
        val pos = albumSpinner.selectedItemPosition
        shown = if (pos <= 0) {
            allPhotos
        } else {
            val b = buckets.getOrNull(pos - 1)
            allPhotos.filter { it.bucket == b }
        }
        photoAdapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun toggleAll() {
        val ids = shown.map { it.id }
        if (ids.isNotEmpty() && selected.containsAll(ids)) selected.removeAll(ids.toSet())
        else selected.addAll(ids)
        photoAdapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val ids = shown.map { it.id }
        selectAllButton.text = if (ids.isNotEmpty() && selected.containsAll(ids)) "선택 해제" else "전체 선택"
        nextButton.isEnabled = selected.isNotEmpty()
        nextButton.text = if (selected.isEmpty()) "사진을 골라 주세요" else "다음 (${selected.size}장)"
    }

    private class SquareFrame(ctx: Context) : FrameLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }
    }

    private fun newCell(): SquareFrame {
        val cell = SquareFrame(this)
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.setBackgroundColor(Color.parseColor("#33888888"))
        cell.addView(img, FrameLayout.LayoutParams(-1, -1))
        val dim = View(this)
        dim.setBackgroundColor(Color.parseColor("#662F6FDE"))
        cell.addView(dim, FrameLayout.LayoutParams(-1, -1))
        val badge = TextView(this)
        badge.gravity = Gravity.CENTER
        badge.setTextColor(Color.WHITE)
        badge.setTypeface(badge.typeface, Typeface.BOLD)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(Color.parseColor("#2F6FDE"))
        circle.setStroke(dp(2), Color.WHITE)
        badge.background = circle
        val lp = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END)
        lp.setMargins(0, dp(4), dp(4), 0)
        cell.addView(badge, lp)
        return cell
    }

    private inner class PhotoAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = shown[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? SquareFrame) ?: newCell()
            val img = cell.getChildAt(0) as ImageView
            val dim = cell.getChildAt(1)
            val badge = cell.getChildAt(2) as TextView
            val p = shown[position]

            val order = if (p.id in selected) selected.indexOf(p.id) + 1 else 0
            dim.visibility = if (order > 0) View.VISIBLE else View.GONE
            badge.visibility = if (order > 0) View.VISIBLE else View.GONE
            badge.text = if (order > 0) order.toString() else ""

            img.tag = p.id
            val cached = thumbCache.get(p.id)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageDrawable(null)
                val px = dp(110)
                val uri = p.uri
                val id = p.id
                thumbPool.execute {
                    val bmp: Bitmap? = try {
                        contentResolver.loadThumbnail(uri, Size(px, px), null)
                    } catch (_: Exception) {
                        null
                    }
                    if (bmp != null) {
                        thumbCache.put(id, bmp)
                        ui.post { if (img.tag == id) img.setImageBitmap(bmp) }
                    }
                }
            }
            return cell
        }
    }

    // ---------- 설정 화면 ----------

    private fun buildOptions() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(6), dp(18), dp(40))

        val back = Button(this)
        back.text = "← 사진 다시 고르기"
        back.setOnClickListener { if (!busy) showGallery() }
        root.addView(back)

        root.addView(header("이름 형식"))
        modeGroup = radios("UUID", "IMG_1234")
        root.addView(modeGroup)

        uuidBox = LinearLayout(this)
        uuidBox.orientation = LinearLayout.VERTICAL
        uuidBox.addView(label("대소문자"))
        caseGroup = radios("대문자 (E621B27A-…)", "소문자 (e621b27a-…)", horizontal = false)
        uuidBox.addView(caseGroup)
        root.addView(uuidBox)

        imgBox = LinearLayout(this)
        imgBox.orientation = LinearLayout.VERTICAL
        imgBox.visibility = View.GONE
        imgBox.addView(label("앞 글자"))
        prefixEdit = EditText(this)
        prefixEdit.setText("IMG_")
        prefixEdit.isSingleLine = true
        imgBox.addView(prefixEdit)
        imgBox.addView(label("시작 번호"))
        startEdit = EditText(this)
        startEdit.setText("1")
        startEdit.inputType = InputType.TYPE_CLASS_NUMBER
        imgBox.addView(startEdit)
        imgBox.addView(label("번호 방식"))
        numGroup = radios("순서대로", "무작위")
        imgBox.addView(numGroup)
        imgBox.addView(label("순서 기준"))
        sortGroup = radios("고른 순서", "찍은 날짜", "파일 이름", horizontal = false)
        imgBox.addView(sortGroup)
        root.addView(imgBox)

        root.addView(label("확장자"))
        extGroup = radios("원래대로", ".JPG", ".jpg")
        root.addView(extGroup)

        root.addView(header("미리보기"))
        previewTitle = TextView(this)
        root.addView(previewTitle)
        previewText = TextView(this)
        previewText.typeface = Typeface.MONOSPACE
        previewText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        previewText.setPadding(0, dp(6), 0, dp(6))
        root.addView(previewText)

        rerollButton = Button(this)
        rerollButton.text = "이름 다시 만들기"
        rerollButton.setOnClickListener { rebuild() }
        root.addView(rerollButton)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.visibility = View.GONE
        root.addView(progress)

        runButton = Button(this)
        runButton.text = "이름 바꾸기 실행"
        runButton.setTypeface(runButton.typeface, Typeface.BOLD)
        runButton.setOnClickListener { confirmRun() }
        root.addView(runButton)

        undoButton = Button(this)
        undoButton.text = "마지막 작업 되돌리기"
        undoButton.visibility = View.GONE
        undoButton.setOnClickListener { confirmUndo() }
        root.addView(undoButton)

        statusText = TextView(this)
        statusText.setPadding(0, dp(10), 0, 0)
        root.addView(statusText)

        optionsScreen = ScrollView(this)
        optionsScreen.addView(root)

        val onChange = RadioGroup.OnCheckedChangeListener { _, _ -> onOptionsChanged() }
        modeGroup.setOnCheckedChangeListener(onChange)
        caseGroup.setOnCheckedChangeListener(onChange)
        numGroup.setOnCheckedChangeListener(onChange)
        sortGroup.setOnCheckedChangeListener(onChange)
        extGroup.setOnCheckedChangeListener(onChange)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                rebuild()
            }
        }
        prefixEdit.addTextChangedListener(watcher)
        startEdit.addTextChangedListener(watcher)
    }

    private fun showGallery() {
        optionsScreen.visibility = View.GONE
        galleryScreen.visibility = View.VISIBLE
        title = getString(R.string.app_name)
        photoAdapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun showOptions() {
        if (selected.isEmpty()) return
        galleryScreen.visibility = View.GONE
        optionsScreen.visibility = View.VISIBLE
        title = "사진 ${selected.size}장 이름 바꾸기"
        status("")
        rebuild()
        refreshUndo()
    }

    private fun onOptionsChanged() {
        val uuid = modeGroup.index() == 0
        uuidBox.visibility = if (uuid) View.VISIBLE else View.GONE
        imgBox.visibility = if (uuid) View.GONE else View.VISIBLE
        rebuild()
    }

    // ---------- 이름 만들기 ----------

    private fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0 && i < name.length - 1) name.substring(i + 1) else ""
    }

    private fun naturalCompare(a: String, b: String): Int {
        val re = Regex("\\d+|\\D+")
        val pa = re.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val pb = re.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else {
                x.compareTo(y)
            }
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
        val byId = allPhotos.associateBy { it.id }
        val picked = selected.mapNotNull { byId[it] }
        val uuidMode = modeGroup.index() == 0
        val ordered = if (uuidMode) {
            picked
        } else {
            when (sortGroup.index()) {
                1 -> picked.sortedBy { it.taken }
                2 -> picked.sortedWith { a, b -> naturalCompare(a.name, b.name) }
                else -> picked
            }
        }

        // 같은 폴더 안의 다른 사진과 이름이 겹치지 않게
        val pickedIds = picked.map { it.id }.toSet()
        val taken = HashSet<String>()
        allPhotos.filter { it.id !in pickedIds }.forEach {
            taken.add((it.relPath + "/" + it.name).lowercase(Locale.ROOT))
        }

        val prefix = prefixEdit.text.toString()
        val start = startEdit.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val randomNum = numGroup.index() == 1
        val usedNums = HashSet<Int>()

        val list = ArrayList<Job>()
        ordered.forEachIndexed { k, p ->
            val ext = formatExt(extOf(p.name))
            val base = if (uuidMode) {
                val u = UUID.randomUUID().toString()
                if (caseGroup.index() == 0) u.uppercase(Locale.ROOT) else u.lowercase(Locale.ROOT)
            } else if (randomNum) {
                var n: Int
                do {
                    n = Random.nextInt(10000)
                } while (n in usedNums && usedNums.size < 10000)
                usedNums.add(n)
                prefix + n.toString().padStart(4, '0')
            } else {
                prefix + (start + k).toString().padStart(4, '0')
            }
            var name = base + ext
            var dup = 1
            while ((p.relPath + "/" + name).lowercase(Locale.ROOT) in taken) {
                name = base + "_" + dup + ext
                dup++
            }
            taken.add((p.relPath + "/" + name).lowercase(Locale.ROOT))
            list.add(Job(p.id, p.uri, p.name, name))
        }
        jobs = list

        if (jobs.isEmpty()) {
            previewTitle.text = "고른 사진이 없습니다."
            previewText.text = ""
        } else {
            previewTitle.text = "사진 ${jobs.size}장"
            val head = jobs.take(150).joinToString("\n") { "${it.oldName}\n  → ${it.newName}" }
            previewText.text = if (jobs.size > 150) "$head\n…외 ${jobs.size - 150}장" else head
        }
        rerollButton.visibility = if ((uuidMode || randomNum) && jobs.isNotEmpty()) View.VISIBLE else View.GONE
        runButton.isEnabled = jobs.isNotEmpty() && !busy
    }

    // ---------- 실행 ----------

    private fun confirmRun() {
        if (jobs.isEmpty() || busy) return
        val todo = jobs
        AlertDialog.Builder(this)
            .setTitle("이름 바꾸기")
            .setMessage(
                "사진 ${todo.size}장의 파일 이름을 바꿉니다.\n" +
                    "다음에 뜨는 창에서 '허용'을 눌러 주세요.\n" +
                    "'마지막 작업 되돌리기'로 원래 이름으로 돌릴 수 있습니다."
            )
            .setPositiveButton("실행") { _, _ ->
                withWriteAccess(todo.map { it.uri }) { runJobs(todo, saveUndo = true) }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun withWriteAccess(uris: List<Uri>, action: () -> Unit) {
        try {
            val pi = MediaStore.createWriteRequest(contentResolver, uris)
            pendingWrite = action
            startIntentSenderForResult(pi.intentSender, reqWrite, null, 0, 0, 0)
        } catch (e: Exception) {
            pendingWrite = null
            status("수정 권한을 요청하지 못했습니다: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqWrite) return
        val action = pendingWrite
        pendingWrite = null
        if (resultCode == RESULT_OK && action != null) action() else status("허용하지 않아서 취소했습니다.")
    }

    private fun nameOf(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun setName(uri: Uri, name: String): Boolean = try {
        val cv = ContentValues()
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        contentResolver.update(uri, cv, null, null) > 0
    } catch (_: Exception) {
        false
    }

    private fun runJobs(todo: List<Job>, saveUndo: Boolean) {
        busy = true
        runButton.isEnabled = false
        undoButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.max = todo.size * 2
        progress.progress = 0
        status("이름을 바꾸는 중…")

        thread {
            val failed = ArrayList<String>()
            val undo = ArrayList<String>() // id \t 원래 이름
            var step = 0

            // 1단계: 서로 이름이 겹치지 않도록 임시 이름으로
            val staged = ArrayList<Job>()
            for (j in todo) {
                val ext = extOf(j.oldName)
                val tmp = "rn_tmp_" + UUID.randomUUID().toString().replace("-", "") +
                    (if (ext.isEmpty()) "" else ".$ext")
                if (setName(j.uri, tmp)) staged.add(j) else failed.add(j.oldName)
                step++
                val s = step
                ui.post { progress.progress = s }
            }

            // 2단계: 최종 이름으로
            for (j in staged) {
                if (setName(j.uri, j.newName)) {
                    undo.add("${j.id}\t${j.oldName}")
                } else {
                    setName(j.uri, j.oldName) // 실패하면 원래 이름으로 복구
                    failed.add(j.oldName)
                }
                step++
                val s = step
                ui.post { progress.progress = s }
            }

            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE).edit()
            if (saveUndo && undo.isNotEmpty()) prefs.putString("undo", undo.joinToString("\n"))
            else if (!saveUndo) prefs.remove("undo")
            prefs.apply()

            ui.post {
                val ok = todo.size - failed.size
                val head = if (saveUndo) "${ok}장의 이름을 바꿨습니다." else "${ok}장을 원래 이름으로 되돌렸습니다."
                val msg = if (failed.isEmpty()) {
                    head
                } else {
                    "$head\n바꾸지 못한 사진 ${failed.size}장:\n" + failed.take(20).joinToString("\n")
                }
                loadPhotos {
                    busy = false
                    progress.visibility = View.GONE
                    rebuild()
                    refreshUndo()
                    status(msg)
                }
            }
        }
    }

    // ---------- 되돌리기 ----------

    private fun readUndo(): List<Pair<Long, String>> {
        val text = getSharedPreferences(prefsName, MODE_PRIVATE).getString("undo", null) ?: return emptyList()
        return text.split("\n").mapNotNull {
            val parts = it.split("\t")
            val id = parts.getOrNull(0)?.toLongOrNull()
            if (parts.size == 2 && id != null) id to parts[1] else null
        }
    }

    private fun refreshUndo() {
        if (!::undoButton.isInitialized) return
        undoButton.visibility = if (readUndo().isNotEmpty()) View.VISIBLE else View.GONE
        undoButton.isEnabled = !busy
    }

    private fun confirmUndo() {
        val pairs = readUndo()
        if (pairs.isEmpty() || busy) return
        AlertDialog.Builder(this)
            .setTitle("되돌리기")
            .setMessage("마지막으로 바꾼 사진 ${pairs.size}장을 원래 이름으로 되돌립니다.")
            .setPositiveButton("되돌리기") { _, _ ->
                val coll = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val todo = pairs.mapNotNull { (id, orig) ->
                    val uri = ContentUris.withAppendedId(coll, id)
                    val cur = nameOf(uri)
                    if (cur == null) null else Job(id, uri, cur, orig)
                }
                if (todo.isEmpty()) {
                    status("되돌릴 사진을 찾지 못했습니다.")
                } else {
                    withWriteAccess(todo.map { it.uri }) { runJobs(todo, saveUndo = false) }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
