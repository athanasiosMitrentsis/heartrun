# Recherche: Trainings-Service auf der Uhr (Foreground Service, ExerciseClient, BLE)

Stand: 2026-10-22 (alle Quellen an diesem Tag abgerufen)
Bezug: ADR 0001 (Entscheidung, Konsequenzen „Ein Foreground Service“ und offener Prüfpunkt zu Kriterium 2), docs/research/polar-ble.md (Spike-Ergebnisse)
Erlaubte Quellen: developer.android.com, polar.com / support.polar.com, github.com/polarofficial

Legende:
- **Belegt**: steht so in der genannten Quelle (Zitat oder enge Paraphrase).
- **Beobachtet**: aus dem Spike vom 2026-10-22 (Einzeltest, ein Gerät).
- **Vermutung**: eigene Schlussfolgerung, nicht direkt belegt.
- **Keine verlässliche Quelle**: nichts Passendes in den erlaubten Quellen gefunden.

Hinweis zur Methode: Einige API-Referenzseiten liefert der Web-Abruf nur als Navigation. Die Stellen aus `BluetoothDevice`, `BluetoothGattConnectionSettings(.Builder)`, `CompanionDeviceService` und `DataType` wurden deshalb aus dem HTML der Seite gelesen.

## Kurzfazit

- Ein einzelner Foreground Service mit `health|location|connectedDevice` ist nach der Doku formal möglich: Typen werden mit `|` kombiniert, und für jeden Typ gelten seine eigenen Voraussetzungen. Ein Beispiel genau dieser Kombination gibt es nicht. Ob er auf Wear OS 7 ohne Ausnahme startet, prüft die erste Implementierung.
- Der Service muss gestartet werden, **während eine Activity sichtbar ist**. `location` und die sensorbasierten `health`-Berechtigungen unterliegen der While-in-use-Regel.
- `ExerciseClient` verlangt ausdrücklich einen Foreground Service für das ganze Training, auch schon für `prepareExercise`. Pace und Distanz liefert es mit festen Einheiten (ms/km, m).
- Für laufende Trainings verlangen die Wear-OS-Qualitätsrichtlinien eine **Ongoing Activity** (oder ab Wear OS 7 eine Live Update Notification). Laut Always-on-Doku hält genau sie die App im Ambient-Modus sichtbar, statt zum Zifferblatt zurückzukehren. Das passt zum Spike, in dem die App etwa 1 min nach dem Ambient-Wechsel unsichtbar wurde.
- BLE: `autoConnect = true` verbindet laut Doku nach einer Trennung durch das Peripheriegerät automatisch neu, solange der Prozess lebt. Ab API 37 sind alle `connectGatt(Context, …)`-Varianten deprecated. Ersatz ist `connectGatt(BluetoothGattConnectionSettings, Executor, BluetoothGattCallback)`.
- CompanionDeviceManager bringt für den Fall „Verbindung nur während eines Trainings im Foreground Service“ keinen klaren Vorteil. Seine Verfügbarkeit auf Wear OS ist nicht verlässlich belegt.

---

## Frage 1: Foreground Service mit `health|location|connectedDevice`

**Belegt**
- Mehrere Typen pro Service: „If multiple types apply to your service, separate them with the `|` operator.“ Als Beispiel steht dort `camera|microphone`.
  https://developer.android.com/develop/background-work/services/fgs/declare (abgerufen 2026-10-22)
- Ein Typ, der bei `startForeground()` übergeben, aber nicht im Manifest deklariert ist, führt zu `IllegalArgumentException`. Fehlt die Typ-Deklaration ganz, gibt es `MissingForegroundServiceTypeException`.
  https://developer.android.com/develop/background-work/services/fgs/launch und …/fgs/declare (abgerufen 2026-10-22)
- `startForeground()` kann mit einer Teilmenge der deklarierten Typen und später erneut mit der bitweisen Kombination aufgerufen werden. Das Beispiel dort ist ein Lauf-Tracker, der Medien nachträglich hinzunimmt.
  https://developer.android.com/develop/background-work/services/fgs/launch (abgerufen 2026-10-22)
- Voraussetzungen je Typ:
  https://developer.android.com/develop/background-work/services/fgs/service-types (abgerufen 2026-10-22)
  - `connectedDevice`: `FOREGROUND_SERVICE_CONNECTED_DEVICE`, dazu unter anderem eine erteilte `BLUETOOTH_CONNECT`- oder `BLUETOOTH_SCAN`-Berechtigung.
  - `health`: `FOREGROUND_SERVICE_HEALTH`, dazu `HIGH_SAMPLING_RATE_SENSORS` im Manifest oder eine erteilte Berechtigung aus `BODY_SENSORS` (≤ API 35), `READ_HEART_RATE`, `READ_SKIN_TEMPERATURE`, `READ_OXYGEN_SATURATION`, `ACTIVITY_RECOGNITION`.
  - `location`: `FOREGROUND_SERVICE_LOCATION`, dazu aktivierte Standortdienste und `ACCESS_COARSE_LOCATION` oder `ACCESS_FINE_LOCATION`.
- Beim Kombinieren gilt: „the service must still adhere to the prerequisites of the other declared types“. Das steht im Abschnitt zu `shortService`.
  https://developer.android.com/develop/background-work/services/fgs/service-types (abgerufen 2026-10-22)
- Start aus dem Hintergrund: Ab API 31 wirft das System `ForegroundServiceStartNotAllowedException`, außer in Ausnahmefällen. Ausnahmen sind unter anderem der Übergang aus einer sichtbaren Activity, eine Nutzeraktion an einer Notification und Companion Device Manager mit `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`.
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start (abgerufen 2026-10-22)
- While-in-use: „if your foreground service needs a while-in-use permission, you must call `Context.startForegroundService()` or `Context.bindService()` while your app has a visible activity, unless the service falls into one of the defined exemptions.“ Das betrifft `location` und `health` mit `BODY_SENSORS`, laut Seite aber nicht `health` mit `ACTIVITY_RECOGNITION`. Für `health` im Hintergrund werden `BODY_SENSORS_BACKGROUND` (API 33–35) bzw. `READ_HEALTH_DATA_IN_BACKGROUND` (API 36) genannt.
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start und …/fgs/service-types (abgerufen 2026-10-22)
- Wear OS 7: Die Seite zu den Verhaltensänderungen nennt nur Änderungen an Hintergrund-Audio (Foreground Service mit While-in-use-Fähigkeit für Audio im Hintergrund) und lokalem Netzwerk (`ACCESS_LOCAL_NETWORK`). Zu Foreground Services für Gesundheit, Standort oder Bluetooth steht dort nichts.
  https://developer.android.com/training/wearables/versions/7/changes (abgerufen 2026-10-22)

**Vermutung**
- `health|location|connectedDevice` in einem Service ist zulässig. Die Doku verbietet keine Kombination und beschreibt nur Kombinationen allgemein. Der Nachweis steht weiter aus (wie in ADR 0001).
- Startet der Service beim Tippen auf „Training vorbereiten“ in der sichtbaren Activity, sind alle While-in-use-Regeln erfüllt. Ein Start aus dem Hintergrund ist für dieses Projekt nicht nötig.
- Die Änderungen von Wear OS 7 betreffen den Trainings-Service nicht. Das gilt nur, solange er kein Audio im Hintergrund abspielt (etwa Ansagen).

**Keine verlässliche Quelle**
- Eine Frist, bis wann `startForeground()` nach `startForegroundService()` aufgerufen werden muss, steht auf den abgerufenen Seiten nicht.

---

## Frage 2: `ExerciseClient` im Trainingsablauf

**Belegt**
https://developer.android.com/health-and-fitness/guides/health-services/active-data (abgerufen 2026-10-22)
- „Use a continuously running `ForegroundService` in conjunction with `ExerciseClient` to help ensure correct operation for the entire workout.“ Das Manifest-Beispiel nutzt `android:foregroundServiceType="health|location"`.
- „Call `prepareExerciseAsync()` from your foreground service. If it is not in a service and is tied to the activity lifecycle, then the sensor preparation might be unnecessarily killed.“ Verlässt der Nutzer die Vorbereitung, soll `endExercise()` die Sensoren abschalten.
- Ein vorzeitiges Ende mit `AUTO_ENDED_PERMISSION_LOST` liegt laut Seite wahrscheinlich an einem fehlenden Foreground Service mit Standortberechtigung.
- Die Fähigkeiten werden beim Start abgefragt (`getCapabilitiesAsync()`, `supportedExerciseTypes`, `getExerciseTypeCapabilities()`). Nicht unterstützte Anfragen lehnt `ExerciseClient` ab, z. B. Auto-Pause.
- „Health Services supports, at most, one exercise at a time across all apps on the device.“ Vor dem Start soll `getCurrentExerciseInfoAsync()` geprüft werden (`OTHER_APP_IN_PROGRESS`, `OWNED_EXERCISE_IN_PROGRESS`, `NO_EXERCISE_IN_PROGRESS`). Wurde das Training durch eine andere App beendet, soll die App den Nutzer informieren.
- Die Quelle der Wahrheit für den Zustand ist `ExerciseUpdate.exerciseStateInfo.state`, nicht die Rückkehr von `pauseExerciseAsync()`. Ein Ende jeder Art wird über `state.isEnded` erkannt.
- Pro App gibt es nur einen Update-Listener, und er wird vor dem Start registriert.
- „If the `ExerciseConfig` specifies distance as a `DataType`, this defaults to using steps to estimate distance. By optionally enabling GPS, location information can be used instead to estimate distance.“
- Zustellung: „The processor delivers messages only when awake or when a maximum reporting period is reached, such as every 150 seconds. Don't rely on the `ExerciseUpdate` frequency to advance a chronometer“. Die aktive Dauer wird so berechnet: `(now() - checkpoint.time) + checkpoint.activeDuration`.
- Speicherung: „Use Room to persist data delivered from Health Services.“ Hochgeladen wird am Ende, z. B. mit WorkManager.
- Abhängigkeit: `androidx.health:health-services-client:1.1.0`.

Datentypen (Referenz `DataType`):
https://developer.android.com/reference/kotlin/androidx/health/services/client/data/DataType (abgerufen 2026-10-22)
- `PACE`: `DeltaDataType<Double, SampleDataPoint<Double>>`, „Pace at a specific point in time. Will be 0 if the user stops moving, otherwise the value will be in milliseconds/kilometer.“
- `PACE_STATS`: `AggregateDataType<Double, StatisticalDataPoint<Double>>`, „Statistics on pace since the start of the current exercise.“
- `DISTANCE`: `DeltaDataType<Double, IntervalDataPoint<Double>>`, „A distance delta between each reading expressed in meters.“
- `DISTANCE_TOTAL`: `AggregateDataType<Double, CumulativeDataPoint<Double>>`, „Total distance since the start of the active exercise expressed in meters.“
- `SPEED`: `DeltaDataType<Double, SampleDataPoint<Double>>`, „expressed as meters/second.“

**Vermutung**
- `HEART_RATE_BPM` muss nicht in `dataTypes` stehen, weil die HF vom H10 kommt (ADR 0001). Die Berechtigung `READ_HEART_RATE` bleibt trotzdem nötig, weil Health Services sie für Exercises verlangt (polar-ble.md, 1c). Ob Health Services eine Exercise ohne HF-Datentyp ohne Einschränkung akzeptiert, prüft die Implementierung.
- Für die Ø-Pace in der Zusammenfassung (spec.md) genügt `PACE_STATS` oder Gesamtdistanz geteilt durch aktive Dauer. Eine eigene Berechnung aus GPS-Punkten ist nicht nötig.
- Laufen und Radfahren unterscheiden sich nur im `ExerciseType`. Ob die Pixel Watch 2 für Radfahren `PACE` oder nur `SPEED` anbietet, zeigt die Abfrage der Fähigkeiten.

---

## Frage 3: Ongoing Activity und Ambient-Modus

**Belegt**
- Für „tracked workouts“ ist eine Ongoing Activity oder eine Live Update Notification laut Wear OS App Quality Guideline WO-V4 Pflicht. Die Ongoing Activity wird mit einer Notification mit `setOngoing(true)` verknüpft und mit `startForeground(…)` gepostet. Eine statische Icon-Grafik und ein Touch-Intent sind nötig, sonst kommt `IllegalArgumentException`. Abhängigkeit: `androidx.wear:wear-ongoing:1.1.0`. Ab Wear OS 7 ist alternativ eine Live Update Notification möglich.
  https://developer.android.com/training/wearables/ongoing-activity (abgerufen 2026-10-22)
- Ambient: „After a period of time in the Ambient state (Timeout #2), the system will typically return to the watch face.“ Ab Wear OS 5 verhindert eine Ongoing Activity das: „you can use the Ongoing Activity API to keep your app visible until the task ends.“ Ab Wear OS 6 mit targetSdk 36 oder höher gilt die App als always-on, und Updates kommen im Ambient-Modus „as infrequent as once per minute“.
  https://developer.android.com/training/wearables/views/always-on (abgerufen 2026-10-22)
- Für Trainings-Apps gilt: Häufig wechselnde Werte („heart rate, or workout distance“) werden im Ambient-Modus zwischen den Updates veraltet und sollen durch Platzhalter wie `--` ersetzt werden. Health Services bündelt Daten im Ambient-Modus. Die Activity verarbeitet Ambient-Wechsel in Compose mit `LocalAmbientModeManager`.
  https://developer.android.com/training/wearables/views/always-on und https://developer.android.com/health-and-fitness/guides/health-services/active-data (abgerufen 2026-10-22)

- `POST_NOTIFICATIONS` (ab API 33): „Apps don't need to request the `POST_NOTIFICATIONS` permission in order to launch a foreground service. However, apps must include a notification when they start a foreground service“. Lehnt der Nutzer die Berechtigung ab, „they still see notices related to foreground services in the Task Manager but don't see them in the notification drawer.“
  https://developer.android.com/develop/ui/views/notifications/notification-permission (abgerufen 2026-10-22)

**Beobachtet**
- Im Spike (ohne Ongoing Activity, ohne Foreground Service) wurde die Activity etwa 1 min nach dem Ambient-Wechsel unsichtbar. Etwa 2 min später kam keine BLE-Notification mehr (polar-ble.md, Kriterium 4).

**Vermutung**
- Ob die Ongoing Activity auf dem Zifferblatt erscheint, wenn `POST_NOTIFICATIONS` abgelehnt ist, ist nicht belegt. Die App sollte die Berechtigung deshalb anfragen.
- Der Foreground Service hält den Prozess und damit die BLE-Verbindung am Leben. Die Ongoing Activity hält die Anzeige sichtbar. Beides zusammen sollte den Abbruch aus dem Spike verhindern. Das prüft die Validierung.
- Die BLE-Werte kommen auch im Ambient-Modus im Sekundentakt im Service an (Spike, Test 1). Gespeichert wird also lückenlos, nur die Anzeige zeigt im Ambient-Modus Platzhalter.

---

## Frage 4: BLE-Verbindung halten und wiederherstellen

**Belegt**
- `autoConnect = false`: „Try to connect to the peripheral device directly, and fail if the device isn't available. In case of disconnection, the GATT client doesn't automatically try to reconnect.“
- `autoConnect = true`: „In case of a disconnection initiated by the peripheral or because the peripheral is out of range, the GATT client automatically tries to reconnect when the peripheral is available.“
- „There is no restriction on connecting to a device while the app is in the background, although the connection is closed if your process is killed.“
- Um verbunden zu bleiben, nennt die Seite zwei Wege: `CompanionDeviceService` mit Erkennung, ob ein Gerät in Reichweite ist, oder ein Foreground Service vom Typ `connectedDevice`, der im Vordergrund gestartet wird.
  https://developer.android.com/develop/connectivity/bluetooth/ble/background (abgerufen 2026-10-22)
- Eine Verbindung wird mit `close()` auf dem `BluetoothGatt`-Objekt beendet. Im Beispiel hält ein Service die einzige Referenz auf `BluetoothGatt` und schließt sie.
  https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server (abgerufen 2026-10-22)
- **Ab API 37 deprecated:** Alle Varianten `connectGatt(Context, boolean autoConnect, …)` sind „Deprecated in API level 37. Use `connectGatt(BluetoothGattConnectionSettings, Executor, BluetoothGattCallback)`“. Die neue Klasse `BluetoothGattConnectionSettings` gibt es seit API 37. Ihr Builder hat `setAutoConnectEnabled(boolean)` („enable the automatic connection to remote device when It is available“), `setTransport(…)`, `setAutomaticMtuEnabled(…)` (Standard `true`), `setOpportunisticEnabled(…)` sowie RSSI- und Pathloss-Schwellen für Auto-Connect.
  https://developer.android.com/reference/android/bluetooth/BluetoothDevice und https://developer.android.com/reference/android/bluetooth/BluetoothGattConnectionSettings.Builder (abgerufen 2026-10-22)

**Beobachtet** (Spike, polar-ble.md)
- Nach dem Abnehmen sendet der H10 etwa 11 s lang den letzten HF-Wert ohne RR (Flags `0x00`), dann meldet die App `STATE_DISCONNECTED` (`status=257`).
- Mehrere nicht geschlossene `BluetoothGatt`-Instanzen erhalten alle dieselben Notifications.
- Der H10 ist mit der Uhr gebondet. Ein Kopplungsdialog erschien bei der ersten Verbindung.

**Vermutung**
- Mit genau einer Instanz und `autoConnect = true` nach einer Trennung sollte die Wiederverbindung ohne Nutzereingriff klappen, solange der Foreground Service läuft. Im Spike war das wegen der Mehrfachinstanzen nicht sauber prüfbar.
- Als Absicherung genügt ein Watchdog: Ist nach einer festen Zeit keine Verbindung da, wird die Instanz geschlossen und neu aufgebaut. Ein periodischer Scan ist laut Doku zu vermeiden und hier nicht nötig, weil Adresse und Bond bekannt sind.
- Auf API 33–36 wird weiter die alte `connectGatt`-Variante genutzt, auf API ≥ 37 die neue. Deprecated heißt nicht entfernt, die alte Variante funktioniert also weiter. Die Pixel Watch 2 läuft mit API 37.

### CompanionDeviceManager (CDM)

**Belegt**
- CDM sucht und koppelt Geräte über einen Systemdialog. Es „doesn't create connections on its own nor enable continuous scanning“. Nach der Kopplung kann die App mit `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` Foreground Services aus dem Hintergrund starten. Ab API 36 läuft die Erkennung, ob ein Gerät in Reichweite ist, über `startObservingDevicePresence(ObservingDevicePresenceRequest)`.
  https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing (abgerufen 2026-10-22)
- CDM braucht das System-Feature `FEATURE_COMPANION_DEVICE_SETUP`. Ein gebundener `CompanionDeviceService` erhöht die Prozesspriorität.
  https://developer.android.com/reference/android/companion/CompanionDeviceManager und https://developer.android.com/reference/android/companion/CompanionDeviceService (abgerufen 2026-10-22)
- „CompanionDeviceManager has certain limitations (such as limited filtering and no support for random MAC addresses)“.
  https://developer.android.com/develop/connectivity/bluetooth/ble/background (abgerufen 2026-10-22)

**Keine verlässliche Quelle**
- Ob CDM bzw. `CompanionDeviceService` auf Wear OS verfügbar ist. Eine Suchzusammenfassung behauptete, der Service sei „not available on Wear OS“. In der Klassenreferenz habe ich diese Aussage nicht gefunden. Prüfen ließe es sich am Gerät mit `adb shell pm list features | grep companion`.

**Vermutung**
- Für dieses Projekt bringt CDM keinen Vorteil: Die Verbindung wird nur während eines Trainings gebraucht, das Training wird immer aus der sichtbaren App gestartet, und der Foreground Service hält den Prozess. CDM wäre erst interessant, wenn die App auf das Erscheinen des H10 reagieren soll, z. B. mit einem automatischen Trainingsstart. Das steht nicht in spec.md.

---

## Frage 5: Datenfluss Service → Oberfläche

**Belegt**
- Im Android-BLE-Beispiel hält ein (gebundener) Service die Verbindung, und die Activity bindet sich daran.
  https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server (abgerufen 2026-10-22)
- Health Services empfiehlt, den Zustand im Foreground Service zu führen („Manage the workout state, sensor data, ongoing activity, and data with a foreground service.“) und mit Room zu speichern.
  https://developer.android.com/health-and-fitness/guides/health-services/active-data (abgerufen 2026-10-22)

**Vermutung**
- Ein app-weiter Zustandshalter mit `StateFlow`, den der Service schreibt und die Oberfläche liest, ist einfacher als Binder-Kommunikation. Wie er bereitgestellt wird, regelt das ADR zu Koin. Wie er gespeichert wird, regelt das ADR zu Room.

---

## Quellen (alle abgerufen 2026-10-22)
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/declare
- https://developer.android.com/develop/background-work/services/fgs/launch
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/health-and-fitness/guides/health-services/active-data
- https://developer.android.com/reference/kotlin/androidx/health/services/client/data/DataType
- https://developer.android.com/training/wearables/versions/7/changes
- https://developer.android.com/training/wearables/ongoing-activity
- https://developer.android.com/training/wearables/views/always-on
- https://developer.android.com/develop/ui/views/notifications/notification-permission
- https://developer.android.com/develop/connectivity/bluetooth/ble/background
- https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server
- https://developer.android.com/reference/android/bluetooth/BluetoothDevice
- https://developer.android.com/reference/android/bluetooth/BluetoothGattConnectionSettings
- https://developer.android.com/reference/android/bluetooth/BluetoothGattConnectionSettings.Builder
- https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing
- https://developer.android.com/reference/android/companion/CompanionDeviceManager
- https://developer.android.com/reference/android/companion/CompanionDeviceService
