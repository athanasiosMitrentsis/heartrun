# Projekt: heartrun

Persönliche Android-App mit Wear-OS-Begleiter. Nur für den Eigengebrauch, kein Store.
Installation direkt aus Android Studio auf Pixel 8 (Android 17) und Pixel Watch 2.

## Ziel dieser Phase
Klären, wie die Wear-OS-Uhr die Herzfrequenz des Polar H10 per BLE empfängt.

## Module
- mobile: Phone-App
- wear: Wear-OS-App

## Stack (verbindlich)
- Kotlin, Jetpack Compose (Phone und Wear)
- Navigation 3
- Koin für Dependency Injection
- Room für lokale Speicherung (auch auf der Uhr)
- minSdk 30, targetSdk aktuelles Level

## Regeln
- Entscheidungen werden als ADR in docs/adr/ dokumentiert, bevor implementiert wird.
- Recherche-Ergebnisse stehen in docs/research/ mit Quell-URLs und Abrufdatum.
- Belegtes und Vermutetes wird klar getrennt. Ohne verlässliche Quelle wird das explizit vermerkt, statt zu raten.
- Keine Secrets ins Repo. Schlüssel gehören in local.properties oder secrets.properties (beide gitignored).
- Änderungen nur über Branches, nie direkt auf main.
- Der Agent merged nicht selbst. Merges macht der Mensch.
- Der Agent arbeitet nach docs/spec.md und den ADRs, nicht nach dem Chatverlauf.

## Dokumente
- docs/spec.md: Anforderungen
- docs/adr/: Architekturentscheidungen
- docs/research/: Recherche mit Quellen
