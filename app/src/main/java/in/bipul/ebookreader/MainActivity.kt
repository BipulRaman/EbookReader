package `in`.bipul.ebookreader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import `in`.bipul.ebookreader.book.BookException
import `in`.bipul.ebookreader.library.BookEntry
import `in`.bipul.ebookreader.library.Covers
import `in`.bipul.ebookreader.library.Importer
import `in`.bipul.ebookreader.library.Library
import `in`.bipul.ebookreader.library.LibraryAdapter
import `in`.bipul.ebookreader.reader.ReaderActivity
import `in`.bipul.ebookreader.ui.BackHandler
import `in`.bipul.ebookreader.ui.setUpEdgeToEdge
import java.util.Locale
import java.util.concurrent.Executors

/** The library: every book as a cover in a grid, with "Continue reading" on top. */
class MainActivity : Activity() {
    private lateinit var library: Library
    private lateinit var adapter: LibraryAdapter
    private lateinit var grid: RecyclerView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var searchBar: View
    private lateinit var searchField: EditText
    private lateinit var empty: View
    private lateinit var importing: View
    private lateinit var importingText: TextView

    private val main = Handler(Looper.getMainLooper())
    private val importer = Executors.newSingleThreadExecutor()
    private var pendingImports = 0
    private val onLibraryChanged: () -> Unit = { main.post { refresh() } }
    private val back = BackHandler(this, { searchBar.visibility == View.VISIBLE }) { showSearch(false) }

    private val prefs by lazy { getSharedPreferences("library", Context.MODE_PRIVATE) }
    private var sort: Int
        get() = prefs.getInt("sort", SORT_RECENT)
        set(value) = prefs.edit().putInt("sort", value).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.setUpEdgeToEdge(findViewById(R.id.root))
        library = Library.get(this)

        grid = findViewById(R.id.books)
        searchBar = findViewById(R.id.search_bar)
        searchField = findViewById(R.id.search_field)
        empty = findViewById(R.id.empty)
        importing = findViewById(R.id.importing)
        importingText = findViewById(R.id.importing_text)

        adapter = LibraryAdapter(library, ::open, ::showOptions)
        layoutManager = GridLayoutManager(this, spanCount())
        layoutManager.spanSizeLookup = adapter.spanLookup { layoutManager.spanCount }
        grid.layoutManager = layoutManager
        grid.adapter = adapter

        findViewById<View>(R.id.add).setOnClickListener { pickBooks() }
        findViewById<View>(R.id.empty_add).setOnClickListener { pickBooks() }
        findViewById<View>(R.id.search).setOnClickListener { showSearch(true) }
        findViewById<View>(R.id.search_close).setOnClickListener { showSearch(false) }
        findViewById<View>(R.id.more).setOnClickListener(::showMenu)
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })

        library.addListener(onLibraryChanged)
        refresh()
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        library.removeListener(onLibraryChanged)
        importer.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutManager.spanCount = spanCount()
    }

    private fun spanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 120).coerceIn(3, 8)
    }

    @SuppressLint("GestureBackNavigation") // API 33+ registers an OnBackInvokedCallback instead.
    @Deprecated("Only called below API 33; newer versions use OnBackInvokedCallback.")
    override fun onBackPressed() {
        if (!back.handle()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun refresh() {
        val query = searchField.text.toString().trim().lowercase(Locale.getDefault())
        val all = library.all()
        val sorted = when (sort) {
            SORT_TITLE -> all.sortedBy { it.title.lowercase(Locale.getDefault()) }
            SORT_AUTHOR -> all.sortedWith(compareBy({ it.author.ifEmpty { "\uFFFF" }.lowercase(Locale.getDefault()) }, { it.title.lowercase(Locale.getDefault()) }))
            SORT_ADDED -> all.sortedByDescending { it.addedAt }
            else -> all.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
        }
        val searching = searchBar.visibility == View.VISIBLE && query.isNotEmpty()
        val shown = if (searching) {
            sorted.filter { it.title.lowercase(Locale.getDefault()).contains(query) || it.author.lowercase(Locale.getDefault()).contains(query) }
        } else {
            sorted
        }
        val continueReading = if (searching) null else all.filter { it.lastOpenedAt > 0 && !it.finished }.maxByOrNull { it.lastOpenedAt }
        val header = when {
            searching -> resources.getQuantityString(R.plurals.results, shown.size, shown.size)
            all.isEmpty() -> null
            else -> resources.getQuantityString(R.plurals.all_books, all.size, all.size)
        }
        adapter.submit(shown, continueReading, header)
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showSearch(show: Boolean) {
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) {
            searchBar.visibility = View.VISIBLE
            searchField.requestFocus()
            imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        } else {
            searchField.setText("")
            imm.hideSoftInputFromWindow(searchField.windowToken, 0)
            searchBar.visibility = View.GONE
            refresh()
        }
        back.update()
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val sorts = listOf(SORT_RECENT to R.string.sort_recent, SORT_TITLE to R.string.sort_title, SORT_AUTHOR to R.string.sort_author, SORT_ADDED to R.string.sort_added)
        val sub = menu.menu.addSubMenu(R.string.sort_by)
        sorts.forEach { (id, label) -> sub.add(1, id, 0, label).isChecked = id == sort }
        sub.setGroupCheckable(1, true, true)
        menu.menu.add(0, MENU_ABOUT, 1, R.string.about)
        menu.setOnMenuItemClickListener { item ->
            when {
                item.groupId == 1 -> { sort = item.itemId; refresh(); grid.scrollToPosition(0) }
                item.itemId == MENU_ABOUT -> showAbout()
            }
            true
        }
        menu.show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_text, BuildConfig.VERSION_NAME))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun open(book: BookEntry) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id))
    }

    private fun showOptions(book: BookEntry, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.open)
        menu.menu.add(0, 2, 1, R.string.book_details)
        menu.menu.add(0, 3, 2, if (book.finished) R.string.mark_unread else R.string.mark_finished)
        menu.menu.add(0, 4, 3, R.string.remove_from_library)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> open(book)
                2 -> showDetails(book)
                3 -> library.update(book.id) {
                    if (it.finished) it.copy(finished = false, progress = 0f, position = null, lastOpenedAt = 0) else it.copy(finished = true, progress = 1f)
                }
                4 -> confirmRemove(book)
            }
            true
        }
        menu.show()
    }

    private fun showDetails(book: BookEntry) {
        val lines = buildList {
            if (book.author.isNotEmpty()) add(getString(R.string.detail_author, book.author))
            add(getString(R.string.detail_format, book.format.name))
            add(getString(R.string.detail_size, Formatter.formatShortFileSize(this@MainActivity, book.fileSize)))
            add(getString(R.string.detail_added, DateFormat.getMediumDateFormat(this@MainActivity).format(book.addedAt)))
            if (book.lastOpenedAt > 0) add(getString(R.string.detail_progress, (book.progress * 100).toInt()))
        }
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmRemove(book: BookEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_title)
            .setMessage(getString(R.string.remove_message, book.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                Covers.evict(library.coverFile(book.id))
                library.remove(book.id)
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun pickBooks() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            // Some file providers label EPUBs as octet-stream; the importer checks the content anyway.
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/pdf", "text/plain", "application/octet-stream"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(intent, REQUEST_PICK)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) }
            if (isEmpty()) data.data?.let(::add)
        }
        importAll(uris, openSingle = uris.size == 1)
    }

    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) importAll(listOf(uri), openSingle = true)
    }

    private fun importAll(uris: List<Uri>, openSingle: Boolean) {
        if (uris.isEmpty()) return
        pendingImports += uris.size
        updateImporting()
        for (uri in uris) {
            importer.execute {
                val result = runCatching { Importer.import(this, uri) }
                main.post {
                    pendingImports--
                    updateImporting()
                    if (isDestroyed) return@post
                    result.onSuccess { r ->
                        if (openSingle) {
                            open(r.entry)
                        } else if (r.alreadyInLibrary) {
                            Toast.makeText(this, getString(R.string.already_in_library, r.entry.title), Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure { e ->
                        val name = Importer.displayName(this, uri) ?: getString(R.string.this_file)
                        val reason = (e as? BookException)?.message ?: getString(R.string.import_failed_generic)
                        Toast.makeText(this, "$name: $reason", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updateImporting() {
        importing.visibility = if (pendingImports > 0) View.VISIBLE else View.GONE
        importingText.text = resources.getQuantityString(R.plurals.adding_books, pendingImports, pendingImports)
    }

    companion object {
        private const val REQUEST_PICK = 1
        private const val MENU_ABOUT = 100
        const val SORT_RECENT = 10
        const val SORT_TITLE = 11
        const val SORT_AUTHOR = 12
        const val SORT_ADDED = 13
    }
}
