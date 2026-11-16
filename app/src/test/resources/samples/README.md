# Beispieldateien für die Tests

Echte Dateien in alten bzw. verschlüsselten Formaten, die sich in Tests nicht einfach erzeugen
lassen. Herkunft (beide Apache-2.0):

- `*.doc`, `*.xls`, `*.ppt`, `*.docx`, `*.xlsx`, `quick.msg`: Testdaten von Apache POI
  (https://github.com/apache/poi, Ordner `test-data`)
- `bla.encrypted.7z` (Passwort `foo`): Testdaten von Apache Commons Compress
  (https://github.com/apache/commons-compress, Ordner `src/test/resources`)

`bug53475-password-is-pass.docx` hat das Passwort `pass`. `protected_agile.docx` und
`protect.xlsx` sind nur schreibgeschützt (Office-Standardpasswort).
