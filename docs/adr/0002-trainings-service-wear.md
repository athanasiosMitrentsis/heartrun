# ADR 0002: Trainings-Service auf der Uhr

- Status: **Vorgeschlagen** (wird vom Menschen angenommen oder abgelehnt)
- Datum: 2026-10-22
- Grundlage: docs/spec.md, ADR 0001, docs/research/polar-ble.md (Spike-Ergebnisse), docs/research/wear-training-service.md

## Kontext

ADR 0001 legt fest: Die HF kommt über eine eigene GATT-Verbindung vom H10, Distanz, Pace und GPS kommen aus `ExerciseClient`, und beides läuft in einem Foreground Service. Offen ist, wie dieser Service und der Trainingsablauf aussehen, wie die BLE-Verbindung stabil bleibt und wie die Oberfläche an die Daten kommt. Außerdem verlangt ADR 0001, dass Kriterium 2 (automatische Wiederverbindung) in der ersten Implementierung mit Foreground Service erneut geprüft wird.

**Leitlinie (Vorgabe des Menschen, 2026-10-22):** Wir erfinden nichts neu. Wir bauen nach, was sich bewährt hat, mit einem Unterschied: Die Uhr verbindet sich direkt mit dem H10 und zeigt dessen Werte live an.
- **Bedienung und Verhalten:** wie die Google-Trainings-App (Fitbit Exercise / Google Health) auf der Pixel Watch.
- **Technischer Aufbau:** wie Googles offizielle Beispiel-App `ExerciseSampleCompose`.
- **Abweichungen nur dort, wo der H10 (BLE) oder CLAUDE.md (Koin, Navigation 3, Room) sie verlangt.** Jede Abweichung wird unten benannt.

Ergebnisse aus wear-training-service.md (W) und den Spike-Ergebnissen in polar-ble.md (S), auf die sich diese Entscheidung stützt:
- **W1** (belegt): Foreground-Service-Typen lassen sich mit `|` kombinieren. Für jeden Typ gelten seine Voraussetzungen. Ein Start mit While-in-use-Berechtigungen muss erfolgen, während eine Activity sichtbar ist.
- **W2** (belegt): `ExerciseClient` braucht einen Foreground Service für das Training. Systemweit läuft höchstens eine Exercise. Die Quelle der Wahrheit für den Zustand ist `ExerciseUpdate`.
- **W3** (belegt): `PACE` in ms/km, `DISTANCE_TOTAL` in m, `PACE_STATS` als Statistik seit Start. Mit `isGpsEnabled` wird die Distanz aus GPS geschätzt.
- **W4** (belegt): Eine Ongoing Activity ist für Trainings Pflicht (WO-V4) und hält die App im Ambient-Modus sichtbar.
- **W5** (belegt): `autoConnect = true` verbindet nach einer Trennung automatisch neu, solange der Prozess lebt. Ab API 37 gibt es dafür `connectGatt(BluetoothGattConnectionSettings, …)`.
- **W6a** (belegt): Die Google-Trainings-App hat Start, Pause und Ende mit nach rechts Wischen auf die Steuerung, kann auf Wunsch ohne GPS-Fix starten, zeigt danach eine Zusammenfassung und bietet eine Einstellung „Always-on screen“ für das Training. Einen Brustgurt **empfängt** die aktuelle Pixel Watch nicht. Die alte Google-Fit-App (nur bis Wear OS 3) ließ den Gurt einmal in den Einstellungen wählen und zeigte „Bluetooth“ neben dem BPM-Wert.
- **W6b** (belegt): `ExerciseSampleCompose` nutzt:
  - einen gebundenen und gestarteten `LifecycleService` mit `START_STICKY`, dessen Zustand als `Flow` über den Binder kommt
  - `startForeground` erst bei `startExercise()`
  - eine Ongoing Activity mit Stoppuhr
  - die Bildschirme Nicht verfügbar → Vorbereitung (GPS-Status, Start) → Training (Pager: Steuerung | Messwerte) → Zusammenfassung
  - eine Warnung, wenn eine andere App trainiert
  - Messwerte, die im Ambient-Modus in Graustufen sichtbar bleiben
- **S1** (beobachtet): Nicht geschlossene `BluetoothGatt`-Instanzen führen zu doppelten Notifications.
- **S2** (beobachtet): Nach dem Abnehmen sendet der H10 etwa 11 s lang den letzten Wert, danach trennt er die Verbindung. Bei schlechtem Kontakt kommt HF 0.
- **S3** (beobachtet): Ohne Foreground Service und Ongoing Activity bricht die HF etwa 3 min nach dem Ambient-Wechsel ab.

## Entscheidung

### 1. Service: wie `ExerciseSampleCompose`
- `TrainingService` ist ein `LifecycleService`, gebunden und gestartet, mit `START_STICKY`. Sein Verhalten nach einem Neustart und beim Lösen der Bindung (3 s Wartezeit) folgt dem Beispiel (W6b).
- Der Zustand kommt als `StateFlow` über den Binder an die Oberfläche (wie im Beispiel).
- `startForeground` erst bei `startExercise()`, wie im Beispiel. **Abweichung:** mit `location|health|connectedDevice` statt `location|health`, weil der Service auch die H10-Verbindung hält (ADR 0001). Manifest: `foregroundServiceType="health|location|connectedDevice"`.
- Bei `isEnded` wird die Notification entfernt, wie im Beispiel. **Zusätzlich** wird die BLE-Verbindung geschlossen.

### 2. Bildschirme und Ablauf: Bedienung wie die Google-Trainings-App, Aufbau wie das Beispiel
1. **Sportart wählen:** Laufen oder Radfahren (spec.md).
2. **Nicht verfügbar:** Erscheint, wenn die Abfrage der Fähigkeiten die Sportart nicht unterstützt. Wie im Beispiel.
3. **Vorbereitung** (wie im Beispiel):
   - Berechtigungen anfragen.
   - Warnung, wenn eine andere App trainiert.
   - `prepareExercise()` aufrufen.
   - GPS-Status am Rand anzeigen und einen Start-Button.
   - **Zusätzlich:** Der Sensorstatus des H10 (Suche / verbunden / getrennt) steht neben dem GPS-Status, und die H10-Verbindung wird schon hier aufgebaut.
   - Gestartet werden kann auch ohne GPS-Fix und ohne H10, wie in der Google-App: GPS bzw. der Sensor verbinden sich dann später.
4. **Training:** horizontaler Pager wie im Beispiel.
   - Eine Seite mit der Steuerung (Pause/Weiter, Ende), erreichbar mit nach rechts Wischen, wie in der Google-App.
   - Die Messwertseite ist Startseite und zeigt die Werte von **Seite 1 aus spec.md**: Distanz, Uhrzeit, aktuelle Pace, aktuelle HF.
   - Seite 2 aus spec.md (Ø-HF, Ø-Pace) ist Priorität 2 und kommt später als weitere Pager-Seite dazu.
5. **Zusammenfassung** nach dem Ende, wie im Beispiel und in der Google-App: Dauer, Distanz, Ø-HF, Ø-Pace.

Pause, Weiter und Ende laufen über `ExerciseClient`. Der Zustand kommt nur aus `ExerciseUpdate` (W2).

### 3. Ongoing Activity: wie im Beispiel
Notification mit `CATEGORY_WORKOUT`, Ongoing Activity mit Stoppuhr-Status und einem Touch-Intent zurück ins Training (W4, W6b).

### 4. Ambient: wie im Beispiel
- Die Messwerte bleiben im Ambient-Modus sichtbar, in Graustufen (`ambientGray` wie im Beispiel).
- Die Daten werden im Service unabhängig vom Display lückenlos gespeichert.
- **Später** (nicht MVP, nicht in spec.md): eine Einstellung „Display während des Trainings an“, wie „Always-on screen“ in der Google-App.

### 5. H10: Auswahl wie früher in Google Fit, Verbindung nach Android-Doku
Hier gibt es keine aktuelle Google-Vorlage (W6a). Deshalb gilt:
- **Auswahl:** Der H10 wird **einmal** in den Einstellungen der App gewählt (Suche mit Filter auf den Heart Rate Service), wie früher „Bluetooth sensors“ in Google Fit. Danach verbindet sich die App bei jedem Training automatisch mit dem gemerkten Sensor. Wo die Adresse gespeichert wird, regelt ADR 0003.
- **Anzeige:** Neben dem BPM-Wert steht ein kleines Bluetooth-Symbol, solange die HF vom H10 kommt, wie früher in Google Fit. Der Sensorstatus aus spec.md (Suche / verbunden / getrennt) steht auf der Messwertseite.
- **Verbindung:**
  - **Genau eine `BluetoothGatt`-Instanz**, gehalten vom Service. Vor jedem neuen Aufbau wird die alte mit `close()` geschlossen (S1).
  - Die erste Verbindung läuft direkt. Nach einer Trennung wird die alte Instanz geschlossen und eine neue mit `autoConnect = true` aufgebaut (W5).
  - **Watchdog:** Ist der Sensor nach 60 s nicht wieder verbunden, wird die Instanz geschlossen und neu aufgebaut. Der Wert ist eine Annahme und wird in der Validierung überprüft.
  - Auf API ≥ 37 wird `connectGatt(BluetoothGattConnectionSettings, …)` genutzt, darunter die bisherige Variante, jeweils mit `TRANSPORT_LE`.
- **HF 0:** Wird als `--` angezeigt und zählt nicht in die Ø-HF (S2). Eine weitere Kontakterkennung gibt es nicht.
- **Bekannte Grenze:** Nach dem Abnehmen zeigt die App bis zu etwa 11 s den letzten Wert, bis der H10 die Verbindung trennt (S2).
- Die BLE-Verbindung besteht nur von der Vorbereitung bis zum Ende des Trainings.

### 6. Zeitbasis und Speicherung
- Zeitbasis wie in ADR 0001, Punkt 4. Die Dauer wird aus dem `activeDurationCheckpoint` berechnet, wie im Beispiel.
- Messwerte werden **während** des Trainings laufend gespeichert (Room laut Health-Services-Doku). Das Schema regelt ADR 0003.

## Bewusst nicht übernommen (vorerst)
- **Auto-Pause, Ziele, Runden, HF-Zonen-Hinweise:** Die Google-App und das Beispiel haben sie, spec.md verlangt sie im MVP aber nicht. HF-Zonen sind dort Priorität 3.
- **Horologist-Bibliotheken des Beispiels:** Für den MVP sind sie nicht nötig. Die Wear-Compose-Bausteine reichen.
- **Hilt und `SwipeDismissableNavHost` aus dem Beispiel:** Stattdessen Koin und Navigation 3 (CLAUDE.md).

## Konsequenzen

- **Manifest wear:**
  - Berechtigungen wie im Beispiel: `BODY_SENSORS` (maxSdk 35), `READ_HEART_RATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACTIVITY_RECOGNITION`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, `FOREGROUND_SERVICE_LOCATION`.
  - `<queries>` auf `com.google.android.wearable.healthservices`, Activity mit `singleTask`.
  - **Zusätzlich:** `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- **Neue Abhängigkeiten:** `androidx.health:health-services-client:1.1.0`, `androidx.wear:wear-ongoing:1.1.0`, `androidx.lifecycle:lifecycle-service`.
- **Der Spike-Code** (`HeartRateBleClient`, Spike-Screen) wird ersetzt. `HeartRateParser` mit seinen Tests bleibt.
- **Offene Risiken (Vermutungen):**
  - `location|health|connectedDevice` wirft auf Wear OS 7 eine Ausnahme. Das Beispiel nutzt nur `location|health`.
  - `autoConnect` verbindet auf der Pixel Watch 2 auch mit einer einzigen Instanz nicht zuverlässig. Dann wird um einen Scan mit Adressfilter ergänzt.
  - Health Services lehnt eine Exercise ohne `HEART_RATE_BPM` ab oder schränkt sie ein.
  - Akkuverbrauch: GPS, BLE und Display für mehr als 60 min.
- **Folge-ADR 0003:** Koin (anstelle von Hilt im Beispiel), Room (Einheit, HF-Zeitreihe, Health-Services-Zeitreihe, gemerkter Sensor) und Navigation 3 (die Bildschirme aus Punkt 2). Erst danach beginnt die Implementierung.

## Validierung: erste Implementierung

Gearbeitet wird nach Annahme dieses ADR und von ADR 0003 auf einem eigenen Branch. Getestet wird auf der Pixel Watch 2 (Wear OS 7) mit dem H10. Die Ergebnisse kommen wie beim Spike nach `docs/research/`.

1. **Foreground Service startet:** `startForeground` mit `location|health|connectedDevice` wirft keine Ausnahme. Die Ongoing Activity ist auf dem Zifferblatt sichtbar.
2. **Kriterium 2 aus ADR 0001 (offener Prüfpunkt):**
   - Im laufenden Training den H10 vom Gurt nehmen. Die App zeigt den Status „getrennt“.
   - Nach dem Wiederanlegen ist der Sensor **ohne Eingabe** innerhalb von 30 s wieder „verbunden“.
   - Pro Paket gibt es genau eine Logzeile, also keine Kopien.
3. **Hintergrund:** Training mindestens 10 min.
   - Dabei je mindestens 3 min Display ambient, Display aus (Always-on aus) und eine andere App im Vordergrund.
   - Danach zeigt das Log lückenlose HF etwa im Sekundentakt, Lücken unter 5 s.
   - Der Prozess wurde nicht beendet oder eingefroren.
4. **Lauf im Freien:** mindestens 20 min mit GPS und H10. Die Distanz weicht höchstens etwa 5 % von einer bekannten Strecke ab, die Pace ist plausibel und die HF läuft parallel.
5. **Vergleich mit der Google-Trainings-App (Beobachtung):** Gleiche Strecke einmal mit heartrun und einmal mit der Google-App aufzeichnen. Distanz und Pace vergleichen.
6. **Beobachtung:** Akkuverbrauch in % pro 30 min Training.

Fällt Kriterium 1 oder 2 durch, wird dieses ADR neu bewertet. Fällt Kriterium 2 durch, wird auch ADR 0001 neu bewertet.
