# herbers-android-filesearch

*(English · [Deutsch](README.de.md))*

An **on-device, fully offline full-text search engine** for Android: it indexes
your files' **names and contents** (PDF, EPUB, MOBI/AZW3, CBZ, DOCX/XLSX/PPTX,
ODT/ODS/ODP, old Office, TXT/MD/…, and optionally inside ZIP/7z/TAR archives) into a local SQLite **FTS4** index and searches them fast —
no cloud, no network, no Gradle. Factored out of the app
**[Sucher](https://github.com/8818freak/Sucher)**.

> Search *inside* your documents and e-books, on the device, offline. The engine
> handles the hard parts: metadata-first indexing so everything is findable
> quickly, a watchdog that survives files which hang a format library, and
> concurrent search while indexing runs.

## What's inside

| Class | Purpose |
|---|---|
| `SearchStore` | The index + query API. SQLite with an FTS4 full-text table plus a metadata table (name/type/size/dates/title/author/series). `search()`, `advancedSearch()` (name/author/title/series/extensions/date ranges), notification capture (`addNotification`/`searchNotifications`), `pruneStale`, backup by copying the DB file. WAL enabled, so a search on the main thread isn't blocked by the indexer's writes. |
| `SearchIndexer` | Background walker/indexer. **Two-phase**: phase 1 writes fast metadata for *every* file (so name search works immediately, even in huge media folders), phase 2 extracts full text/title/author, phase 3 pre-warms cover thumbnails in small batches. Cooperative stop, a **watchdog** that hard-kills a runaway (CPU-bound format-library loop) after a stall and remembers the offending file so the next run skips it, and guards against directory-alias loops. |
| `SearchConfig` | Small interface the host app implements (which folders, which are content-enabled, the skip-list, a comics-metadata flag). Keeps the engine independent of how the app stores settings. |

## Usage

```java
// 1) Implement SearchConfig (e.g. backed by SharedPreferences):
SearchConfig cfg = new SearchConfig() {
    public java.util.Collection<String> searchFolders() { return prefsFolders(); }
    public boolean contentIndexingEnabled(String folder) { return prefs.getBoolean("content:" + folder, false); }
    public boolean searchComicsMeta() { return true; }
    public java.util.Set<String> skipContentPaths() { return prefsSkip(); }
    public void addSkipContent(String path) { prefsAddSkip(path); }   // must persist immediately
    public void onRunFinished(long startedAtMillis) { /* optional: store a timestamp */ }
};

// 2) Index in the background (e.g. from a JobService or a foreground service):
SearchIndexer.start(context, cfg, () -> { /* onDone */ });

// 3) Query (do this OFF the main thread — a common word can match a lot):
for (SearchStore.FileHit h : SearchStore.get(context).search("query", -1, null, null)) {
    // h.path, h.name, h.title, h.author, h.snippet, ...
}
```

PDF needs a one-time `PdfExtractorHelper.init(context)` (from docextract) before
content extraction; the indexer calls it for you.

## Dependencies

Two sibling libraries, added here as Git submodules:

- **[herbers-android-common](https://github.com/8818freak/herbers-android-common)** – `DiagLog`/`Diagnostics` (diagnostic log + hang/crash stack capture).
- **[herbers-android-docextract](https://github.com/8818freak/herbers-android-docextract)** – the format extractors and cover/thumbnail generation (bundles Apache POI, PDFBox-Android etc. under `docextract/libs/`).

FTS4 itself is built into Android's SQLite — no extra dependency.

## Building it in

Intended as a **source module** (raw Android SDK build, no Gradle):

```sh
git submodule update --init                 # pulls common/ and docextract/
# compile: src/ + common/src/ + docextract/src/ , with docextract/libs/*.jar on
# the classpath; enable multidex (POI/PDFBox are large). Unpack the jars into the
# obj dir before d8 and delete module-info.class / META-INF/versions (see Sucher).
```

Requirements: Android 10+ (API 29), Java 8 language features. Needs
`READ`/`MANAGE_EXTERNAL_STORAGE` at the app level to walk the user's folders.

## License

**GNU Lesser General Public License v3.0 (or later)** – see `LICENSE`. Linkable
from non-GPL apps; changes to the library itself stay copyleft. Copyright © 2026
Mathias Herbers.
