# Spec: heartrun

Stand: 2026-10-09

## Ziel
Eine persönliche Lauf- und Trainings-App für Phone und Wear OS. Der Polar H10 liefert die Herzfrequenz, die Uhr zeigt sie während des Trainings live an. Nur für den Eigengebrauch, kein Store.

## Geräte
- Phone: Google Pixel 8, Android 17
- Uhr: Pixel Watch 2
- Sensor: Polar H10 (Brustgurt, Bluetooth Herzfrequenzprofil)

## Priorität 1 (MVP)
- Trainingseinheit auf der Uhr starten und beenden. Sportart wählbar (z. B. Laufen, Radfahren).
- Live-Anzeige auf der Uhr, Seite 1: Distanz, Uhrzeit, aktuelle Pace, aktuelle Herzfrequenz.
- Live-Status des Sensors auf der Uhr sichtbar (verbunden, getrennt, Suche).
- Speicherung der Einheit auf der Uhr, auch ohne Handy-Verbindung.
- Übertragung der Einheiten vom Uhr zum Handy, sobald Verbindung besteht.
- Historie aller Einheiten auf dem Handy.
- Zusammenfassung pro Einheit auf dem Handy (Dauer, Distanz, Ø-Herzfrequenz, Ø-Pace).
- Export nach Health Connect, von dort kann Strava die Einheit übernehmen.

## Priorität 2
- Uhr, Seite 2: Ø-Herzfrequenz und Ø-Pace während des Trainings.
- Herzfrequenzverlauf in der Zusammenfassung als Diagramm.

## Priorität 3 (später)
- Route (GPS) in Historie und Zusammenfassung.
- Herzfrequenzzonen.
- Kalorien.

## Nicht im Umfang
- Lokale KI-Modelle.
- Strava-API direkt (Umweg über Health Connect).
- Import von Daten aus Polar-Flow oder AccessLink.
- Veröffentlichung im Play Store.

## Offene Fragen
- Ob Health Services den H10 als externe Herzfrequenzquelle akzeptiert. Wird durch die Recherche in docs/research/polar-ble.md geklärt.
- Ob die Pace auf der Uhr aus GPS oder aus Health Services kommt.