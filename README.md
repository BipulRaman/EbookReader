# EbookReader

A small native Android ebook reader (Kotlin) built for comfortable, Kindle-style reading of **EPUB**, **PDF** and **TXT** books — real pages instead of scrolling, beautiful typography, a warm night light and screen brightness right under your thumb.

| Library | Reading |
|---|---|
| "Continue reading" card for the last book, then a cover grid with format badges and progress bars; search, sort (recent / title / author / added) and import | Full-screen page with a quiet header (chapter) and footer (page / time left / %); tap the middle for the menu: Contents, Display, Light, search and bookmark |

---

## Reading experience

- **Real pages.** Books are laid out into screen-sized pages (CSS multi-column) and turned by tapping the left / right edges, swiping (the page follows your finger), or the **volume keys**. Turning past a chapter's end slides straight into the next one.
- **Typography.** Ships with **Literata** (the font designed for Google Play Books), plus serif, sans-serif and the book's own font. Font size, line spacing, margins, justified or left-aligned text, hyphenation, and a two-page spread in landscape. Centred / right-aligned elements (titles, poems, signatures) keep the book's alignment.
- **Themes.** White, Sepia, Green and Black page colours. The menus, panels and dialogs follow the page theme; PDFs are re-tinted to match (dark mode inverts them sensibly).
- **Screen light.**
  - **Brightness** slider (or follow the system), going *below* the system minimum for reading in bed by dimming the page itself.
  - **Slide up / down along the left edge** of the page to change brightness without opening a menu.
  - **Warm light** that tints everything — page, menus and panels — from neutral to amber, like a Kindle's adjustable warmth.
- **Highlights and notes.** Long-press to select text, then **Highlight** (4 colours) or **Note**; tap a highlight to recolour it, edit its note, copy, share or delete it. Copy, Share, Translate and dictionary apps stay in the selection toolbar too.
- **Bookmarks.** Tap the top-right corner of a page (a ribbon appears) or use the bookmark button.
- **Contents panel** with three tabs: **Contents** (chapters with their % position, the current chapter marked), **Bookmarks** and **Notes**, each with a snippet of the text.
- **Search** the whole book; results show the chapter and a snippet, and the match is highlighted on the page.
- **Back to where you were.** After jumping (contents, search, a link, the progress slider) a "‹ Back to 34%" pill returns you to your reading position.
- **Time left.** Tap the footer to cycle *page in chapter* → *time left in chapter* → *time left in book*. The estimate learns your reading speed from how long you spend on each page.
- **Images** fit the page; double-tap one to view it full screen with pinch-zoom.
- **Your place is never lost:** position is saved continuously and survives rotation, font changes and restarts.
- **Keep screen on** while reading (optional), immersive full-screen pages, and edge-to-edge layout around display cutouts.

### Formats

| Format | Notes |
|---|---|
| **EPUB** 2 / 3 | Cover, title and author from the package; the table of contents from the EPUB 3 nav or EPUB 2 NCX. Book scripts are removed. |
| **PDF** | Rendered with Android's `PdfRenderer`: swipe or tap to turn pages, double-tap or pinch to zoom (sharp re-render when zoomed), themes tint the page. No text search, highlights or contents for PDFs. |
| **TXT** | Converted once on import into a paginated book: encoding detected (UTF-8, UTF-16 with BOM, else Windows-1252), title and author read from a Project Gutenberg header, chapters ("CHAPTER I.", "Part Two", "Preface"…) detected for the contents, `_italics_` rendered, and a typographic cover generated. |

Books are added with **+** (system file picker, several at once), or opened from other apps (Files, Downloads, a mail attachment → *Open with* / *Share* → EbookReader). Each book is copied into the app, so the original file can be deleted; importing the same file twice is detected.

---

## Stack

- **Kotlin** on the Android SDK, plus only `androidx.recyclerview` — no Compose, AppCompat or start-up initializers
- **minSdk 26** (Android 8.0), **targetSdk 37** (Android 17), edge-to-edge, predictive back
- Reflowable books are rendered in a locked-down `WebView` driven by [reader.js](app/src/main/assets/reader/reader.js); PDFs by `PdfRenderer`
- Android Gradle Plugin 9.4 (built-in Kotlin), Gradle 9.8 wrapper, JDK 17+
- Tests: JUnit + Robolectric

---

## Project layout

Everything lives inside [app/](app/) — it is a single-module Gradle project. The repo root only holds repo-wide metadata.

```
EbookReader/                               # repo root: metadata only
├─ README.md, LICENSE, CODE_OF_CONDUCT.md, .gitignore, .gitattributes
├─ .github/workflows/
│  ├─ build-and-release.yml                # builds the signed APK when a GitHub Release is published
│  └─ setup-keystore.yml                   # one-time: prints keystore for repo secret
└─ app/                                    # the Android project — run all Gradle commands here
   ├─ settings.gradle.kts, build.gradle.kts, gradle.properties, proguard-rules.pro
   ├─ gradlew, gradlew.bat, gradle/wrapper/
   └─ src/
      ├─ main/
      │  ├─ AndroidManifest.xml
      │  ├─ assets/reader/
      │  │  ├─ reader.js                   # pagination, page turns, gestures, highlights, search hits
      │  │  └─ fonts/                      # Literata (SIL Open Font License, see OFL.txt)
      │  ├─ java/in/bipul/ebookreader/
      │  │  ├─ MainActivity.kt             # library: continue card, cover grid, search, sort, import
      │  │  ├─ book/
      │  │  │  ├─ Book.kt                  # common book model: spine, table of contents, resources
      │  │  │  ├─ Epub.kt                  # EPUB 2/3: container, OPF, nav / NCX, cover
      │  │  │  ├─ TxtBook.kt               # TXT → paginated XHTML chapters + detected contents
      │  │  │  ├─ HtmlText.kt, TocIndex.kt # chapter text, search, chapter positions within files
      │  │  │  └─ Xml.kt, HtmlEntities.kt  # tolerant XML/XHTML parsing
      │  │  ├─ library/
      │  │  │  ├─ Library.kt               # the book list + reading positions (library.json)
      │  │  │  ├─ Importer.kt              # copies, de-duplicates and indexes imported books
      │  │  │  ├─ Annotations.kt           # highlights, notes and bookmarks per book
      │  │  │  ├─ Covers.kt                # cover extraction, caching and generated covers
      │  │  │  └─ LibraryAdapter.kt        # library grid
      │  │  ├─ reader/
      │  │  │  ├─ ReaderActivity.kt        # reading screen: menus, sheets, panels, light, bookmarks
      │  │  │  ├─ Engine.kt                # interface shared by both renderers
      │  │  │  ├─ WebEngine.kt             # EPUB/TXT: chapter loading, JS bridge, chapter transitions
      │  │  │  ├─ BookServer.kt            # serves book files to the WebView offline (no internet permission)
      │  │  │  ├─ ReaderWebView.kt         # WebView settings + selection toolbar actions
      │  │  │  ├─ ReaderSettings.kt        # themes, fonts, layout settings → reader CSS
      │  │  │  ├─ PdfEngine.kt             # PDF pages, zoom, tinting
      │  │  │  ├─ ScreenFilterView.kt      # warm light + extra dimming overlay; image viewer
      │  │  │  └─ RowAdapter.kt            # contents / bookmarks / notes / search lists
      │  │  └─ ui/                         # insets & system bars, cover frame, predictive back
      │  └─ res/
      │     ├─ drawable/                   # vector icons + launcher layers
      │     ├─ mipmap-anydpi/ic_launcher.xml  # adaptive launcher icon (+ themed monochrome layer)
      │     └─ layout/, values*/, xml/     # screens, colors, strings, themes, backup rules
      └─ test/java/in/bipul/ebookreader/   # JVM + Robolectric tests (mirrors the packages above)
```

---

## Run locally

Requires **JDK 17+** and the **Android SDK** (Android Studio installs both). Open the `app/` folder in Android Studio and press Run, or:

```powershell
cd app
.\gradlew installDebug          # build + install on a connected device/emulator
.\gradlew testDebugUnitTest     # unit + Robolectric tests
```

---

## Releasing an APK

APKs are built **only when a GitHub Release is published**. Pushes and merges to `main` never build or publish anything.

### Updates

The app never checks for updates itself. Release APKs are signed with a permanent key, and each release has a higher `versionCode`, so a user with EbookReader already installed just installs the newer APK and Android updates the app in place, keeping their books, positions and highlights.

That only holds while the key stays the same. It lives in the repository secret `DEBUG_KEYSTORE_BASE64` (alias `androiddebugkey`, password `android`), and a release build fails if the secret is missing or the APK ends up signed with any other key. **Never delete or replace that secret**: an APK signed with a different key can't update existing installs — people would have to uninstall first and lose their library.

### One-time setup — generate a stable signing keystore

1. Push this repo to GitHub.
2. Go to **Actions → "Setup signing keystore (run once)" → Run workflow**.
3. Open the finished run and find the **"Print setup instructions"** step. It prints a base64 block between `BEGIN` / `END` markers.
4. Go to **Settings → Secrets and variables → Actions → New repository secret**:
   - **Name:** `DEBUG_KEYSTORE_BASE64`
   - **Value:** the base64 block from step 3 (no surrounding whitespace)
5. Save, keep a private backup of the keystore, and delete the workflow run (its log contains the key).

The keystore is **never committed to the repo** — only in the secret.

### Publish a release

1. **Releases → Draft a new release**, create a tag with a semantic version such as `v1.2.3` (or `1.2.3`) on the commit you want to ship, and **Publish**.
2. [.github/workflows/build-and-release.yml](.github/workflows/build-and-release.yml) runs the tests and Android Lint, builds the minified release APK, signs it, and attaches it to that release as `EbookReader-v1.2.3.apk` (usually within a few minutes).

The tag version is stamped into the APK: `versionName` = `1.2.3`, `versionCode` = `10203` (major × 10000 + minor × 100 + patch), so every newer version installs as an update over older ones. If the tag isn't in that format, or minor/patch reach 100, the release build fails.

**Install it:** download the APK from the release, copy it to your phone and tap to install (allow "Install unknown apps" once). If EbookReader is already installed, this updates it in place and keeps your library.

### Build locally

```powershell
cd app
.\gradlew assembleRelease
```

The APK (about 1.5 MB, most of it the Literata fonts) lands at `app/build/outputs/apk/release/EbookReader-release.apk`. Without extra configuration it is signed with your local debug key. To sign with the stable keystore instead, set `EBOOKREADER_KEYSTORE` to the keystore path (and, if they differ from the Android defaults, `EBOOKREADER_KEYSTORE_PASSWORD`, `EBOOKREADER_KEY_ALIAS`, `EBOOKREADER_KEY_PASSWORD`). Pass `-PversionCode=<n> -PversionName=<x.y.z>` to set the version.

---

## Before you publish — checklist

- `applicationId` in [app/build.gradle.kts](app/build.gradle.kts) (`in.bipul.ebookreader`) — **must be globally unique** for the Play Store. Changing it makes it a different app: existing installs won't upgrade in place.
- App name: `app_name` in [app/src/main/res/values/strings.xml](app/src/main/res/values/strings.xml).
- Launcher icon: vector layers `ic_launcher_background.xml` / `ic_launcher_foreground.xml` / `ic_launcher_monochrome.xml` in `app/src/main/res/drawable/`.

---

## Notes & limitations

- **Android only** (8.0+).
- **No permissions at all** — not even internet. Books arrive through the system file picker or *Open with* / *Share*, so the app only sees the files you choose, and nothing in a book can reach the network: book pages are served from inside the app, their scripts are removed and a Content-Security-Policy blocks everything else. Links in a book to websites open in your browser after asking.
- **Your library stays on the phone.** Books, covers, positions, highlights and notes live in the app's private storage and are excluded from cloud backups (books can be large, and may not be yours to upload).
- **DRM-protected books** (Kindle, Adobe ADEPT, Kobo, Apple) can't be opened. Embedded fonts that publishers obfuscate fall back to the reader's fonts.
- **Fixed-layout EPUBs** (comics, picture books) are reflowed like ordinary books, which may not look as designed. Right-to-left page progression (manga, Arabic, Hebrew) isn't supported yet.
- **PDFs** have no text search, highlights or table of contents, and very large scanned PDFs render page by page.

### Credits

[Literata](https://github.com/googlefonts/literata) by TypeTogether for Google, under the SIL Open Font License 1.1 ([OFL.txt](app/src/main/assets/reader/fonts/OFL.txt)). Sample public-domain books used in testing come from [Project Gutenberg](https://www.gutenberg.org/).

---

## License

MIT — do whatever you want.
