# Design: Open Knowledge Format als Source of Truth

**Status:** Freigegeben
**Datum:** 2026-07-17
**Bundle-Profil:** `productspecagent/1` auf Basis von OKF `0.1`

## Kontext

ProductSpecAgent speichert Features derzeit im Wizard-Zustand, Entscheidungen als
JSON und erzeugt Teile der Markdown-Dokumentation erst beim Export. Dadurch gibt
es mehrere fachliche Quellen und keine einheitliche, von Menschen und Agenten
gleichermassen bearbeitbare Wissensbasis.

Das unter `docs/examples/okf/` dokumentierte Open Knowledge Format (OKF) definiert
ein Bundle aus UTF-8-Markdown-Dateien mit YAML-Frontmatter, stabilen
bundle-relativen Konzeptpfaden, optionalen Indizes und toleranten Konsumenten.
ProductSpecAgent verwendet dieses Format mit einem strengeren Producer-Profil fuer
Features, Entscheidungen und Architekturdokumente.

## Ziele

- `docs/okf/` wird die kanonische Quelle fuer Features, Entscheidungen und
  Architekturdokumente eines migrierten Projekts.
- Ein verbundenes Git-Repository ermoeglicht bidirektionale Aenderungen durch
  ProductSpecAgent sowie durch Menschen und externe Werkzeuge.
- Jede agentengenerierte Aenderung wird als atomarer, validierter Changeset
  vorgeschlagen und vor dem Schreiben freigegeben.
- Stabile IDs entkoppeln interne Referenzen und UI-Zustand von Dateipfaden.
- Explorer, Export und Rete-Graph verwenden aus OKF erzeugte Lesemodelle.
- Bestehende Projekte koennen ueber einen expliziten, idempotenten Dry-Run
  migriert werden.

## Nicht-Ziele

- `docs/spec.md`, Tasks, Uploads und Design-Artefakte werden im ersten Rollout
  nicht nach OKF migriert.
- Der Python-Referenzagent, dessen Web-Crawler und dessen HTML-Viewer werden nicht
  in die Anwendung portiert.
- S3 bleibt keine zweite schreibbare fachliche Quelle.
- Gleichzeitige Aenderungen am selben Dokument werden nicht semantisch
  automatisch zusammengefuehrt.
- Agenten erhalten keinen direkten Datei-, Commit- oder Push-Zugriff.

## Zentrale Entscheidungen

| Thema | Entscheidung |
|---|---|
| Rolle von OKF | Source of Truth, nicht nur Exportformat |
| Bundle-Root | `docs/okf/` |
| Git-Autoritaet | Branch `productspec/okf` im verbundenen Repository |
| Interne Links | Ausschliesslich dateirelativ |
| Identitaet | Unveraenderliche UUID im Frontmatter-Feld `id` |
| Architektur | Atomare, spezialisierte Dokumenttypen |
| Generierung | Deterministisches Overview plus freizugebende Agentenvorschlaege |
| Freigabe | Atomarer Changeset mit zusammenhaengendem Diff |
| Konflikte | Andere Dateien automatisch integrieren, gleiche Datei blockieren |
| Migration | Projektbezogener Dry-Run mit bewusstem Cutover |
| S3 | Cache, Sync-Zustand und abgeleitete Lesemodelle |

## ProductSpecAgent-OKF-Profil

### Bundle-Kopf

Der Root-Index `docs/okf/index.md` deklariert OKF-Version und Profil:

```yaml
---
okf_version: "0.1"
profile: "productspecagent/1"
---
```

Unterverzeichnisse koennen eigene `index.md`-Dateien besitzen. Indizes werden
deterministisch aus dem Bundle erzeugt und im selben Changeset wie die
Konzeptaenderung aktualisiert. Ein Sprachmodell erzeugt keine Indexstruktur.
Ein `log.md` wird im ersten Rollout nicht erzeugt, weil Git bereits die
autoritative Aenderungshistorie liefert.

### Gemeinsames Frontmatter

Jedes fachliche Dokument muss folgende Felder besitzen:

```yaml
---
type: feature
id: 550e8400-e29b-41d4-a716-446655440000
title: Benutzeranmeldung
description: Nutzer koennen sich sicher am System anmelden.
tags: [authentication]
timestamp: 2026-07-17T12:00:00+02:00
---
```

Regeln:

- `type` ist ein bekannter Profiltyp oder ein unbekannter OKF-Erweiterungstyp.
- `id` ist eine innerhalb des Bundles eindeutige UUID und bleibt bei Rename oder
  Move unveraendert.
- `title` ist ein nicht leerer Anzeigename.
- `description` ist genau ein kurzer Satz.
- `tags` ist immer eine YAML-Liste, auch wenn sie leer ist.
- `timestamp` ist ein ISO-8601-Zeitpunkt und wird nur bei fachlicher Aenderung
  aktualisiert.
- `resource` und weitere Erweiterungsfelder sind optional.
- Unbekannte Felder werden beim Roundtrip in ihrer Reihenfolge erhalten.

### Pfade und Typen

| Pfad | `type` |
|---|---|
| `features/*.md` | `feature` |
| `decisions/*.md` | `decision` |
| `architecture/overview.md` | `architecture-overview` |
| `architecture/components/*.md` | `architecture-component` |
| `architecture/interfaces/*.md` | `architecture-interface` |
| `architecture/data-models/*.md` | `architecture-data-model` |
| `architecture/constraints/*.md` | `architecture-constraint` |

Dateinamen sind lesbare, lowercase Slugs. Der Pfad bleibt die OKF-Konzept-ID;
ProductSpecAgent-APIs, Rete-Knoten und Sync-Zustaende verwenden dagegen die UUID.

### Inhaltsvertraege

Feature-Dokumente verwenden die Abschnitte:

- `Problem`
- `Outcome`
- `Acceptance Criteria`
- `Relationships`

Akzeptanzkriterien sind Markdown-Checkboxen. Die typisierte Projektion liefert
unabhaengig von Legacy-Eingaben immer ein Array. Der OKF-Parser extrahiert die
Texte als Liste; die Anwendungsprojektion erzeugt daraus das bestehende Modell
`AcceptanceCriterion[]`. Skalare Legacy-Werte werden nur an der Migrations- oder
Kompatibilitaetsgrenze normalisiert.

Decision-Dokumente verwenden:

- `Context`
- `Decision`
- `Consequences`
- `Alternatives`
- `Relationships`

Architekturdokumente verwenden je nach Typ `Responsibility` oder `Scope` sowie:

- `Details`
- `Constraints`
- `Relationships`

Interne Beziehungen werden als standardisierte Markdown-Links notiert:

```markdown
## Relationships

- depends-on: [Login API](../architecture/interfaces/login-api.md)
```

Interne Ziele muessen relativ zur aktuellen Datei innerhalb von `docs/okf/`
liegen. Externe URLs sind nur in `resource` oder unter `Citations` erlaubt.

Typspezifische Maschinenmetadaten wie Feature-Scopes oder Decision-Status werden
als Profilerweiterungen im Frontmatter gespeichert. Fachlicher Fliesstext bleibt
im Body. Derselbe fachliche Wert darf nicht gleichzeitig in Frontmatter und Body
als zwei unabhaengige Quellen modelliert werden. Die exakten Erweiterungsfelder
und ihre Abbildung auf die bestehenden Domain-Modelle werden im
Implementierungsplan festgelegt.

## Zielarchitektur

Alle OKF-Zugriffe laufen ueber eine gemeinsame Backend-Grenze:

```text
Wizard / Agenten / Git-Remote
            |
            v
      OkfChangesetService
            |
      Validierung + Diff
            |
      Nutzerfreigabe
            |
            v
       GitOkfRepository  ---> productspec/okf:docs/okf/
            |
            v
     OkfProjectionService
       |        |        |
    Explorer   Rete    Export/Wizard
```

### Backend-Verantwortlichkeiten

- `OkfParser` liest sicheres YAML-Frontmatter und Markdown und unterstuetzt einen
  verlustfreien Roundtrip unbekannter Felder.
- `OkfProfileValidator` trennt OKF-Core-Gueltigkeit von den strengeren
  ProductSpecAgent-Profilregeln.
- `OkfLinkResolver` loest nur normalisierte, bundle-interne relative Pfade auf.
- `GitOkfRepository` verwaltet isolierte Checkouts, Fetch, Commit, Push und den
  aktuellen Commit-SHA.
- `OkfChangesetService` erzeugt Vorschauen, validiert, integriert unabhaengige
  Aenderungen und verwaltet Konflikte.
- `OkfProjectionService` erzeugt typisierte Lesemodelle fuer bestehende
  Anwendungsfaelle.
- `OkfMigrationService` erzeugt Dry-Runs, fuehrt die Migration idempotent aus und
  schaltet Projekte kontrolliert um.

Fuer YAML wird ein sicherer Parser mit Limits fuer Aliase, Verschachtelung,
Codepoints, doppelte Schluessel und rekursive Schluessel verwendet. Es darf keine
beliebige Klasseninstanziierung aus YAML geben.

## Git und Synchronisation

### Autoritaet und Arbeitskopie

- Git auf `productspec/okf` ist fuer `docs/okf/` autoritativ.
- Jedes Projekt besitzt eine isolierte Arbeitskopie; Arbeitsverzeichnisse werden
  nicht zwischen Projekten oder Requests geteilt.
- S3 speichert Remote-Konfiguration, letzten bestaetigten Commit-SHA, Sync-Status
  und abgeleitete Lesemodelle.
- Git-Zugangsdaten werden in einer Secret-Ablage gehalten und weder im Bundle noch
  im S3-Projektobjekt im Klartext gespeichert.
- Repository-Handles und andere JGit-Ressourcen werden nach jeder Operation
  geschlossen.

### Eingehender Sync

1. Remote-Branch fetchen.
2. Bundle mit dem sicheren Parser laden.
3. Core, Profil, IDs, Pfade und Beziehungen validieren.
4. Neue Projektionen fuer Explorer, Wizard, Rete und Export erzeugen.
5. Commit-SHA und Sync-Status erst nach erfolgreicher Verarbeitung aktualisieren.

Unbekannte Typen und Felder bleiben erhalten. Ein core-gueltiges, aber
profilwidriges Dokument wird im Explorer als `Needs attention` dargestellt und
nicht in ein typisiertes Fachmodell projiziert. Defektes YAML und unsichere Pfade
blockieren das betroffene Dokument. Nicht aufloesbare interne Links erzeugen eine
sichtbare Warnung.

### Ausgehender Sync

Ein Changeset besteht aus `add`, `update`, `rename` und `delete`, besitzt eine
idempotente ID und referenziert den Basis-Commit.

1. Wizard oder Agent liefert einen strukturierten Vorschlag.
2. Der Server rendert Markdown, aktualisiert Indizes und validiert das gesamte
   Ergebnis-Bundle.
3. Die UI zeigt alle Datei-Diffs als eine atomare Einheit.
4. Vor der Freigabe wird der Remote-Stand erneut geprueft.
5. Nicht ueberlappende Datei-Aenderungen werden integriert.
6. Bei Aenderungen derselben Datei wird ein Drei-Wege-Konflikt aus Basis, Remote
   und lokalem Vorschlag erzeugt.
7. Erst nach Freigabe werden Dateien geschrieben, committed und gepusht.

Ein fehlgeschlagener Push behaelt den Changeset fuer einen erneuten Versuch.
Projektbezogene Locks verhindern parallele Schreiboperationen. Sichtbare
Sync-Zustaende sind `Clean`, `Incoming`, `Proposal pending`, `Conflict`,
`Invalid remote` und `Push failed`.

## Agenten-Governance

Agenten duerfen:

- Bundle und einzelne Dokumente lesen,
- nach Typ, ID, Tag und Text suchen,
- strukturierte Dokumentaenderungen als Changeset vorschlagen.

Agenten duerfen nicht:

- direkt in eine Arbeitskopie schreiben,
- die Validierung umgehen,
- einen Changeset freigeben,
- Git-Commits oder Pushes ausloesen.

Fuer Architektur erzeugt das System aus den vorhandenen Feldern `architecture`,
`database`, `deployment` und `notes` deterministisch genau ein
`architecture-overview`. Weitere Components, Interfaces, Data Models und
Constraints sind Agentenvorschlaege, die nur bei vorhandener Evidenz aus Features
oder Entscheidungen und nach Nutzerfreigabe geschrieben werden.

## API- und UI-Vertraege

Die API muss folgende Verantwortlichkeiten anbieten; konkrete Routen werden im
Implementierungsplan festgelegt:

- OKF- und Git-Sync-Status lesen,
- Dokumente und typisierte Projektionen abfragen,
- eingehenden Sync ausloesen,
- Changeset-Vorschau erzeugen und freigeben,
- Drei-Wege-Konflikt lesen und aufloesen,
- Migrations-Dry-Run erzeugen und Migration ausfuehren.

Im Frontend:

- erscheint `docs/okf/` als Bundle-Root im bestehenden Explorer,
- zeigt die Kopfzeile den Sync-Zustand,
- besitzt ein Changeset eine gemeinsame Diff- und Freigabeansicht,
- verwenden Rete-Knoten die stabile Dokument-ID,
- werden Pfade nur fuer Anzeige, Navigation und Markdown-Links genutzt,
- verarbeitet die Feature-Projektion Akzeptanzkriterien immer als Liste.

Rete-Positionen sind kein fachlicher Bestandteil eines Features. Sie duerfen als
verwerfbarer Praesentations-Cache, nach Dokument-ID indiziert, in S3 liegen. Bei
fehlendem Cache wird das Layout neu berechnet; daraus entsteht kein OKF-Commit.

## Migration

### Projektzustaende

- `LEGACY`: Bestehende JSON- und Wizard-Daten sind autoritativ.
- `MIGRATION_READY`: Dry-Run und Vorschau sind vorhanden, aber ohne Cutover.
- `OKF_ACTIVE`: Git und `docs/okf/` sind die einzige fachliche Quelle fuer die
  migrierten Artefakte.

### Dry-Run-Bericht

Der Bericht enthaelt fuer jedes Quellobjekt:

- Quelltyp und Quell-ID,
- geplanten Zielpfad und uebernommene UUID,
- normalisierte Felder,
- ID- und Pfadkollisionen,
- nicht abbildbare Inhalte,
- Linkwarnungen,
- den vollstaendigen Changeset-Diff.

### Cutover

1. `productspec/okf` vom konfigurierten Ausgangsstand erstellen.
2. Root-Index und Feature-Dokumente erzeugen.
3. Decision-JSON in Decision-Dokumente ueberfuehren.
4. Ein deterministisches Architecture Overview erzeugen.
5. Optionale atomare Architekturdokumente als Vorschlaege aufnehmen.
6. Bundle validieren, committen und pushen.
7. Bundle erneut aus Git lesen und Projektionen mit der Vorschau vergleichen.
8. Nur nach erfolgreichem Roundtrip auf `OKF_ACTIVE` umschalten.

Die Ausfuehrung ist idempotent. Legacy-Daten bleiben zunaechst als unveraenderter
Ruecksetz-Snapshot erhalten, werden aber nicht dual weitergeschrieben. Nach neuen
OKF-Commits erfolgt eine Ruecknahme ueber Git und nicht ueber veraltete JSON-Daten.

## Rollout

Jeder nutzbare vertikale Schnitt enthaelt bereits eingehenden und ausgehenden
Git-Sync fuer die darin aktivierte Dokumentart:

1. OKF-Core, Git-Anbindung und Feature-Migration mit bidirektionalem Sync
2. Decisions mit demselben Changeset- und Sync-Modell
3. Architecture Overview und freizugebende atomare Architekturdokumente
4. Agentenwerkzeuge, Explorer, Export und Rete-Projektion vervollstaendigen
5. Legacy-Schreibpfade nach einer Stabilisierungsphase entfernen

Die Funktion wird projektbezogen aktiviert. Ein Projekt wird nie teilweise auf
OKF umgeschaltet, bevor sein Git-Roundtrip erfolgreich war.

### Planungsgrenze

Dieses Dokument ist die verbindliche Umbrella-Spezifikation fuer alle Schnitte,
aber kein Auftrag fuer eine einzige, zusammenhaengende Implementierung. Jeder der
oben genannten Schnitte erhaelt einen eigenen Implementierungsplan und wird
separat verifiziert. Der unmittelbar folgende Plan umfasst nur Schnitt 1:
OKF-Core, Git-Anbindung, Feature-Profil, Feature-Migration und den
bidirektionalen Feature-Sync einschliesslich Explorer- und Rete-Projektion.
Decision-, Architecture- und erweiterte Agenten-Schreibpfade bleiben darin
ausserhalb des Scopes.

## Fehlerbehandlung und Sicherheit

- Parserfehler enthalten Dokumentpfad und, soweit verfuegbar, Zeile und Spalte.
- Profilfehler blockieren lokale Changesets vor dem Schreiben.
- Core-gueltige Erweiterungen werden nicht verworfen oder ueberschrieben.
- Pfadauflosung verhindert absolute Pfade, `..` ausserhalb des Bundles und
  symbolische Links aus dem Checkout heraus.
- Rename wird ueber die stabile ID erkannt und zusammen mit Link- und
  Indexaktualisierungen atomar behandelt.
- Ein invalides Remote-Dokument wird nie durch eine lokale Projektion still
  korrigiert oder ersetzt.
- Netzwerk- und Push-Fehler veraendern den bestaetigten Basis-Commit nicht.

## Verifikation

### Unit- und Contract-Tests

- Frontmatter- und Body-Roundtrip einschliesslich unbekannter Felder
- Pflichtfelder, UUID-Eindeutigkeit, Tags-Liste und ISO-8601-Zeitpunkt
- sichere YAML-Limits und Schutz vor Path Traversal
- relative Links, Renames und stabile IDs
- deterministische Indexerzeugung
- Profilfehler gegenueber tolerierten Core-Erweiterungen
- skalare, objektartige und listenfoermige Legacy-Akzeptanzkriterien

### Integrationstests

- Fetch, Commit und Push mit temporaeren Bare-Repositories
- Remote-Aenderung an einer anderen Datei wird integriert
- Remote-Aenderung an derselben Datei erzeugt einen Konflikt
- Push-Fehler behaelt den freigegebenen Changeset
- Migrations-Dry-Run und Ausfuehrung sind idempotent
- Cutover erfolgt nur nach erfolgreichem Git-Roundtrip

### End-to-End-Tests

- migriertes Feature im Explorer und Rete-Graph anzeigen und auswaehlen
- externe Git-Aenderung synchronisieren und in der UI anzeigen
- Agentenvorschlag pruefen, freigeben, pushen und erneut projizieren
- Konflikt anzeigen und nach Nutzeraufloesung fortsetzen

## Erfolgskriterien

- Ein migriertes Projekt kann Features, Entscheidungen und Architekturwissen aus
  `docs/okf/` ohne Legacy-Lesezugriff darstellen.
- Manuelle Git-Aenderungen erscheinen nach einem Sync in ProductSpecAgent.
- Freigegebene UI- oder Agentenaenderungen erscheinen als nachvollziehbarer
  Commit auf `productspec/okf`.
- Nicht ueberlappende Aenderungen gehen nicht verloren; gleiche-Datei-Konflikte
  werden nie still ueberschrieben.
- Rete kann jedes projizierte Feature auch bei migrierten Legacy-Daten ohne
  Laufzeitfehler auswaehlen.

## Verworfene Alternativen

- **OKF nur als Export:** Loest die mehrfachen fachlichen Quellen nicht.
- **S3 als Autoritaet mit Git-Spiegel:** Erzeugt Dual-Write- und
  Konfliktprobleme.
- **Vollautomatische Architekturzerlegung:** Kann unbelegte Komponenten und
  Schnittstellen erfinden.
- **Freigabe je Dokument:** Kann zusammengehoerende Links und Dateien
  inkonsistent zuruecklassen.
- **Agent schreibt und Nutzer revertiert:** Ist fuer eine Source of Truth zu
  wenig kontrolliert.
- **Semantisches Auto-Merge derselben Datei:** Ist fuer fachliche Entscheidungen
  und Architekturtexte nicht verlaesslich genug.
- **Big-Bang-Migration aller Dokumente:** Vergroessert Risiko und Umfang ohne
  Nutzen fuer den ersten vertikalen Schnitt.

## Implementation Status

- Slice 1 complete: OKF Core, feature profile, Git sync, migration, Explorer/export, and Rete projection.
- Remaining: Decisions, Architecture, agent write tools, and final legacy cleanup.
