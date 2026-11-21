# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Projekt

Android-Tastatur (Kotlin, ein Modul `app`, Paket `de.hazeberry.keyboard`) für lange KI-Chats:
setzt automatisch eine "Signatur" (Ziel/Regeln/Stand) vor Nachrichten und kann über die Taste 📁
den Text von Dateien einfügen oder Dateien anhängen. Alles läuft offline auf dem Gerät.
README, Code-Kommentare und alle Texte für Nutzer sind **deutsch**.

## Befehle

Voraussetzungen: JDK 17, Gradle 8.7+ (kein Wrapper im Repo; CI nutzt 8.7), Android SDK 34.
Lokal braucht es `local.properties` mit `sdk.dir=...` (steht in `.gitignore`).

```bash
gradle assembleDebug                      # Debug-APK -> app/build/outputs/apk/debug/
gradle testDebugUnitTest                  # alle Tests (JVM + Robolectric)
gradle testDebugUnitTest --tests '*MoreFormatsTest*'               # eine Testklasse
gradle testDebugUnitTest --tests '*MoreFormatsTest.encrypted7z'    # ein Test
gradle lintDebug                          # Bericht: app/build/reports/lint-results-debug.txt
```

CI (`.github/workflows/build.yml`) führt bei jedem Push/PR erst die Tests, dann `assembleDebug`
aus; ein Tag `v*` hängt die APK an ein Release. Es gibt nur Debug-APKs (Debug-Schlüssel).
Robolectric lädt beim ersten Lauf das Android-Image (~150 MB) aus Maven Central.

## Architektur

### Tastatur und Signatur
- `KeyboardService` (InputMethodService): komplette UI programmatisch (keine XML-Layouts),
  Signatur-Logik, Erkennung "Nachricht gesendet" (Feld war gefüllt und ist jetzt leer, siehe
  `onUpdateSelection`). Alles Getippte läuft über `type()`, das bei leerem Feld eine fällige
  Signatur voranstellt; Passwortfelder (`isSensitiveField`) sind überall ausgenommen.
- `SignatureStore`: alle Einstellungen in SharedPreferences. `TaskerReceiver`: Broadcast-API,
  Signatur ändern nur mit Token. `SettingsActivity`: Einrichtung, UI ebenfalls programmatisch.

### Datei-Taste 📁 (Ablauf über mehrere Dateien)
1. `KeyboardService.openFilePicker` startet `FilePickerActivity` (durchsichtig, eigener Task) und
   gibt `packageName` und `contentMimeTypes` des aktuellen Eingabefelds als Extras mit.
2. `FilePickerActivity` öffnet das Storage Access Framework (keine Speicher-Berechtigung) und
   verarbeitet die Auswahl in **einem Hintergrund-Thread**. Rückfragen (Text oder Anhang,
   Archiv-Auswahl, Passwort) laufen über `ask { answer -> Dialog }`: zeigt den Dialog auf dem
   Main-Thread und blockiert den Worker per `CountDownLatch`. Abbruch -> `CancelledException`.
3. Der Text wird in `FilePickerActivity.deliver` zusammengesetzt (`FileCompose.build`: Abfrage,
   Trennlinie, `[Datei: name]`-Blöcke; Abfrage-Vorlagen kommen aus `SignatureStore.queryTemplates`,
   ein Textformat mit `## Name`-Zeilen). Standard ist `showPreview` (Abfrage wählen, Dateien
   abwählen, "Einfügen" oder "In Zwischenablage"); ohne Vorschau (`filePreview = false`) wird
   sofort mit `lastQuery` eingefügt. Erst "Einfügen" legt den Text in die `FileBridge`.
   Die Signatur kommt danach automatisch davor, weil das Einfügen über `type()` läuft.
4. Ergebnis geht an `FileBridge` (Singleton im selben Prozess, nur im Speicher, verfällt nach
   3 Minuten, gilt nur für das Ziel-Paket).
5. `KeyboardService.onStartInput` -> `insertPendingFile`: Text per `type()` (Signatur greift),
   Anhänge per `commitContent` nur, wenn das Feld den MIME-Typ annimmt.
   Alternativ "Teilen" direkt an die Ziel-App (`<queries>` im Manifest für die Sichtbarkeit).
6. Anhänge liegen als Kopie im Cache und werden über `AttachmentProvider` bereitgestellt
   (nicht exportiert, `grantUriPermissions`, Pfadprüfung, Aufräumen nach 24 h).

### Termux-Anbindung (Taste ▶, Kürzel `;;name`)
- `TermuxClient` startet `com.termux.app.RunCommandService` (Intent `com.termux.RUN_COMMAND`,
  immer `bash` mit Argumenten, stdin per `RUN_COMMAND_STDIN`, Hintergrundmodus) und bekommt das
  Ergebnis über einen **veränderbaren** PendingIntent an den nicht exportierten
  `TermuxResultReceiver` zurück (Bundle `result`: stdout, stderr, exitCode, err == -1 heißt kein
  interner Fehler). Callbacks laufen auf dem Main-Thread, genau einmal (auch bei Timeout).
- `TermuxLogic` ist Android-frei: Konvention `~/.tastatur/aktionen/<name>` (stdin -> stdout) und
  `~/.tastatur/kuerzel <kürzel>`, Namensprüfung, Kürzel-Erkennung, Fehlertexte und das
  Einrichtungsskript (`SETUP_SCRIPT`, ein Kotlin-Rohstring: `$` als `${'$'}` schreiben).
- `KeyboardService`: Ergebnis wird nur eingesetzt, wenn Paket, `fieldId` und Text unverändert sind
  (`TermuxTarget` + `canApply`), sonst Zwischenablage. Kürzel lösen nur aus, wenn sie in
  `typedBuffer` (von der Tastatur selbst getippt) stehen. Leere Ausgabe ersetzt nie Text.
  Passwortfelder sind gesperrt. Die Funktion ist standardmäßig aus (`SignatureStore.termuxEnabled`).
- Die Tastatur greift nie direkt auf Termux-Dateien oder Shared Storage zu (keine
  Speicher-Berechtigung); alles läuft über Befehle in Termux.

### Textextraktion
- `DocumentReader` ist **bewusst frei von Android-Code**, damit die JVM-Tests ohne Gerät laufen.
  Android-Abhängiges wird injiziert: `PdfEngine` (`PdfText`, PDFBox-Android), `OcrEngine`
  (`TesseractOcr`), `Progress` (Fortschritt/Abbruch). Das so lassen.
- `detect()` bestimmt die Dateiart aus Name/MIME **und** Magic Bytes (`sniff`); der Inhalt
  gewinnt, außer bei ZIP-basierten Formaten mit passender Endung. Dann `readFile` -> Dispatch.
- Ergebnis ist immer ein `Part` (Text oder `problem`, plus `needsPassword`/`wrongPassword`,
  `ocr`, `children` bei Archiven). Fehler werden als `ReadProblem` mit deutscher, direkt
  anzeigbarer Meldung geworfen; `PasswordRequired` löst in der Activity den Passwort-Dialog aus;
  `CancelledException` muss immer durchgereicht werden (nie in Generic-Catches schlucken).
- Konvention für Längen: Leser bekommen `maxChars + 1`; `finish()` kürzt und setzt `truncated`.
  Grenzen (100.000 Zeichen gesamt, 100 MB Datei, ZIP-Bomben-Schutz, Verschachtelungstiefe 2)
  stehen als Konstanten in `DocumentReader` bzw. `FilePickerActivity`.
- Formate verteilt auf: `LegacyOffice` (.doc/.xls/.ppt auf Basis von `Cfb`, dem OLE2-Leser;
  PowerPoint über das Persistenz-Verzeichnis, damit Schnellspeicher-Altstände nicht mitkommen),
  `ExcelFormats` (Datums-/Zahlenformat, Tabellenausgabe für .xls und .xlsx), `OfficeCrypto`
  (verschlüsselte .docx/.xlsx/.pptx, Agile + Standard, probiert zuerst das Office-Standardpasswort),
  `Archives` (ZIP via zip4j inkl. CP437-Namen und Passwort, 7z/tar via Commons Compress),
  `RtfText`, `HtmlText` (für EPUB und Outlook-HTML). OOXML/ODF/EPUB parst `DocumentReader` per SAX
  mit gesperrten externen Entitäten.

## Build-Besonderheiten (`app/build.gradle.kts`)
- Nur ARM-ABIs (`abiFilters`) und `jniLibs.useLegacyPackaging = true`, sonst wird die APK durch
  Tesseract ~40 MB statt ~17 MB. BouncyCastle-PQC-Ressourcen sind ausgeschlossen.
- JitPack ist in `settings.gradle.kts` nur für die Gruppe `cz.adaptech.tesseract4android` freigegeben.
- Tesseract-Sprachdaten liegen in `app/src/main/assets/tessdata/` (deu, eng, tessdata_fast) und
  werden zur Laufzeit nach `filesDir/tesseract/tessdata` kopiert (`DATA_VERSION` in `TesseractOcr`).
- Die App hat **keine INTERNET-Berechtigung**; das ist ein Kernversprechen (README) und soll so bleiben.

## Tests
- JVM-Tests: `DocumentReaderTest`, `MoreFormatsTest`, `PdfBoxTest`, `FileComposeTest`. Echte Beispieldateien in
  `app/src/test/resources/samples/` (Herkunft und Passwörter siehe README dort); alles andere
  (ZIP, 7z, tar, verschlüsselte ZIPs, RTF, EPUB) erzeugen die Tests selbst.
- PDFBox braucht außerhalb von Android seine Schriftdaten auf dem Klassenpfad: die Gradle-Aufgabe
  `pdfboxTestResources` entpackt sie aus dem AAR. Auf dem Gerät lädt `PdfText.init(context)` sie aus
  den Assets.
- Robolectric (`AndroidFlowTest`, SDK 34 per `robolectric.properties`): Activity-Abläufe mit
  `EXTRA_NO_PICKER` + `start(uris)`, Fake-OCR über `FilePickerActivity.ocrEngine`, Meldungen über
  `lastToast`/`lastSummary`; Dialoginhalte der Vorschau werden über View-Tags gefunden
  (`query:<Name>`, `file:<n>`, `preview`, `counter`, `notes`); Tastatur-Tests setzen `mStartedInputConnection`/`mInputEditorInfo`
  per Reflection und nutzen eine Proxy-`InputConnection`. Braucht `--add-opens java.base/java.io`
  (in `build.gradle.kts` gesetzt).
- Termux: `TermuxLogicTest` führt das echte `SETUP_SCRIPT` mit bash in einem Temp-Home aus und
  ruft die Beispielaktionen und Kürzel auf (wird ohne bash übersprungen). `TermuxAndroidTest`
  ersetzt Termux über `KeyboardService.testTermux` und simuliert das Eingabefeld mit einer
  Proxy-`InputConnection` (Text, Cursor, Markierung).
- Nicht automatisch testbar: Tesseract zur Laufzeit (native Bibliothek), echtes Verhalten
  fremder Apps bei `commitContent`/Teilen und das Zusammenspiel mit einem echten Termux.
- Achtung in Kotlin-Testquellen: `\uXXXX` wird auch in Rohstrings (`"""..."""`) als Unicode-Escape
  übersetzt. Für RTF-Steuerwörter wie `\u8364` einen Platzhalter ersetzen (siehe `MoreFormatsTest.rtf`).
