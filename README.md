# Hazeberry Keyboard

Eine kleine Android-Tastatur, die **in langen KI-Chats** (Claude, ChatGPT, Mistral, ...)
automatisch eine Signatur vor deine Nachricht setzt, zum Beispiel Ziel, Regeln und aktuellen
Stand eines Projekts. So verliert das Modell den Faden nicht.

Alles läuft lokal auf dem Handy. Es gibt keinen Server, keine Datenbank und keine
Internet-Berechtigung. Über die Taste 📁 kannst du außerdem den Text von Dateien (PDF, Word,
Excel, Bilder, ZIP ...) direkt in den Chat einfügen oder Dateien anhängen.

## Funktionen

- QWERTZ-Tastatur mit Umlauten, Shift/Caps-Lock, automatischer Großschreibung am Satzanfang,
  Zahlen- und Symbolseiten (inkl. `[]{}<>|\~^$` für YAML und Code) und Emoji-Seite
- Signatur nach **N gesendeten Nachrichten**, nach **N Minuten** oder **per Tasker**
- Die Signatur wird der nächsten Nachricht **vorangestellt**, sobald du das erste Zeichen tippst
- Statuszeile über der Tastatur ("Noch 3 Nachricht(en) bis zur Signatur")
- Taste **Sig** zum manuellen Einfügen
- Taste **📁** öffnet deine Dateien und fügt ihren Text ein: Textdateien, **PDF** (auch
  eingescannt), **Word/Excel/PowerPoint** (neu und alt, auch mit Passwort), **LibreOffice**,
  **RTF**, **E-Books**, **Outlook-Mails**, **Google Docs**, **Bilder** (Texterkennung) und
  **Archive** (ZIP, 7z, tar.gz ...), auch mehrere auf einmal. Wo die App es zulässt, auch als
  **Anhang**
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
3. Nimmt die App Dateien an (siehe "Anhänge" unten), fragt die Tastatur, ob der **Text**
   eingefügt oder die **Datei** angehängt werden soll. Sonst geht es direkt mit dem Text weiter.
4. Bei einem Archiv erscheint eine Liste der enthaltenen Dateien. Lesbare sind angehakt (Bilder
   nicht, weil die Texterkennung dauert). Abwählen, was nicht rein soll ("Alle an/aus" hilft),
   dann **Einfügen**.
5. Ist eine Datei mit Passwort geschützt, fragt die Tastatur danach. Falsches Passwort: noch
   einmal fragen. "Überspringen" lässt die Datei weg. Das Passwort wird nicht gespeichert.
6. Du kommst zurück in die App, der Text wird eingefügt. Ist die Signatur fällig, steht sie davor.

### Was gelesen wird

| Format | Was herauskommt |
| --- | --- |
| Textdateien (`.txt`, `.md`, `.yml`, `.json`, `.csv`, Code, ...) | Inhalt 1:1. UTF-8, UTF-16 und alte Windows-Dateien (Umlaute) werden erkannt |
| PDF | Der Text aller Seiten. Eingescannte Seiten per Texterkennung (bis 40 Seiten) |
| PDF mit Passwort | Nach Eingabe des Passworts wie oben |
| Word `.docx`, `.doc` (97–2003) | Absätze, Aufzählungen, Tabellen mit Tabulator zwischen den Zellen |
| Excel `.xlsx`, `.xls` (97–2003) | Jedes Blatt mit Namen, Zellen mit Tabulator, leere Zellen bleiben als Spalte erhalten. Datumswerte als `15.03.2023` (nicht als Zahl `45000`) |
| PowerPoint `.pptx`, `.ppt` (97–2003) | Text jeder Folie, mit "Folie N" davor |
| Word/Excel/PowerPoint mit Passwort (`.docx`, `.xlsx`, `.pptx`) | Nach Eingabe des Passworts wie oben. Dateien mit reinem Schreibschutz öffnen sich ohne Frage |
| LibreOffice `.odt`, `.ods`, `.odp` | Wie bei Word, Excel und PowerPoint |
| RTF | Text mit Absätzen, Sonderzeichen und Links |
| E-Books `.epub` | Kapitel in Lesereihenfolge |
| Outlook-Mails `.msg` | Betreff, Absender, Empfänger, Anhangsnamen und Text |
| Google Docs, Tabellen, Präsentationen (aus Drive) | Werden von Drive als Text bzw. Office-Datei exportiert und dann gelesen |
| Bilder (`.jpg`, `.png`, `.webp`, `.heic` ...) | Texterkennung (Deutsch und Englisch), z. B. für Screenshots oder fotografierte Dokumente |
| Archive: ZIP (auch mit Passwort), 7z (auch mit Passwort), `.tar`, `.tar.gz`, `.tar.bz2`, `.tar.xz` | Alle obigen Formate darin, auswählbar, jede Datei mit `[Datei: pfad]` davor. Archive im Archiv werden eine Ebene tief geöffnet |
| Einzelne komprimierte Dateien `.gz`, `.bz2`, `.xz` | Werden entpackt und dann gelesen |

Die Dateiart wird am **Inhalt** erkannt, nicht nur an der Endung. Eine `.doc`, die in Wahrheit
RTF ist, oder ein Download ohne Endung werden also trotzdem richtig gelesen.

Bei mehreren Dateien steht vor jeder `[Datei: name]`. Bei einer einzelnen Datei lässt sich das in
den Einstellungen abschalten.

### Texterkennung

Für eingescannte PDF-Seiten und Bilder nutzt die Tastatur **Tesseract** mit Sprachdaten für
Deutsch und Englisch, komplett auf dem Handy, ohne Internet. Erkannter Text kann Fehler
enthalten, die Tastatur weist darauf hin. Je Seite dauert es ein paar Sekunden; der Fortschritt
wird angezeigt, "Abbrechen" stoppt sofort. Seiten, die schon Text haben, werden nicht erkannt,
sondern direkt gelesen.

### Anhänge

Statt des Textes kann die Datei selbst in den Chat:

- **Als Datei anhängen:** Nur, wenn das Eingabefeld der App das ausdrücklich erlaubt
  (Android "Rich Content"). Viele Messenger erlauben es für Bilder, KI-Apps meist gar nicht.
  Die Tastatur bietet die Option nur an, wenn das Feld den Dateityp annimmt.
- **An die App senden (Teilen):** Wie "Teilen" im Dateimanager, aber direkt an die App, aus
  der du 📁 angetippt hast. Klappt bei allen Apps mit Teilen-Funktion (Claude, ChatGPT,
  Messenger ...), öffnet dort aber unter Umständen einen **neuen Chat**.

Für Anhänge legt die Tastatur eine Kopie der Datei im eigenen Cache ab. Die App, an die du sie
gibst, darf genau diese Datei lesen, sonst niemand. Die Kopien werden nach einem Tag gelöscht.

### Was nicht geht, und warum

- **RAR-Archive.** Das Format ist proprietär; das aktuelle RAR5 lässt sich ohne die Bibliothek
  von WinRAR nicht entpacken, und die gibt es nicht für eine solche App. Bitte als ZIP oder 7z
  packen.
- **Audio und Video.** Dafür bräuchte es Spracherkennung; brauchbare Modelle sind hunderte MB
  groß. Abschriften erstellen KI-Apps selbst besser.
- **Alte Office-Dateien mit Passwort** (`.doc`, `.xls`, `.ppt` mit Kennwort) und
  **passwortgeschützte OpenDocument-Dateien** (`.odt` ...). Das sind seltene, ältere
  Verschlüsselungsverfahren; die Tastatur sagt dir, dass die Datei geschützt ist. Bitte ohne
  Passwort, als `.docx`/`.xlsx` oder als PDF speichern. (Neue Office-Dateien mit Passwort gehen.)
- **Word 6/95 mit Schnellspeicherung, Excel 5/95, Apple Pages/Numbers/Keynote.** Sehr alte
  bzw. geschlossene Formate. Bitte als `.docx` oder PDF exportieren.
- **Handschrift** erkennt die Texterkennung kaum, gedruckter Text klappt gut.

### Grenzen und Schutz

- **Keine Speicher-Berechtigung.** Die App sieht nur die Dateien, die du antippst, und nur in
  diesem Moment. Gelesen wird komplett auf dem Handy. Der Text liegt nur kurz im
  Arbeitsspeicher, Zwischendateien werden sofort gelöscht.
- Höchstens **100.000 Zeichen** insgesamt (etwa 40 Buchseiten). Was darüber hinausgeht, wird
  abgeschnitten, und du siehst, welche Dateien betroffen sind.
- Dateien über 100 MB werden nicht gelesen (bei Textdateien: nur der Anfang). Archive sind gegen
  "ZIP-Bomben" abgesichert (höchstens 40 MB je entpackter Datei, 200 MB insgesamt, 2000 Einträge).
- Eingefügt wird nur in **die App, aus der du 📁 angetippt hast**, nie in ein Passwortfeld.
  Wartet der Text länger als 3 Minuten, verfällt er.
- Erscheint die Tastatur nach der Auswahl nicht von selbst, einmal ins Eingabefeld tippen.
- Die APK ist rund 17 MB groß. Den größten Teil macht die Texterkennung aus (Programm und
  Sprachdaten, rund 9 MB), dann die PDF-Unterstützung.

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
- Was die Datei-Taste nicht kann, steht oben unter "Dateien".
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
  FilePickerActivity.kt Dateidialog, Text oder Anhang, Archiv-Auswahl, Passwort, Meldungen
  DocumentReader.kt     Dateiart erkennen, Text aus allen Formaten (ohne Android-Code)
  LegacyOffice.kt       Alte Office-Formate .doc/.xls/.ppt, Excel-Datumswerte
  Cfb.kt                Containerformat alter Office-Dateien (OLE2)
  OfficeCrypto.kt       Entschlüsselung passwortgeschützter .docx/.xlsx/.pptx
  RtfText.kt            RTF
  Archives.kt           ZIP (zip4j), 7z und tar (Commons Compress)
  PdfText.kt            Text aus PDFs (PDFBox)
  TesseractOcr.kt       Texterkennung für Bilder und eingescannte PDF-Seiten
  AttachmentProvider.kt Dateien für Anhänge bereitstellen
  FileBridge.kt         Übergabe von Text und Anhängen an die Tastatur
app/src/main/assets/tessdata/  Sprachdaten Deutsch/Englisch (tessdata_fast, Apache-2.0)
app/src/test/           Tests (gradle testDebugUnitTest): alle Formate mit echten
                        Beispieldateien, dazu Robolectric-Tests für Dialoge, Anhänge und Tastatur
.github/workflows/build.yml
```
