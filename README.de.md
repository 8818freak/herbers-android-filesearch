# herbers-android-filesearch

*([English](README.md) · Deutsch)*

Eine **Volltext-Such-Engine für das Gerät, komplett offline**: Sie indiziert
**Namen UND Inhalte** deiner Dateien (PDF, EPUB, MOBI/AZW3, CBZ, DOCX/XLSX/PPTX,
altes Office, TXT/MD/…) in einen lokalen SQLite-**FTS4**-Index und durchsucht sie
schnell – ohne Cloud, ohne Netzwerk, ohne Gradle. Herausgelöst aus der App
**[Sucher](https://github.com/8818freak/Sucher)**.

> *In* Dokumenten und E-Books suchen, auf dem Gerät, offline. Die Engine nimmt
> einem die schweren Teile ab: Metadaten zuerst (alles sofort auffindbar), ein
> Watchdog gegen Dateien, die eine Format-Bibliothek aufhängen, und Suchen
> während die Indizierung läuft.

## Enthalten

| Klasse | Zweck |
|---|---|
| `SearchStore` | Index + Abfrage-API. SQLite mit einer FTS4-Volltexttabelle plus Metadaten-Tabelle (Name/Typ/Größe/Datum/Titel/Autor/Serie). `search()`, `advancedSearch()` (Name/Autor/Titel/Serie/Endungen/Zeiträume), Benachrichtigungs-Mitschnitt (`addNotification`/`searchNotifications`), `pruneStale`, Sicherung durch Kopieren der DB-Datei. WAL aktiv, damit eine Suche nicht von den Schreibvorgängen der Indizierung blockiert wird. |
| `SearchIndexer` | Hintergrund-Durchlauf/Indizierer. **Zwei-Phasen**: Phase 1 schreibt schnelle Metadaten für *jede* Datei (Namenssuche sofort, auch in großen Medienordnern), Phase 2 erfasst Volltext/Titel/Autor, Phase 3 wärmt Titelbilder in kleinen Tranchen vor. Kooperativer Stopp, ein **Watchdog**, der einen Amoklauf (CPU-Endlosschleife einer Format-Bibliothek) nach Stillstand hart beendet und die auslösende Datei merkt (nächster Lauf überspringt sie), plus Schutz gegen Ordner-Alias-Schleifen. |
| `SearchConfig` | Kleine Schnittstelle, die die App implementiert (welche Ordner, welche mit Inhalt, Skip-Liste, Comics-Metadaten-Schalter). Hält die Engine unabhängig davon, wie die App ihre Einstellungen speichert. |

## Nutzung

```java
// 1) SearchConfig implementieren (z. B. gestützt auf SharedPreferences):
SearchConfig cfg = new SearchConfig() {
    public java.util.Collection<String> searchFolders() { return prefsFolders(); }
    public boolean contentIndexingEnabled(String folder) { return prefs.getBoolean("content:" + folder, false); }
    public boolean searchComicsMeta() { return true; }
    public java.util.Set<String> skipContentPaths() { return prefsSkip(); }
    public void addSkipContent(String path) { prefsAddSkip(path); }   // sofort persistieren
    public void onRunFinished(long startedAtMillis) { /* optional: Zeitstempel merken */ }
};

// 2) Im Hintergrund indizieren (z. B. aus einem JobService/Vordergrunddienst):
SearchIndexer.start(context, cfg, () -> { /* onDone */ });

// 3) Suchen (NICHT auf dem Main-Thread – ein häufiges Wort trifft evtl. sehr viel):
for (SearchStore.FileHit h : SearchStore.get(context).search("query", -1, null, null)) {
    // h.path, h.name, h.title, h.author, h.snippet, ...
}
```

PDF braucht einmalig `PdfExtractorHelper.init(context)` (aus docextract) vor der
Inhaltsextraktion; der Indizierer ruft das selbst auf.

## Abhängigkeiten

Zwei Geschwister-Bibliotheken, hier als Git-Submodule eingebunden:

- **[herbers-android-common](https://github.com/8818freak/herbers-android-common)** – `DiagLog`/`Diagnostics` (Diagnose-Protokoll + Stack-Erfassung bei Hängern/Abstürzen).
- **[herbers-android-docextract](https://github.com/8818freak/herbers-android-docextract)** – die Format-Extraktoren und Titelbild-Erzeugung (bündelt Apache POI, PDFBox-Android usw. in `docextract/libs/`).

FTS4 selbst steckt in Androids SQLite – keine Zusatz-Abhängigkeit.

## Einbinden

Als **Quell-Modul** gedacht (roher Android-SDK-Build, kein Gradle):

```sh
git submodule update --init                 # holt common/ und docextract/
# kompilieren: src/ + common/src/ + docextract/src/ , mit docextract/libs/*.jar
# auf dem Classpath; multidex aktivieren (POI/PDFBox sind groß). Die JARs vor d8
# in den obj-Ordner entpacken und module-info.class / META-INF/versions löschen
# (siehe Sucher).
```

Voraussetzungen: Android 10+ (API 29), Java-8-Sprachfeatures. Braucht auf
App-Ebene `READ`/`MANAGE_EXTERNAL_STORAGE`, um die Ordner zu durchlaufen.

## Lizenz

**GNU Lesser General Public License v3.0 (oder später)** – siehe `LICENSE`. Auch
aus nicht-GPL-Apps einbindbar; Änderungen an der Bibliothek selbst bleiben
copyleft. Copyright © 2026 Mathias Herbers.
