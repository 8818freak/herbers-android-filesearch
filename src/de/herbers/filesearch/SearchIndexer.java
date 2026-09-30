package de.herbers.filesearch;

import de.herbers.docextract.*;

import de.herbers.common.DiagLog;
import de.herbers.common.Diagnostics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;

/**
 * Durchsucht die in den Einstellungen gewaehlten Ordner und fuellt den
 * SearchStore. Laeuft als einfacher Hintergrund-Thread (kein eigener
 * Dienst/keine WorkManager-Abhaengigkeit noetig - EdgeService haelt den
 * Prozess ohnehin als Vordergrunddienst am Leben, darin ist ein zusaetzlicher
 * Thread waehrend eines manuell angestossenen Durchlaufs unproblematisch).
 *
 * Jede Datei bekommt einen Metadaten-Eintrag (Name/Typ/Groesse/Datum/Titel/
 * Autor/Serie) - so funktioniert Namens-/Autor-/Serien-/Typ-/Datumssuche
 * fuer JEDE Datei, auch unbekannte Formate (dann eben ohne Titel/Autor/
 * Serie). Der eigentliche FLIESSTEXT (das teure an Zeit UND Speicher - siehe
 * Mathias' ~22000-Buecher-Bibliothek) wird nur fuer Ordner geholt, die
 * die SearchConfig extra freigibt (contentIndexingEnabled); ausserhalb bleibt es bei
 * Metadaten (mtime-Vergleich macht Folgedurchlaeufe trotzdem schnell).
 */
public final class SearchIndexer {

    private SearchIndexer() {}

    /** App-Konfiguration (Ordner, Inhalt-Freigabe, Skip-Liste ...). */
    private static SearchConfig cfg;

    private static volatile boolean running = false;
    private static volatile boolean stopRequested = false;
    static volatile int scanned = 0;
    static volatile int contentIndexed = 0;
    static volatile int thumbsMade = 0;
    // Aktuelle Phase: 1 = Metadaten, 2 = Inhalt, 3 = Titelbilder.
    static volatile int phase = 0;
    // In Phase 1 gesammelte Pfade, die in Phase 2 Inhalt/Titel/Autor brauchen.
    private static java.util.List<String> pendingExtract = new java.util.ArrayList<>();
    // In diesem Lauf angetroffene, aber unveraenderte Dateien (je Wurzelordner) -
    // werden vor pruneStale per touchIndexed als "gesehen" markiert, sonst
    // haelt pruneStale sie faelschlich fuer verwaist und loescht sie.
    private static java.util.List<String> seenUnchanged = new java.util.ArrayList<>();
    static volatile String currentPath = "";
    // Aktuell durchlaufener ORDNER (unabhaengig von der Datei). Wichtig fuers
    // Diagnose-Protokoll: bei einem Ordner-Amoklauf (Pfad-Alias-Ring) haengt
    // der Lauf in walk() und erreicht nie eine Datei - dann ist currentPath
    // leer, aber currentDir zeigt, WO es feststeckt.
    static volatile String currentDir = "";

    public static boolean isRunning() { return running; }

    /** Kooperativer Stopp fuer IndexJobService.onStopJob(): das System hat
     *  das Zeitfenster des Jobs beendet, aber der Scan lief bis vor diesem
     *  Fix als eigener, vom Job-Lebenszyklus abgekoppelter Thread einfach
     *  weiter - onStopJob() konnte ihn nicht wirklich anhalten. Damit blieb
     *  fuer die naechste Job-Ausfuehrung (deren Thread ja noch "running"
     *  war) nur ein sofortiger, folgenloser No-Op via start() uebrig, ohne
     *  je jobFinished() aufzurufen - das System wertete den Job darum
     *  wiederholt als haengengeblieben und brach ihn zwangsweise ab (24x
     *  laut dumpsys jobscheduler, cachten Ausfuehrungsstatistiken zufolge),
     *  was App-Prozess und -Job zunehmend drosselte. walk()/indexOne()
     *  pruefen dieses Flag jetzt zwischen Dateien und beenden sich zuegig,
     *  sodass der Job sich sauber (und rechtzeitig) als fertig meldet. */
    public static void requestStop() { stopRequested = true; }

    /** Groessenobergrenze fuer Inhaltsextraktion (Metadaten werden trotzdem
     *  immer gespeichert) - verhindert dass ein Mammut-Archiv/-Video die
     *  Indizierung tagelang blockiert. */
    private static final long MAX_CONTENT_BYTES = 60L * 1024 * 1024;

    // Von start() einmal pro Lauf gesetzt.
    private static boolean searchComicsMetaCached = true;
    private static List<String> contentRootsCached = java.util.Collections.emptyList();
    // Dateien, die einen frueheren Lauf zum Haengen brachten (siehe Watchdog
    // unten) - fuer die wird kein Inhalt/Titelbild mehr geholt, nur Metadaten.
    private static java.util.Set<String> skipContentCached = java.util.Collections.emptySet();

    // ---- Selbstheilung gegen den in Sucher-Uebergabe Abschnitt 3 beschriebenen
    // Haenger (Lauf blieb tagelang bei hoher CPU-Last stehen, Telefon wurde
    // warm, kein Stoppen-Knopf/Force-Stop half zuverlaessig). Ein CPU-gebundener
    // Endlos-Loop in einer Format-Bibliothek (PDFBox/POI/Mobi) reagiert NICHT
    // auf ein kooperatives Stopp-Flag oder Thread.interrupt() - nur das harte
    // Beenden des Prozesses stoppt ihn. Der Watchdog erkennt genau diesen Fall
    // (kein Fortschritt mehr) und beendet den Prozess, nachdem er sich die
    // ausloesende Datei gemerkt hat, damit der naechste Lauf sie ueberspringt. --
    private static final long WATCHDOG_INTERVAL_MS = 30_000L;
    private static final long STALL_MS = 5L * 60 * 1000; // 5 Min ohne Fortschritt = haengt

    public static void start(Context ctx, SearchConfig config, Runnable onDone) {
        if (running) return;
        running = true;
        stopRequested = false;
        cfg = config;
        scanned = 0; contentIndexed = 0; thumbsMade = 0; phase = 0; currentPath = "";
        Context app = ctx.getApplicationContext();
        contextApp = app;
        searchComicsMetaCached = cfg.searchComicsMeta();
        skipContentCached = cfg.skipContentPaths();
        Handler main = new Handler(Looper.getMainLooper());
        Thread worker = new Thread(() -> {
            try {
                PdfExtractorHelper.init(app);
                SearchStore store = SearchStore.get(app);
                long runStart = System.currentTimeMillis();
                DiagLog.log(app, "Indizierlauf gestartet"
                        + (skipContentCached.isEmpty() ? "" : " (" + skipContentCached.size()
                        + " Datei(en) auf der Überspringen-Liste)"));
                visitedCanonical.clear();
                // Nicht lesbare Wurzelordner auf den zugänglichen internen
                // Speicher abbilden: "/storage/emulated" (bzw. "/storage")
                // selbst kann eine App nicht auflisten - gemeint ist praktisch
                // immer "/storage/emulated/0". So funktioniert eine bestehende
                // (im mageren Ordner-Picker gewählte) Einstellung von selbst,
                // und der vorhandene Index wird per mtime wiederverwendet.
                java.util.Collection<String> stored = cfg.searchFolders();
                java.util.List<String> folders = new java.util.ArrayList<>();
                contentRootsCached = new java.util.ArrayList<>();
                for (String cf : stored) {
                    String norm = normalizeRoot(cf);
                    folders.add(norm);
                    if (cfg.contentIndexingEnabled(cf)) contentRootsCached.add(norm);
                }
                // ---- PHASE 1: Metadaten fuer ALLE Dateien (schnell) ----
                // Nur Name/Typ/Groesse/Datum je Datei - keine (teure) Inhalts-
                // oder Titelbild-Erfassung. So ist JEDE Datei sofort per
                // Namenssuche auffindbar, auch die in grossen Medien-Ordnern
                // (Fotos/Musik/...). Frueher blieb ein Lauf in der teuren
                // Inhaltsextraktion des grossen Buecher-Ordners haengen und
                // erreichte die Medien-Ordner nie (Mathias: "keine gefundene
                // Datei liegt im photos-Pfad"). Inhalt (Phase 2) und Titelbilder
                // (Phase 3) folgen danach in Tranchen (Mathias' Vorschlag).
                phase = 1;
                pendingExtract = new java.util.ArrayList<>();
                for (String root : folders) {
                    if (stopRequested) break;
                    File rootDir = new File(root);
                    // WICHTIG: Nur laufen/aufraeumen, wenn der Wurzelordner
                    // tatsaechlich lesbar ist (siehe Kommentar normalizeRoot):
                    // ein nicht auflistbarer Ordner liefert listFiles()==null;
                    // dann NICHT pruneStale (wuerde den Index faelschlich leeren).
                    if (!rootDir.isDirectory() || rootDir.listFiles() == null) {
                        DiagLog.log(app, "Wurzelordner »" + root + "« ist nicht lesbar bzw. kein "
                                + "Verzeichnis – übersprungen, Index NICHT bereinigt. Bitte in den "
                                + "Einstellungen einen zugänglichen Ordner wählen (z. B. /storage/emulated/0 "
                                + "statt /storage/emulated).");
                        continue;
                    }
                    seenUnchanged = new java.util.ArrayList<>();
                    walk(store, rootDir, 0);
                    if (!stopRequested) {
                        // Unveraenderte Dateien als "in diesem Lauf gesehen"
                        // markieren (indexed_at >= runStart), damit pruneStale
                        // sie NICHT als verwaist loescht. Ohne das leerte jeder
                        // Lauf den halben Index und baute ihn neu auf - und das
                        // FTS-Massenloeschen brachte den Lauf zum Haengen.
                        store.touchIndexed(seenUnchanged, runStart);
                        // Vor dem Aufraeumen den zuletzt gelaufenen Dateipfad
                        // loeschen: sonst wuerde ein Haenger in pruneStale
                        // faelschlich die letzte gewalkte Datei als
                        // "problematisch" markieren (currentPath wird in
                        // pruneStale nicht aktualisiert). currentDir bleibt der
                        // Wurzelordner - der ist dann der sinnvolle Verursacher.
                        currentPath = "";
                        store.pruneStale(root, runStart);
                    }
                }
                DiagLog.log(app, "Phase 1 (Metadaten) " + (stopRequested ? "gestoppt" : "fertig")
                        + ": " + scanned + " Dateien erfasst, " + pendingExtract.size()
                        + " für Inhalt/Metadaten vorgemerkt");

                // ---- PHASE 2: Inhalt/Titel/Autor in Tranchen ----
                extractPass(app, store);

                // ---- PHASE 3: Titelbilder in Tranchen ----
                // (Titelbilder entstehen zwar auch bei Bedarf beim Anzeigen -
                //  hier werden die zuletzt geaenderten Dateien vorab in kleinen
                //  Mengen je Lauf erfasst, damit sie beim Blaettern sofort da
                //  sind. Bewusst gedeckelt, damit das Telefon nicht warm wird.)
                thumbPass(app, store);

                if (!stopRequested) cfg.onRunFinished(System.currentTimeMillis());
                DiagLog.log(app, (stopRequested ? "Indizierlauf gestoppt" : "Indizierlauf fertig")
                        + ": " + scanned + " geprüft, " + contentIndexed + " mit neuem Volltext, "
                        + thumbsMade + " neue Titelbilder");
            } catch (Throwable t) {
                android.util.Log.w("EdgeTabSearch", "Indizierlauf abgebrochen", t);
                StackTraceElement[] st = t.getStackTrace();
                String where = (st != null && st.length > 0) ? " @ " + st[0] : "";
                DiagLog.log(app, "Indizierlauf mit Fehler abgebrochen: " + t + where
                        + " (zuletzt: Ordner »" + currentDir + "«"
                        + (currentPath == null || currentPath.isEmpty() ? "" : ", Datei »" + currentPath + "«") + ")");
            } finally {
                running = false;
                if (onDone != null) main.post(onDone);
            }
        }, "EdgeTabSearchIndexer");
        worker.start();
        startWatchdog(app, worker);
    }

    /** Beobachtet den laufenden Indizierer und beendet den Prozess hart, wenn
     *  er ueber {@link #STALL_MS} keinen Fortschritt mehr macht (weder eine
     *  weitere Datei noch einen weiteren Ordner) - der klassische Fall eines
     *  Endlos-Loops in einer Format-Bibliothek, den ein kooperatives Stoppen
     *  nicht erreicht. Die ausloesende Datei wird vorher gemerkt und kuenftig
     *  uebersprungen. */
    private static void startWatchdog(Context app, Thread worker) {
        Thread wd = new Thread(() -> {
            long lastAdvance = System.currentTimeMillis();
            int lastScanned = scanned;
            int lastWalk = walkCallCounter;
            while (running) {
                try { Thread.sleep(WATCHDOG_INTERVAL_MS); } catch (InterruptedException e) { return; }
                if (!running) return;
                // Diagnose-Herzschlag: alle 30 s festhalten, WO der Lauf gerade
                // ist (Ordner + Datei + Zaehler). So zeigt der Log auch dann
                // Pfade, wenn keine Einzeldatei einen Fehler wirft - und man
                // sieht an steigender Ordnerzahl bei stehendem "geprüft" sofort
                // einen Ordner-Amoklauf (Pfad-Alias-Ring) statt nur Wärme.
                try {
                    DiagLog.log(app, "läuft: " + scanned + " geprüft, " + visitedCanonical.size()
                            + " Ordner besucht; gerade: Ordner »" + currentDir + "«"
                            + (currentPath == null || currentPath.isEmpty() ? "" : ", Datei »" + currentPath + "«"));
                } catch (Throwable ignored) {}
                if (scanned != lastScanned || walkCallCounter != lastWalk) {
                    lastScanned = scanned;
                    lastWalk = walkCallCounter;
                    lastAdvance = System.currentTimeMillis();
                    continue;
                }
                // Kein Fortschritt seit dem letzten Herzschlag -> Stack des
                // Worker-Threads festhalten (zeigt die genaue haengende Stelle,
                // z.B. Files.readAttributes vs. SQLite). Geht auch nach logcat.
                logWorkerStack(app, worker, "kein Fortschritt seit letztem Herzschlag");
                if (System.currentTimeMillis() - lastAdvance >= STALL_MS) {
                    // Bei einem Ordner-Hänger ist currentPath leer -> dann den
                    // Ordner als Verursacher merken/melden.
                    String stuck = (currentPath != null && !currentPath.isEmpty()) ? currentPath : currentDir;
                    android.util.Log.w("EdgeTabSearch", "Indizierer haengt seit "
                            + (STALL_MS / 1000) + "s ohne Fortschritt bei: " + stuck
                            + " - wird kuenftig uebersprungen, Prozess wird beendet.");
                    try {
                        DiagLog.log(app, "HÄNGER erkannt: kein Fortschritt seit "
                                + (STALL_MS / 1000) + "s bei »" + stuck + "« (Ordner »" + currentDir
                                + "«). Wird künftig übersprungen, Prozess wird jetzt beendet.");
                    } catch (Throwable ignored) {}
                    if (stuck != null && !stuck.isEmpty()) {
                        try { cfg.addSkipContent(stuck); } catch (Throwable ignored) {}
                    }
                    stopRequested = true;
                    worker.interrupt();
                    Process.killProcess(Process.myPid());
                    return;
                }
            }
        }, "EdgeTabSearchWatchdog");
        wd.setDaemon(true);
        wd.start();
    }

    /** Haelt den aktuellen Stack des Indizierer-Threads im Diagnose-Protokoll
     *  fest (und damit auch in logcat). Zeigt bei einem Haenger die exakte
     *  blockierende Stelle - ohne Debug-Build/Root. */
    private static void logWorkerStack(Context app, Thread worker, String warum) {
        if (worker == null) return;
        try {
            DiagLog.log(app, "Indizierer-Stack (" + warum + "):"
                    + Diagnostics.stackOf(worker, 18));
        } catch (Throwable ignored) {}
    }

    // Kanonische Pfade bereits besuchter Ordner - Android haengt an mehreren
    // Stellen Verknuepfungen ein, die auf denselben echten Ort zeigen (z.B.
    // /storage/self/primary -> /storage/emulated/0). Ohne diese Bremse wuerde
    // ein an /storage gewurzelter Durchlauf jede Datei doppelt erfassen (im
    // schlimmsten Fall, je nach ROM, sogar in einer echten Schleife haengen).
    private static final java.util.Set<String> visitedCanonical = new java.util.HashSet<>();

    // Harte Grenze GEGEN den Fall, dass getCanonicalPath() zwei verschiedene
    // Pfade zum selben echten Ort NICHT auf denselben String abbildet (auf
    // Androids FUSE-Speicher beobachtet: /storage/emulated/0 vs.
    // /storage/self/primary vs. /sdcard koennen je nach ROM uneinheitlich
    // aufgeloest werden) - dann greift visitedCanonical NICHT, und ein
    // Alias-Ring wuerde sonst unbemerkt endlos rekursieren (beobachtet:
    // Sucher lief tagelang mit hoher CPU-Last fest, "gerade dran" zeigte nie
    // eine Datei - die Rekursion kam nie bis zu einer Datei durch). Jede real
    // sinnvolle Ordnerstruktur ist weit flacher als das.
    private static final int MAX_DEPTH = 40;

    // Zweite, breiten-orientierte Notbremse gegen einen Pfad-Alias-Ring, den
    // MAX_DEPTH nicht faengt: rekursiert die Explosion nicht in die Tiefe,
    // sondern immer wieder ueber neue (nicht deduplizierte) Aliaspfade in die
    // Breite, waechst visitedCanonical unbegrenzt und der Lauf macht scheinbar
    // ewig "Fortschritt" (der Watchdog greift dann nicht). Jede real sinnvolle
    // Ordnerstruktur - auch eine sehr grosse Buchsammlung - hat weit weniger
    // Ordner als diese Grenze; ein Alias-Ring erreicht sie in Sekunden.
    private static final int MAX_DIRS = 300_000;

    // Zaehlt walk()-Aufrufe. Dient dem Watchdog als Fortschrittssignal (siehe
    // startWatchdog): solange der Zaehler steigt, geht der Verzeichnis-Durchlauf
    // noch voran, auch wenn die Datei-Zahl "scanned" gerade stillsteht - deshalb
    // bleibt der Zaehler. Das frueher hier haengende periodische Diagnose-Logging
    // ist entfernt, die Ursache (Pfad-Alias-Ring) ist per MAX_DEPTH/MAX_DIRS
    // fest abgesichert.
    private static int walkCallCounter = 0;

    /** Einen nicht auflistbaren Wurzelordner auf den zugänglichen internen
     *  Speicher abbilden. "/storage/emulated" und "/storage" sind für eine App
     *  nicht listbar; gemeint ist der primäre geteilte Speicher, den
     *  {@link android.os.Environment#getExternalStorageDirectory()} liefert
     *  (typisch "/storage/emulated/0"). Lesbare Ordner bleiben unverändert. */
    private static String normalizeRoot(String path) {
        try {
            File f = new File(path);
            if (f.isDirectory() && f.listFiles() != null) return path; // lesbar -> so lassen
            if ("/storage/emulated".equals(path) || "/storage".equals(path) || "/sdcard".equals(path)) {
                File ext = android.os.Environment.getExternalStorageDirectory();
                if (ext != null && ext.isDirectory() && ext.listFiles() != null) {
                    android.util.Log.i("SucherDiag", "Wurzelordner »" + path + "« nicht lesbar -> "
                            + "verwende stattdessen »" + ext.getPath() + "«.");
                    return ext.getPath();
                }
            }
        } catch (Throwable ignored) {}
        return path;
    }

    private static void walk(SearchStore store, File dir, int depth) {
        ++walkCallCounter;   // Fortschrittssignal fuer den Watchdog (siehe startWatchdog)
        if (depth > MAX_DEPTH) {
            android.util.Log.w("EdgeTabSearch", "Abbruch: Ordner zu tief verschachtelt (moeglicher Pfad-Alias-Ring): " + dir);
            return;
        }
        if (visitedCanonical.size() >= MAX_DIRS) {
            android.util.Log.w("EdgeTabSearch", "Abbruch: zu viele Ordner besucht ("
                    + visitedCanonical.size() + ", moeglicher Pfad-Alias-Ring) - Lauf wird gestoppt.");
            DiagLog.log(contextApp, "Abbruch: unplausibel viele Ordner besucht ("
                    + visitedCanonical.size() + ", möglicher Pfad-Alias-Ring) bei »" + dir + "«.");
            stopRequested = true; // beendet die restliche Rekursion sauber
            return;
        }
        currentDir = dir.getPath();
        String canon;
        try { canon = dir.getCanonicalPath(); } catch (Exception e) { canon = dir.getAbsolutePath(); }
        if (!visitedCanonical.add(canon)) return;

        File[] children = dir.listFiles();
        if (children == null) return;
        if (children.length > 5000) {
            android.util.Log.d("EdgeTabSearchDiag", "grosser Ordner: " + children.length + " Eintraege in " + dir);
        }
        for (File f : children) {
            if (stopRequested) return;
            if (f.isDirectory()) {
                if (f.isHidden() || f.getName().startsWith(".")) continue; // .thumbnails, .trash etc.
                walk(store, f, depth + 1);
            } else {
                indexMeta(store, f);
            }
        }
    }

    private static boolean contentWanted(String path) {
        for (String root : contentRootsCached) {
            if (path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/")) return true;
        }
        return false;
    }

    /** PHASE 1 je Datei: nur schnelle Metadaten (Name/Typ/Groesse/Datum), keine
     *  Inhalts-/Titel-/Titelbild-Erfassung. Unveraenderte Dateien werden
     *  uebersprungen; Dateien, die noch Inhalt/Titel brauchen, fuer Phase 2
     *  vorgemerkt. Aenderung wird an mtime ODER Groesse erkannt (eine ersetzte
     *  Datei behaelt manchmal die alte Aenderungszeit - dann verraet die andere
     *  Groesse die Aenderung; Mathias' Fall mit einem ausgetauschten Buch). */
    private static void indexMeta(SearchStore store, File f) {
        try {
            currentPath = f.getPath();
            long mtime = f.lastModified();
            long size = f.length();
            String name = f.getName();
            String ext = extOf(name);
            boolean poisoned = skipContentCached.contains(f.getPath());
            // Fuer welche Dateien soll ueberhaupt Inhalt/Titel extrahiert werden
            // (Phase 2)? Nur unterstuetzte Formate; Comics nur wenn gewuenscht;
            // vergiftete Dateien nie (die brachten einen frueheren Lauf zum
            // Haengen - fuer sie bleibt es beim reinen Namens-Eintrag).
            boolean comic = "cbz".equals(ext) || "cbr".equals(ext);
            boolean wantExtract = !poisoned && isSupported(ext) && (!comic || searchComicsMetaCached);
            boolean wantContent = wantExtract && size <= MAX_CONTENT_BYTES && contentWanted(f.getPath());
            SearchStore.KnownState known = store.known(f.getPath());
            boolean unchanged = known.mtime == mtime && known.size == size;
            if (unchanged) {
                // Unveraendert. Metadaten stehen schon. Als "in diesem Lauf
                // gesehen" merken, damit pruneStale sie nicht loescht.
                seenUnchanged.add(f.getPath());
                // Nur wenn noch Inhalt gewuenscht ist und fehlt -> fuer Phase 2.
                if (wantContent && !known.contentOk) pendingExtract.add(f.getPath());
                scanned++;
                return;
            }
            // Neu oder geaendert -> schnelle Metadaten schreiben (loescht einen
            // evtl. veralteten Volltext) und, falls unterstuetzt, fuer die
            // Inhalts-/Titel-Erfassung in Phase 2 vormerken.
            long created = readCreated(f, mtime);
            // Alten Volltext nur loeschen, wenn die Datei vorher welchen hatte
            // (sonst teurer FTS-Komplettscan, siehe SearchStore.upsertMeta).
            store.upsertMeta(f.getPath(), name, ext, size, mtime, created, known.contentOk);
            if (wantExtract) pendingExtract.add(f.getPath());
            scanned++;
        } catch (Throwable t) {
            android.util.Log.w("EdgeTabSearch", "Datei uebersprungen: " + f.getPath(), t);
            DiagLog.log(contextApp, "Datei übersprungen (Fehler beim Verarbeiten): »"
                    + f.getPath() + "« - " + t);
        }
    }

    /** PHASE 2: fuer die in Phase 1 vorgemerkten Dateien Inhalt/Titel/Autor
     *  erfassen. Laeuft NACH der schnellen Metadaten-Phase, sodass alle Dateien
     *  (auch die in grossen Medien-Ordnern) bereits per Namenssuche gefunden
     *  werden, egal wie lange diese teure Phase dauert. Kooperativer Stopp
     *  zwischen den Dateien; der Watchdog sieht Fortschritt an scanned. */
    private static void extractPass(Context app, SearchStore store) {
        if (stopRequested || pendingExtract == null) return;
        phase = 2;
        DiagLog.log(app, "Phase 2 (Inhalt): " + pendingExtract.size() + " Datei(en)");
        for (String path : pendingExtract) {
            if (stopRequested) return;
            try {
                File f = new File(path);
                if (!f.isFile()) continue;
                if (skipContentCached.contains(path)) continue;
                String ext = extOf(f.getName());
                long size = f.length();
                boolean wantContent = size <= MAX_CONTENT_BYTES && contentWanted(path);
                currentPath = path;
                currentDir = f.getParent() == null ? "" : f.getParent();
                android.util.Log.i("SucherDiag", "extrahiere" + (wantContent ? " Inhalt" : " Metadaten")
                        + " [" + ext + ", " + size + "B]: " + path);
                FileExtractors.Result r = FileExtractors.extract(f, ext, wantContent);
                if (r.text != null && !r.text.isEmpty()) contentIndexed++;
                long mtime = f.lastModified();
                long created = readCreated(f, mtime);
                // Phase 1 hat für diese Datei bereits content_ok=0 gesetzt und
                // einen evtl. alten Volltext entfernt -> hier NICHT erneut den
                // teuren FTS-Löschscan ausführen.
                store.upsert(path, f.getName(), ext, size, mtime, created,
                        r.text, r.drm, r.title, r.author, r.series, r.seriesIndex, false);
                scanned++;
            } catch (Throwable t) {
                android.util.Log.w("EdgeTabSearch", "Inhalt uebersprungen: " + path, t);
                DiagLog.log(app, "Inhalt übersprungen (Fehler): »" + path + "« - " + t);
            }
        }
    }

    // Wie viele Kandidaten je Lauf hoechstens auf ein fehlendes Titelbild
    // geprueft werden, und wie viele je Lauf hoechstens NEU erzeugt werden -
    // bewusst gedeckelt (Mathias' Wunsch "in Tranchen"), damit das Telefon nicht
    // warm wird. Der Rest kommt in den folgenden Laeufen bzw. bei Bedarf beim
    // Anzeigen dran.
    private static final int THUMB_SCAN_CAP = 4000;
    private static final int THUMB_BATCH = 150;

    /** PHASE 3: fuer die zuletzt geaenderten titelbildfaehigen Dateien Vorschau-
     *  bilder vorab erzeugen, in kleinen Tranchen (siehe THUMB_BATCH). Nur was
     *  noch keins hat. Titelbilder entstehen sonst ohnehin bei Bedarf beim
     *  Anzeigen - das hier ist nur ein sanftes Vorwaermen. */
    private static void thumbPass(Context app, SearchStore store) {
        if (stopRequested) return;
        phase = 3;
        try {
            java.util.List<String[]> cand = store.thumbCandidates(THUMB_SCAN_CAP);
            int made = 0, checked = 0;
            for (String[] pe : cand) {
                if (stopRequested || made >= THUMB_BATCH) break;
                String path = pe[0], ext = pe[1];
                if (skipContentCached.contains(path)) continue;
                if (!Thumbnails.canHaveThumb(ext)) continue;
                if (Thumbnails.exists(app, path)) continue;
                File f = new File(path);
                if (!f.isFile()) continue;
                currentPath = path;
                checked++;
                if (Thumbnails.ensure(app, f, ext)) { made++; thumbsMade++; }
            }
            DiagLog.log(app, "Phase 3 (Titelbilder): " + made + " neu erzeugt (" + checked + " geprüft)");
        } catch (Throwable t) {
            android.util.Log.w("EdgeTabSearch", "Titelbild-Phase abgebrochen", t);
        }
    }

    // Von start() gesetzt - fuer Kontext-abhaengige Aufrufe.
    private static Context contextApp;

    /** Erstellungsdatum ("Geburt") ueber NIO, wo der Kernel/Dateisystem es
     *  liefert (ext4-btime-Unterstuetzung variiert) - sonst Ruecksturz auf
     *  die Aenderungszeit, damit das Feld nie 0/unplausibel bleibt. */
    private static long readCreated(File f, long fallback) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
            long t = attrs.creationTime().toMillis();
            return t > 0 ? t : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    // Auch von SearchStore.contentEligibleCount() genutzt (Fortschritts-
    // Prozentanzeige) - eine einzige Quelle statt einer zweiten, leicht
    // auseinanderlaufenden Kopie dieser Liste.
    static final String[] SUPPORTED_EXTS = {
            "txt", "md", "markdown", "csv", "log", "json", "xml", "srt", "ini", "yaml", "yml",
            "docx", "xlsx", "pptx", "doc", "xls", "ppt", "epub", "fb2",
            "mobi", "azw", "azw3", "prc", "pdf", "cbz", "cbr"
    };
    private static final java.util.Set<String> SUPPORTED_EXT_SET =
            new java.util.HashSet<>(java.util.Arrays.asList(SUPPORTED_EXTS));

    private static boolean isSupported(String ext) {
        return SUPPORTED_EXT_SET.contains(ext);
    }
}
