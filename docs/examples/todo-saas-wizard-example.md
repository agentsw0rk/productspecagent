# Beispielprojekt fuer den Wizard: TodoFlow SaaS

Dieses Dokument ist eine ausfuellbare Vorlage fuer ein Beispielprojekt im Product Spec Agent Wizard. Es beschreibt eine Todo-App als SaaS-Applikation und ist so formuliert, dass die Inhalte direkt in die Wizard-Felder kopiert werden koennen.

## Projektanlage

**Projektname**

TodoFlow SaaS

**Kurzidee**

TodoFlow SaaS ist eine webbasierte Todo-App fuer kleine Teams, die Aufgaben, Prioritaeten und einfache Wochenplanung in einer gemeinsamen Arbeitsflaeche verbindet.

## Schritt 1: Idee

**Produktname**

TodoFlow SaaS

**Produktidee / Vision**

TodoFlow SaaS ist eine schlanke Aufgabenmanagement-Anwendung fuer kleine Teams, Freelancer und Gruender. Nutzer koennen Aufgaben erfassen, priorisieren, faelligkeitsbasiert planen und in einfachen Team-Listen gemeinsam bearbeiten. Die App soll bewusst weniger komplex sein als klassische Projektmanagement-Tools und den Fokus auf klare Tages- und Wochenplanung legen.

**Kategorie**

SaaS

## Schritt 2: Problem & Zielgruppe

**Kernproblem**

Kleine Teams verlieren Aufgaben oft zwischen Chat-Nachrichten, Notizen und persoenlichen Listen. Dadurch fehlen klare Verantwortlichkeiten, Faelligkeiten und ein gemeinsames Bild davon, was heute, diese Woche und als Naechstes wichtig ist.

**Primaere Zielgruppe**

Kleine SaaS-Teams, Freelancer und Gruenderteams mit 2 bis 10 Personen, die eine einfache gemeinsame Aufgabenliste brauchen, aber kein vollstaendiges Projektmanagement-System einfuehren wollen.

**Pain Points**

- Aufgaben liegen in zu vielen Tools verteilt.
- Niemand sieht schnell, was heute wirklich wichtig ist.
- Faelligkeiten und Verantwortlichkeiten werden vergessen.
- Bestehende Projektmanagement-Tools sind fuer kleine Teams zu schwergewichtig.
- Persoenliche Todo-Listen lassen sich schlecht im Team teilen.

## Schritt 3: Features

### Feature 1: Aufgaben erfassen

**Titel**

Aufgaben erfassen

**Beschreibung**

Nutzer koennen neue Aufgaben mit Titel, Beschreibung, Prioritaet, Faelligkeitsdatum und optionalem Verantwortlichen anlegen. Die Aufgabe erscheint sofort in der Team-Liste und kann spaeter bearbeitet oder abgeschlossen werden.

**Scopes**

Frontend, Backend

**Akzeptanzkriterien**

- Eine Aufgabe kann mit Titel gespeichert werden.
- Prioritaet kann auf Niedrig, Normal oder Hoch gesetzt werden.
- Ein Faelligkeitsdatum kann optional gesetzt werden.
- Neu angelegte Aufgaben erscheinen sofort in der Aufgabenliste.

### Feature 2: Aufgabenliste und Filter

**Titel**

Aufgabenliste und Filter

**Beschreibung**

Nutzer sehen alle offenen Aufgaben in einer scanbaren Liste und koennen nach Status, Prioritaet, Faelligkeit und Verantwortlichem filtern. Erledigte Aufgaben bleiben abrufbar, dominieren aber nicht die Hauptansicht.

**Scopes**

Frontend, Backend

**Akzeptanzkriterien**

- Offene Aufgaben werden standardmaessig angezeigt.
- Nutzer koennen erledigte Aufgaben ein- und ausblenden.
- Filter fuer Prioritaet und Faelligkeit sind kombinierbar.
- Die Liste bleibt auch bei vielen Aufgaben gut lesbar.

### Feature 3: Tages- und Wochenplanung

**Titel**

Tages- und Wochenplanung

**Beschreibung**

Nutzer koennen Aufgaben fuer Heute, Diese Woche oder Spaeter einordnen. Eine kompakte Planungsansicht zeigt, welche Aufgaben kurzfristig relevant sind.

**Scopes**

Frontend, Backend

**Akzeptanzkriterien**

- Aufgaben koennen einer Planungsgruppe zugeordnet werden.
- Die Ansicht zeigt separate Bereiche fuer Heute, Diese Woche und Spaeter.
- Ueberfaellige Aufgaben werden deutlich markiert.
- Nutzer koennen Aufgaben zwischen Planungsgruppen verschieben.

### Feature 4: Team-Zugriff

**Titel**

Team-Zugriff

**Beschreibung**

Ein Nutzer kann ein Team erstellen und weitere Mitglieder per E-Mail einladen. Teammitglieder sehen dieselben Aufgaben und koennen Aufgaben gemeinsam bearbeiten.

**Scopes**

Frontend, Backend

**Akzeptanzkriterien**

- Ein Team kann erstellt werden.
- Mitglieder koennen per E-Mail eingeladen werden.
- Eingeladene Nutzer koennen nach Registrierung auf Team-Aufgaben zugreifen.
- Aufgaben bleiben teambezogen voneinander getrennt.

### Feature-Abhaengigkeiten

- Aufgabenliste und Filter haengt von Aufgaben erfassen ab.
- Tages- und Wochenplanung haengt von Aufgaben erfassen ab.
- Team-Zugriff kann nach Aufgaben erfassen und Aufgabenliste umgesetzt werden.

## Schritt 4: MVP

**MVP-Ziel**

Das MVP soll es einem kleinen Team ermoeglichen, Aufgaben gemeinsam zu erfassen, zu priorisieren, nach Faelligkeit zu planen und erledigte Aufgaben nachvollziehbar abzuschliessen.

**MVP-Features**

- Aufgaben erfassen
- Aufgabenliste und Filter
- Tages- und Wochenplanung

**Nicht im MVP**

- Wiederkehrende Aufgaben
- Kalender-Synchronisation
- Mobile Apps
- Kommentare und Dateianhaenge
- Komplexe Rollen- und Rechteverwaltung
- Abrechnung und Zahlungsintegration

**Erfolgskriterien**

- Ein Nutzer kann sich registrieren und eine Aufgabenliste anlegen.
- Eine Aufgabe kann in weniger als 15 Sekunden erstellt werden.
- Nutzer sehen sofort, welche Aufgaben heute faellig sind.
- Mindestens 80 Prozent der Testnutzer verstehen die Hauptansicht ohne Einweisung.
- Aufgabenstatus, Prioritaet und Faelligkeit bleiben nach Reload erhalten.

## Schritt 5: Design

**Designbeschreibung**

Die App soll ruhig, hell und produktiv wirken. Die Hauptansicht besteht aus einer linken Navigation, einer zentralen Aufgabenliste und einer kompakten Planungsleiste fuer Heute, Diese Woche und Spaeter. Die visuelle Sprache ist reduziert, mit klaren Statusmarkierungen, guter Lesbarkeit und wenigen Ablenkungen. Die Anwendung soll sich wie ein fokussiertes Arbeitswerkzeug anfuehlen, nicht wie eine Marketing-Seite.

**UI-Richtung**

- Helles Standard-Theme mit optionalem Dark Mode.
- Klare Listenansicht statt Kanban als Startpunkt.
- Prioritaeten als kleine farbige Marker.
- Faelligkeiten und Ueberfaelligkeit deutlich, aber nicht aggressiv darstellen.
- Primaere Aktion: Neue Aufgabe.

## Schritt 6: Architektur

**System-Architektur**

Monolith

**Datenbank**

PostgreSQL

**Deployment**

Docker

**Architektur-Notizen**

Das MVP kann als modularer Monolith umgesetzt werden. Zentrale Domaenen sind Auth, Teams, Aufgaben und Planung. PostgreSQL speichert Nutzer, Teams, Aufgaben, Status, Prioritaeten und Faelligkeiten. Docker Compose reicht fuer lokale Entwicklung und Demo-Deployments. Eine spaetere Skalierung kann ueber getrennte Module oder Services erfolgen, ist fuer das MVP aber nicht erforderlich.

## Schritt 7: Backend

**Sprache / Framework**

Kotlin + Spring

**API-Stil**

REST

**Auth-Methode**

JWT

**Backend-Notizen**

Das Backend stellt REST-Endpunkte fuer Registrierung, Login, Teamverwaltung und Aufgaben bereit. JWT sichert die Benutzer-Session. Im MVP reicht einfache teambezogene Zugriffskontrolle: Nutzer duerfen nur Aufgaben ihrer eigenen Teams lesen und bearbeiten.

**Wichtige API-Ressourcen**

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `GET /tasks`
- `POST /tasks`
- `PATCH /tasks/{id}`
- `POST /teams`
- `POST /teams/{id}/invites`

## Schritt 8: Frontend

**Framework**

Next.js + React

**UI Library**

shadcn/ui

**Styling**

Tailwind CSS

**Theme**

Both

**Frontend-Notizen**

Das Frontend nutzt eine App-Shell mit linker Navigation. Die wichtigste Seite ist die Aufgabenuebersicht mit schneller Erfassung, Filtern und Planungsgruppen. Formulare sollen kurz bleiben und direktes Feedback geben. Die erste Version optimiert Desktop und Tablet; Mobile muss nutzbar sein, aber ist nicht der Hauptfokus.

## Schritt 9: Review-Zusammenfassung

TodoFlow SaaS ist eine fokussierte Todo-App fuer kleine Teams. Das Produkt loest das Problem verstreuter Aufgaben und unklarer Prioritaeten durch eine gemeinsame Aufgabenliste mit Prioritaeten, Faelligkeiten und einfacher Tages- und Wochenplanung. Das MVP konzentriert sich auf Aufgaben erfassen, Aufgaben filtern und Aufgaben planen. Team-Zugriff ist wichtig, kann aber nach dem Kernflow ausgebaut werden.

## Typische Agenten-Rueckfragen und gute Antworten

**Ist TodoFlow SaaS ein finaler Produktname oder ein Arbeitstitel?**

TodoFlow SaaS ist ein Arbeitstitel, kann aber fuer das Beispielprojekt als Produktname verwendet werden.

**Wer ist die primaere Zielgruppe?**

Primaere Zielgruppe sind kleine Teams mit 2 bis 10 Personen, insbesondere Gruenderteams und Freelancer-Kollaborationen.

**Soll Team-Zugriff Teil des MVP sein?**

Team-Zugriff ist wichtig, aber das MVP soll zuerst den Kernflow Aufgaben erfassen, filtern und planen beweisen. Einfache Team-Zugehoerigkeit kann vorbereitet werden; Einladungen und Rollen koennen danach folgen.

**Soll die App eher Kanban oder Liste sein?**

Die Hauptansicht soll eine Liste sein. Kanban ist fuer spaetere Versionen denkbar, aber fuer das MVP nicht noetig.

**Welche Review-Signale sind fuer das Cockpit relevant?**

Relevant sind offene Akzeptanzkriterien, fehlende Team-Zugriff-Entscheidungen, noch nicht validierte Auth-Flows und fehlende Persistenztests fuer Aufgabenstatus und Faelligkeiten.

## Kurzer Copy-Paste-Start

Wenn du den Wizard schnell starten willst, nutze diese kompakte Version:

```text
TodoFlow SaaS ist eine schlanke Aufgabenmanagement-Anwendung fuer kleine Teams, Freelancer und Gruender. Nutzer koennen Aufgaben erfassen, priorisieren, faelligkeitsbasiert planen und in einfachen Team-Listen gemeinsam bearbeiten. Die App soll bewusst weniger komplex sein als klassische Projektmanagement-Tools und den Fokus auf klare Tages- und Wochenplanung legen.
```
