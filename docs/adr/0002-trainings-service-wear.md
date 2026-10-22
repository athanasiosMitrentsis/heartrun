# ADR 0002: Trainings-Service auf der Uhr

- Status: **Vorgeschlagen** (wird vom Menschen angenommen oder abgelehnt)
- Datum: 2026-10-22
- Grundlage: docs/spec.md, ADR 0001, docs/research/polar-ble.md (Spike-Ergebnisse), docs/research/wear-training-service.md

## Kontext

ADR 0001 legt fest: Die HF kommt über eine eigene GATT-Verbindung vom H10, Distanz, Pace und GPS kommen aus `ExerciseClient`, und beides läuft in einem Foreground Service. Offen ist, wie dieser Service aufgebaut ist, wann er läuft, wie die BLE-Verbindung stabil bleibt und wieder aufgebaut wird und wie die Oberfläche an die Daten kommt. Außerdem verlangt ADR 0001, dass Kriterium 2 (automatische Wiederverbindung) in der ersten Implementierung mit Foreground Service erneut geprüft wird.

Ergebnisse aus wear-training-service.md (W) und den Spike-Ergebnissen in polar-ble.md (S), auf die sich diese Entscheidung stützt:
- **W1** (belegt): Foreground-Service-Typen lassen sich mit `|` kombinieren. Für jeden Typ gelten seine Voraussetzungen. Ein Start mit While-in-use-Berechtigungen (`location`, `health` mit Körpersensoren) muss erfolgen, während eine Activity sichtbar ist.
- **W2** (belegt): `ExerciseClient` verlangt einen Foreground Service für das ganze Training, auch schon für `prepareExerciseAsync()`. Systemweit läuft höchstens eine Exercise. Die Quelle der Wahrheit für den Zustand ist `ExerciseUpdate`.
- **W3** (belegt): `PACE` in ms/km (0 bei Stillstand), `DISTANCE_TOTAL` in m, `PACE_STATS` als Statistik seit Start. Mit `isGpsEnabled` wird die Distanz aus GPS geschätzt statt aus Schritten.
- **W4** (belegt): Ongoing Activity (oder ab Wear OS 7 Live Update) ist für Trainings Pflicht (WO-V4) und hält die App im Ambient-Modus sichtbar. Im Ambient-Modus sollen schnell wechselnde Werte als `--` erscheinen.
- **W5** (belegt): `autoConnect = true` verbindet nach einer Trennung durch das Peripheriegerät automatisch neu, solange der Prozess lebt. Ab API 37 ist `connectGatt(Context, …)` deprecated. Ersatz ist `connectGatt(BluetoothGattConnectionSettings, Executor, BluetoothGattCallback)`.
- **W6** (keine verlässliche Quelle): Ob CompanionDeviceManager auf Wear OS verfügbar ist.
- **S1** (beobachtet): Nicht geschlossene `BluetoothGatt`-Instanzen führen zu doppelten Notifications.
- **S2** (beobachtet): Der H10 meldet keinen Hautkontakt. Nach dem Abnehmen sendet er etwa 11 s lang den letzten Wert ohne RR, danach trennt er die Verbindung. Bei schlechtem Kontakt kommt HF 0.
- **S3** (beobachtet): Ohne Foreground Service und Ongoing Activity wird die App etwa 1 min nach dem Ambient-Wechsel unsichtbar. Etwa 2 min später kommt keine HF mehr.
- **S4** (beobachtet): Mit sichtbarer Activity kommen die BLE-Notifications auch im Ambient-Modus etwa im Sekundentakt.

## Optionen

### A. Aufbau des Service
- **(A1) Ein Service `TrainingService` mit `health|location|connectedDevice`.** Er hält Exercise und BLE-Verbindung, eine Notification und eine Ongoing Activity.
- **(A2) Zwei Services:** einer für die Exercise (`health|location`), einer für BLE (`connectedDevice`). Das ergibt zwei Lebenszyklen, die synchron bleiben müssen, und zwei Notifications bzw. Ongoing Activities. Der einzige Vorteil wäre, dass BLE ohne Training laufen könnte. Das verlangt spec.md nicht.

### B. Wiederverbindung zum H10
- **(B1) Nur `autoConnect = true`** mit genau einer Instanz.
- **(B2) `autoConnect = true` mit genau einer Instanz plus Watchdog.** Ist der Sensor nach einer festen Zeit nicht wieder verbunden, wird die Instanz geschlossen und neu aufgebaut.
- **(B3) CompanionDeviceManager** mit Erkennung, ob der Sensor in Reichweite ist. Die Verfügbarkeit auf Wear OS ist unklar (W6). CDM bringt einen eigenen Kopplungsdialog mit und hilft nur, wenn der Prozess nicht läuft. Das kommt bei einem laufenden Foreground Service nicht vor.

### C. Datenfluss Service → Oberfläche
- **(C1) App-weiter Zustandshalter mit `StateFlow`.** Der Service schreibt, die Oberfläche liest.
- **(C2) Gebundener Service** (Binder) wie im Android-BLE-Beispiel.

## Entscheidung

**A1, B2, C1.**

1. **Ein `TrainingService`** mit `android:foregroundServiceType="health|location|connectedDevice"`. Gestartet wird er mit allen drei Typen aus der sichtbaren Activity, wenn der Nutzer die Trainingsvorbereitung öffnet (W1). Ein Start aus dem Hintergrund ist nicht vorgesehen.

2. **Lebenszyklus:**
   - **Vorbereitung:**
     - Service starten.
     - `getCurrentExerciseInfoAsync()` prüfen. Bei `OTHER_APP_IN_PROGRESS` wird nachgefragt, bevor eine andere Exercise beendet wird (W2).
     - `prepareExerciseAsync()` aufrufen und die Verbindung zum H10 aufbauen.
     - Die Oberfläche zeigt dabei den GPS- und Sensorstatus.
   - **Start:**
     - `startExerciseAsync()` mit `ExerciseType` je Sportart, `isGpsEnabled = true` und den Datentypen `DISTANCE_TOTAL`, `PACE`, `PACE_STATS`, `SPEED`, `LOCATION`.
     - Welche Typen tatsächlich angefragt werden, ergibt sich aus der Abfrage der Fähigkeiten zur Laufzeit.
     - `HEART_RATE_BPM` wird nicht angefragt (ADR 0001, Punkt 3).
   - **Pause/Ende:**
     - Der Zustand kommt ausschließlich aus `ExerciseUpdate` (W2).
     - Bei jedem `isEnded`, auch durch eine andere App oder durch Verlust einer Berechtigung, wird die BLE-Verbindung geschlossen und die Notification entfernt. Dann wird der Service beendet.
   - **Abbruch der Vorbereitung:** Verlässt der Nutzer die Vorbereitung ohne Start, wird `endExercise()` aufgerufen, die Verbindung geschlossen und der Service gestoppt.
   - Die BLE-Verbindung besteht **nur** zwischen Vorbereitung und Ende des Trainings.

3. **Ongoing Activity** (`androidx.wear:wear-ongoing`) für die ganze Dauer, verknüpft mit der Foreground-Notification. Der Touch-Intent führt zur Trainingsanzeige (W4). Live Update (ab Wear OS 7) wird nicht genutzt, weil die Ongoing Activity auf allen Versionen ab minSdk 33 funktioniert.

4. **BLE-Verbindung (B2):**
   - **Genau eine `BluetoothGatt`-Instanz** pro Sensor, gehalten von einer einzigen Klasse. Vor jedem neuen Verbindungsaufbau wird die alte Instanz mit `close()` geschlossen (S1).
   - **Erste Verbindung** direkt, mit `autoConnect = false`. Nach einer Trennung wird die alte Instanz geschlossen und eine neue mit `autoConnect = true` aufgebaut (W5).
   - **Watchdog:** Ist der Sensor 60 s nach der Trennung nicht wieder verbunden, wird die Instanz geschlossen und eine neue mit `autoConnect = true` aufgebaut. Das wiederholt sich bis zum Trainingsende. Einen periodischen Scan gibt es nicht. Der Wert 60 s ist eine Annahme und wird in der Validierung überprüft.
   - **API-Weiche:**
     - Auf API ≥ 37 wird `connectGatt(BluetoothGattConnectionSettings, Executor, callback)` genutzt, mit `setAutoConnectEnabled(…)` und `setTransport(TRANSPORT_LE)`.
     - Darunter wird die bisherige Variante mit `TRANSPORT_LE` genutzt (W5).
   - **Gemerkter Sensor:**
     - Die Adresse des zuletzt gewählten H10 wird gespeichert. Gesucht wird nur, wenn noch kein Sensor bekannt ist oder der Nutzer einen anderen wählt.
     - Wo die Adresse gespeichert wird, regelt das ADR zu Room bzw. Einstellungen.

5. **Sensorstatus:** Die drei Zustände aus spec.md werden um einen Zustand für fehlenden Kontakt erweitert (S2):
   - `Suche`: Scan oder `autoConnect` wartet.
   - `Verbunden`: Notifications kommen.
   - `Kein Kontakt`: HF = 0 oder 5 s lang Pakete ohne RR-Intervalle. Die Anzeige zeigt `--`, und diese Werte gehen nicht in die Ø-HF ein.
   - `Getrennt`: Die Verbindung ist weg, der Watchdog läuft.

   Die Schwelle von 5 s ist eine Annahme und wird in der Validierung überprüft.

6. **Datenfluss (C1):**
   - Ein app-weiter `TrainingRepository` hält den Zustand als `StateFlow`:
     - Trainingszustand
     - Sensorstatus
     - letzte HF mit Zeitstempel
     - letzte Distanz, Pace und Dauer-Checkpoint
   - Der Service schreibt, die Oberfläche liest.
   - Die Oberfläche bindet sich nicht an den Service. Befehle (Start, Pause, Ende) gehen über `Intent` bzw. Methoden des Repositorys an den Service.
   - Wie das Repository bereitgestellt wird (Koin) und gespeichert wird (Room), regelt ADR 0003.
   - Schon hier gilt: Messwerte werden **während** des Trainings laufend gespeichert, nicht erst am Ende. So geht bei einem Absturz nichts verloren.

7. **Ambient:**
   - Die Trainingsanzeige reagiert mit `LocalAmbientModeManager`.
   - Im Ambient-Modus erscheinen HF, Pace und Distanz als `--`. Uhrzeit und Dauer werden etwa einmal pro Minute aktualisiert (W4).
   - Gespeichert wird im Service unabhängig vom Display weiter (S4).

8. **Zeitbasis** wie in ADR 0001, Punkt 4: `elapsedRealtimeNanos()` für BLE-Proben, „Zeit seit Boot“ für Health Services. Die aktive Dauer wird aus dem Checkpoint berechnet, nicht aus der Häufigkeit der Updates (W2).

## Konsequenzen

- **Manifest wear:**
  - Zusätzlich zu den Berechtigungen aus ADR 0001 (inkl. Nachtrag minSdk 33): `<service android:name=".training.TrainingService" android:foregroundServiceType="health|location|connectedDevice" android:exported="false">`.
  - Dazu `POST_NOTIFICATIONS`. Für den Start des Foreground Service ist die Berechtigung laut Doku nicht nötig. Ohne sie erscheint die Notification aber nicht in der Notification-Leiste (wear-training-service.md, Frage 3). Ob die Ongoing Activity ohne die Berechtigung angezeigt wird, ist nicht belegt. Deshalb wird sie angefragt.
- **Neue Abhängigkeiten:** `androidx.health:health-services-client:1.1.0`, `androidx.wear:wear-ongoing:1.1.0`.
- **Abfrage der Berechtigungen** vor dem Start, gebündelt in der Vorbereitung:
  - `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `ACTIVITY_RECOGNITION`, `READ_HEART_RATE` (API ≥ 36) bzw. `BODY_SENSORS` (≤ 35), `POST_NOTIFICATIONS`
  - Prüfung, ob GPS eingeschaltet ist (`isProviderEnabled(GPS_PROVIDER)`)
- **Der Spike-Code** (`HeartRateBleClient`, Spike-Screen) wird ersetzt, nicht erweitert. `HeartRateParser` mit seinen Tests bleibt.
- **Offene Risiken (Vermutungen):**
  - `health|location|connectedDevice` wirft auf Wear OS 7 trotz erfüllter Voraussetzungen eine Ausnahme.
  - `autoConnect` verbindet auf der Pixel Watch 2 auch mit einer einzigen Instanz nicht zuverlässig. Dann muss B2 durch einen Scan mit Adressfilter ergänzt werden.
  - Health Services lehnt eine Exercise ohne `HEART_RATE_BPM` ab oder schränkt sie ein.
  - Akkuverbrauch: GPS, BLE und Display für mehr als 60 min.
- **Folge-ADR 0003:** Koin (Bereitstellung von `TrainingRepository` und Clients), Room (Schema für Einheit, HF-Zeitreihe und Health-Services-Zeitreihe, gemerkter Sensor), Navigation 3 (Vorbereitung → Training → Zusammenfassung). Erst danach beginnt die Implementierung.

## Validierung: erste Implementierung

Gearbeitet wird nach Annahme dieses ADR und von ADR 0003 auf einem eigenen Branch. Die Kriterien gelten auf der Pixel Watch 2 (Wear OS 7) mit dem H10. Die Ergebnisse kommen wie beim Spike nach `docs/research/` (Logs und Auswertung).

1. **Foreground Service startet:** `startForeground` mit `health|location|connectedDevice` wirft keine Ausnahme. Die Ongoing Activity ist auf dem Zifferblatt sichtbar.
2. **Kriterium 2 aus ADR 0001 (offener Prüfpunkt):** Im laufenden Training den H10 vom Gurt nehmen. Die App zeigt `Kein Kontakt` und dann `Getrennt`. Nach dem Wiederanlegen ist der Sensor **ohne Eingabe** innerhalb von 30 s wieder `Verbunden`. Pro Paket gibt es genau eine Logzeile, also keine Kopien.
3. **Hintergrund:** Training mindestens 10 min. Dabei je mindestens 3 min Display ambient, Display aus (Always-on aus) und eine andere App im Vordergrund. Danach zeigt das Log lückenlose HF etwa im Sekundentakt, Lücken unter 5 s. Der Prozess wurde nicht beendet oder eingefroren.
4. **Lauf im Freien:** mindestens 20 min mit GPS und H10. Die Distanz weicht höchstens etwa 5 % von einer bekannten Strecke ab, die Pace ist plausibel und die HF läuft parallel.
5. **Beobachtung:** Akkuverbrauch in % pro 30 min Training.
6. **Beobachtung:** Verhalten, wenn während des Trainings eine andere App eine Exercise startet.

Fällt Kriterium 1 oder 2 durch, wird dieses ADR neu bewertet. Fällt Kriterium 2 durch, wird auch ADR 0001 neu bewertet.
