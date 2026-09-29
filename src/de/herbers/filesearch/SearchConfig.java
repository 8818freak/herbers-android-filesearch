package de.herbers.filesearch;

import java.util.Collection;
import java.util.Set;

/**
 * App-Konfiguration fuer den {@link SearchIndexer} - so bleibt die Bibliothek
 * unabhaengig davon, WIE und WO die App ihre Einstellungen speichert. Eine App
 * implementiert das (z.B. gestuetzt auf SharedPreferences) und reicht es an
 * {@link SearchIndexer#start} herein.
 *
 * <p>English: configuration the host app provides to the indexer, so the
 * library stays independent of how the app stores its settings.
 */
public interface SearchConfig {

    /** Wurzelordner, die durchsucht/indiziert werden sollen (absolute Pfade). */
    Collection<String> searchFolders();

    /** Ob fuer Dateien unter diesem Wurzelordner auch der VOLLTEXT erfasst wird
     *  (teuer). false = nur Metadaten (Name/Typ/Groesse/Datum/Titel/Autor). */
    boolean contentIndexingEnabled(String folder);

    /** Ob aus Comics (CBZ) Metadaten (ComicInfo.xml) gelesen werden sollen. */
    boolean searchComicsMeta();

    /** Pfade, die einen frueheren Lauf zum Haengen brachten - fuer sie wird kein
     *  Inhalt/Titelbild mehr versucht (nur Namens-/Metadaten-Eintrag). */
    Set<String> skipContentPaths();

    /** Einen Pfad dauerhaft zur Skip-Liste hinzufuegen (der Watchdog ruft das
     *  auf, wenn eine Datei den Lauf haengen liess). Muss den Kill des Prozesses
     *  ueberleben (also sofort persistieren). */
    void addSkipContent(String path);

    /** Wird am Ende eines Laufs aufgerufen (Zeitpunkt des Laufbeginns) - z.B. um
     *  einen "zuletzt indiziert"-Zeitstempel zu speichern. Optional nutzbar. */
    void onRunFinished(long startedAtMillis);
}
