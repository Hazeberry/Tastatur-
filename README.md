# Hazeberry Keyboard

Eine kleine Android-Tastatur, die **in langen KI-Chats** (Claude, ChatGPT, Mistral, ...)
automatisch eine Signatur vor deine Nachricht setzt, zum Beispiel Ziel, Regeln und aktuellen
Stand eines Projekts. So verliert das Modell den Faden nicht.

Alles läuft lokal auf dem Handy. Es gibt keinen Server, keine Datenbank und keine
Internet-Berechtigung. Über die Taste 📁 kannst du außerdem den Text von Dateien (PDF, Word,
Excel, ZIP ...) direkt in den Chat einfügen.

## Funktionen

- QWERTZ-Tastatur mit Umlauten, Shift/Caps-Lock, automatischer Großschreibung am Satzanfang,
  Zahlen- und Symbolseiten (inkl. `[]{}<>|\~^$` für YAML und Code) und Emoji-Seite
- Signatur nach **N gesendeten Nachrichten**, nach **N Minuten** oder **per Tasker**
- Die Signatur wird der nächsten Nachricht **vorangestellt**, sobald du das erste Zeichen tippst
- Statuszeile über der Tastatur ("Noch 3 Nachricht(en) bis zur Signatur")
- Taste **Sig** zum manuellen Einfügen
- Taste **📁** öffnet deine Dateien und fügt ihren Text ein: Textdateien, **PDF**, **Word**,
  **Excel**, **PowerPoint**, **LibreOffice**, **Google Docs** und **ZIP-Archive**, auch mehrere
  auf einmal
- Passwortfelder werden nie gezählt und nie mit einer Signatur versehen
- Hell/Dunkel folgt dem System

## APK mit GitHub Actions bauen

1. Neues Repository auf GitHub anlegen und den Inhalt dieses Ordners hochladen (auf `main`).
2. Im Repository auf **Actions** gehen. Der Workflow **Build APK** läuft bei jedem Push.
   Manuell starten: Actions > Build APK > Run workflow.
3. Nach dem Lauf unter **Artifacts** die Datei `hazeberry-keyboard-debug-apk` herunterladen
   und entpacken.
4. Optional: Einen Tag `v1.0.0` pushen, dann hängt der Workflow die APK an ein GitHub-Release.

Lokal geht es mit Android Studio (Ordner öffnen) oder mit `gradle assembleDebug`
(Gradle 8.7, JDK 17). Tests: `gradle testDebugUnitTest`.

## Installieren und einrichten

1. APK auf dem Handy öffnen und die Installation aus dieser Quelle erlauben.
2. **Hazeberry Keyboard** öffnen. Dort **Tastatur aktivieren** und danach **Tastatur auswählen**.
3. Signatur eintragen (Button "Beispiel einfügen" zeigt ein Beispiel für eine GitHub Action),
   Intervall festlegen, **Speichern**.
4. In der KI-App tippen. Über das Symbol 🌐 in der Tastatur wechselst du zurück zu deiner
   normalen Tastatur.

## Dateien (📁)

1. Ins Eingabefeld tippen, damit die Tastatur erscheint, dann auf **📁** tippen.
2. Im Android-Dateidialog eine oder mehrere Dateien wählen (lange drücken für Mehrfachauswahl).
   Der Dialog zeigt alle Ordner des Handys, Downloads und installierte Cloud-Anbieter
   (Google Drive, Nextcloud, OneDrive ...).
3. Bei einem ZIP-Archiv erscheint eine Liste der enthaltenen Dateien. Alle sind angehakt,
   abwählen, was nicht rein soll ("Alle an/aus" hilft), dann **Einfügen**.
4. Du kommst zurück in die App, der Text wird eingefügt. Ist die Signatur fällig, steht sie davor.

Was gelesen wird:

| Format | Was herauskommt |
| --- | --- |
| Textdateien (`.txt`, `.md`, `.yml`, `.json`, `.csv`, Code, ...) | Inhalt 1:1. UTF-8, UTF-16 und alte Windows-Dateien (Umlaute) werden erkannt |
| PDF | Der Text aller Seiten |
| Word `.docx` | Absätze, Tabellen mit Tabulator zwischen den Zellen |
| Excel `.xlsx` | Jedes Blatt mit Namen, Zeilen mit Tabulator zwischen den Zellen (Werte, keine Formeln) |
| PowerPoint `.pptx` | Text jeder Folie, mit "Folie N" davor |
| LibreOffice `.odt`, `.ods`, `.odp` | Wie bei Word, Excel und PowerPoint |
| Google Docs, Tabellen, Präsentationen (aus Drive) | Werden von Drive als Text bzw. Office-Datei exportiert und dann gelesen |
| ZIP | Alle obigen Formate darin, auswählbar, jede Datei mit `[Datei: pfad]` davor |

Bei mehreren Dateien steht vor jeder `[Datei: name]`. Bei einer einzelnen Datei lässt sich das in
den Einstellungen abschalten.

Was **nicht** geht, und warum:

- **Dateien als Anhang** in den Chat legen (so dass die KI das Original-PDF bekommt). Eine
  Tastatur kann Apps nur Text und, wenn die App das ausdrücklich erlaubt, Bilder übergeben.
  KI-Apps erlauben das für PDFs und ZIPs nicht. Dafür bleibt der Weg über "Teilen" im
  Dateimanager oder die Büroklammer der App.
- **Eingescannte PDFs** (Seiten sind nur Bilder). Dafür wäre Texterkennung (OCR) nötig. Du
  bekommst eine Meldung.
- **Passwortgeschützte PDFs** (zum Öffnen braucht man ein Passwort). PDFs, die nur gegen
  Drucken oder Ändern geschützt sind, gehen.
- **Alte Office-Formate** `.doc`, `.xls`, `.ppt` sowie RTF. Bitte als `.docx`/`.xlsx`/`.pptx`,
  `.odt` oder PDF speichern.
- **Andere Archive** wie RAR, 7z, tar.gz. Nur ZIP. Verschlüsselte ZIPs gehen nicht, ZIPs in ZIPs
  werden nicht geöffnet.
- Bilder, Audio und Video (kein Text).

Grenzen und Schutz:

- **Keine Speicher-Berechtigung.** Die App sieht nur die Dateien, die du antippst, und nur in
  diesem Moment. Gelesen wird komplett auf dem Handy. Der Text liegt nur kurz im
  Arbeitsspeicher, Zwischendateien werden sofort gelöscht.
- Höchstens **100.000 Zeichen** insgesamt (etwa 40 Buchseiten). Was darüber hinausgeht, wird
  abgeschnitten, und du siehst, welche Dateien betroffen sind.
- Dateien über 100 MB werden nicht gelesen. ZIPs werden gegen "ZIP-Bomben" abgesichert
  (höchstens 40 MB je entpackter Datei, 200 MB insgesamt, 2000 Einträge).
- Eingefügt wird nur in **die App, aus der du 📁 angetippt hast**, nie in ein Passwortfeld.
  Wartet der Text länger als 3 Minuten, verfällt er.
- Erscheint die Tastatur nach der Auswahl nicht von selbst, einmal ins Eingabefeld tippen.
- Die APK ist rund 10 MB groß, vor allem wegen der PDF-Unterstützung (PDFBox).

## Tasker

Aktion **Intent senden**, Ziel **Broadcast-Empfänger**, Paket `de.hazeberry.keyboard`.

| Aktion | Wirkung |
| --- | --- |
| `de.hazeberry.keyboard.ACTION_SIGNATURE_DUE` | Signatur beim nächsten Nachrichtenbeginn einfügen |
| `de.hazeberry.keyboard.ACTION_RESET` | Zähler und Flag zurücksetzen |
| `de.hazeberry.keyboard.ACTION_SET_SIGNATURE` | Signaturtext ersetzen, Extras `token:<Token>` und `text:<Text>` |

Das Ändern der Signatur funktioniert nur mit dem Token aus den Einstellungen. Ohne Token ist es
gesperrt, damit keine fremde App Text in deine Chats einschleusen kann. Die App muss einmal
geöffnet worden sein, sonst stellt Android keine Broadcasts zu.

## Grenzen

- **Die Nachrichtenzählung ist eine Näherung.** Die Tastatur sieht den Senden-Button der App
  nicht. Eine Nachricht zählt, wenn ein gefülltes Eingabefeld leer wird (und du es nicht selbst
  mit der Löschtaste geleert hast). Das passt zu den üblichen Chat-Apps, kann aber bei
  Ausschneiden des ganzen Textes falsch zählen. Teste es einmal pro App.
- Die Datei-Taste fügt nur **Text** ein, siehe oben unter "Dateien".
- Wortvorschläge, Swipe-Eingabe und Sprachen außer Deutsch (QWERTZ) gibt es nicht.
- Die gebaute APK ist eine Debug-APK (mit Debug-Schlüssel signiert). Zum Verteilen über einen
  Store bräuchtest du einen eigenen Release-Schlüssel.

## Aufbau

```
app/src/main/java/de/hazeberry/keyboard/
  KeyboardService.kt    Tastatur, Zählung, Einfügen
  SignatureStore.kt     Einstellungen, Zähler, Fälligkeitslogik
  TaskerReceiver.kt     Tasker-Schnittstelle
  SettingsActivity.kt   Einrichtung und Einstellungen
  FilePickerActivity.kt Dateidialog, ZIP-Auswahl, Meldungen
  DocumentReader.kt     Text aus Text-, Office-, OpenDocument- und ZIP-Dateien
  PdfText.kt            Text aus PDFs (PDFBox)
  FileBridge.kt         Übergabe des Texts an die Tastatur
app/src/test/           Unit-Tests für alle Formate (gradle testDebugUnitTest)
.github/workflows/build.yml
```
