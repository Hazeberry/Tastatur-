# Hazeberry Keyboard

Eine kleine Android-Tastatur, die **in langen KI-Chats** (Claude, ChatGPT, Mistral, ...)
automatisch eine Signatur vor deine Nachricht setzt, zum Beispiel Ziel, Regeln und aktuellen
Stand eines Projekts. So verliert das Modell den Faden nicht.

Alles läuft lokal auf dem Handy. Es gibt keinen Server, keine Datenbank und keine
Internet-Berechtigung.

## Funktionen

- QWERTZ-Tastatur mit Umlauten, Shift/Caps-Lock, automatischer Großschreibung am Satzanfang,
  Zahlen- und Symbolseiten (inkl. `[]{}<>|\~^$` für YAML und Code) und Emoji-Seite
- Signatur nach **N gesendeten Nachrichten**, nach **N Minuten** oder **per Tasker**
- Die Signatur wird der nächsten Nachricht **vorangestellt**, sobald du das erste Zeichen tippst
- Statuszeile über der Tastatur ("Noch 3 Nachricht(en) bis zur Signatur")
- Taste **Sig** zum manuellen Einfügen
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
(Gradle 8.7, JDK 17).

## Installieren und einrichten

1. APK auf dem Handy öffnen und die Installation aus dieser Quelle erlauben.
2. **Hazeberry Keyboard** öffnen. Dort **Tastatur aktivieren** und danach **Tastatur auswählen**.
3. Signatur eintragen (Button "Beispiel einfügen" zeigt ein Beispiel für eine GitHub Action),
   Intervall festlegen, **Speichern**.
4. In der KI-App tippen. Über das Symbol 🌐 in der Tastatur wechselst du zurück zu deiner
   normalen Tastatur.

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
.github/workflows/build.yml
```
