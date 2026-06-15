# Feature 22: Intelligenter FEATURES-Step mit DAG und Scope-Kontext

## Problem

Der aktuelle FEATURES-Step sammelt Features nur als flache Liste aus `{title, description, estimate}`. Damit fehlen zwei Dinge: (1) Abhaengigkeiten zwischen Features koennen nicht erfasst werden, weshalb die von Feature 21 erzeugten EPIC-Tasks keine realen `dependencies` tragen und der generierte `docs/features/00-feature-set-overview.md` naiv `"Feature N-1"` rendert statt echter Abhaengigkeitsrelationen. (2) Die Erfassung ist scope-blind — ein Frontend-only-Feature bekommt dieselben Felder wie ein Backend-Feature, obwohl die relevanten Design-Aspekte (UI-Komponenten vs. API-Endpunkte) sich fundamental unterscheiden. Der Plan-Generator hat dadurch keine Scope-Information und produziert generische Stories statt scope-spezifischer Arbeit.

## Ziel

Den FEATURES-Step zu einem intelligenten Graph-Editor ausbauen, der Abhaengigkeiten explizit modelliert, pro Feature scope-spezifische Felder erfasst und vom Agent aktiv befuellt und validiert wird.

1. **DAG-Editor mit Rete.js** — Features sind Nodes, Dependencies sind gerichtete Kanten, Zyklen werden verhindert, Layout persistiert
2. **Scope-Flags pro Feature** — Jedes Feature traegt ein `Set<FeatureScope>` aus `FRONTEND` und/oder `BACKEND` (bzw. leer bei Library); scope-spezifische Felder werden dynamisch ein- und ausgeblendet
3. **Feature-Proposal-Agent** — Ein Klick erzeugt initiale Feature-Liste + Dependencies aus Idea/Problem/Scope/MVP
4. **Validierungs-Clarifications** — Der bestehende `IdeaToSpecAgent` emittiert Clarifications bei isolierten Nodes, fehlenden Standard-Features oder Scope-Unstimmigkeiten
5. **Echte Dependencies in EPIC-Tasks** — Graph-Kanten werden in `SpecTask.dependencies` gemappt; `ScaffoldContextBuilder` rendert echte Feature-Titel statt `"Feature N-1"`
6. **Scope-aware Plan-Generator** — `PlanGeneratorAgent` erhaelt Scope + Scope-Felder und erzeugt passend fokussierte Stories (Frontend-Feature → UI-Stories, Backend → API-Stories)

## Voraussetzungen

| Abhaengigkeit                              | Status                                             | Blocker?                 |
|--------------------------------------------|----------------------------------------------------|--------------------------|
| Feature 11 (Guided Wizard Forms)           | Implementiert                                      | Nein                     |
| Feature 12 (Dynamic Wizard Steps)          | Implementiert                                      | Nein                     |
| Feature 17 (Resizable Sidebar)             | Implementiert (`ResizablePanelGroup` verfuegbar)   | Nein                     |
| Feature 18 (Step Blocker Gate)             | Implementiert                                      | Nein                     |
| Feature 20 (Spec-to-Docs Sync)             | Implementiert                                      | Nein                     |
| Feature 21 (Wizard Features → EPIC Tasks)  | Implementiert                                      | Nein — wird erweitert    |
| Rete.js (Node-Graph Visualisierung)        | Verfuegbar (bereits in `spec-flow/` verwendet)     | Nein                     |
| Dagre (`@dagrejs/dagre` fuer Auto-Layout)  | Geplant (`npm install @dagrejs/dagre`)             | Nein                     |

## Architektur

### Datenmodell

Der FEATURES-Step persistiert zwei Felder in `data.steps.FEATURES.fields`: `features` und `edges`. Stabile UUIDs sind Pflicht, damit Kanten nach Umbenennung eines Features weiter funktionieren.

```kotlin
// backend/src/main/kotlin/com/agentwork/productspecagent/domain/WizardFeatureGraph.kt
@Serializable
data class WizardFeatureGraph(
    val features: List<WizardFeature>,
    val edges: List<WizardFeatureEdge>,
)

@Serializable
data class WizardFeature(
    val id: String,                       // UUID, stabile Referenz fuer Kanten
    val title: String,
    val scopes: Set<FeatureScope>,        // leere Menge bei Library; {FRONTEND}, {BACKEND} oder beides
    val description: String,
    val scopeFields: Map<String, String>, // dynamische Felder je nach aktiven Scopes
    val position: GraphPosition,          // Rete.js Layout-Persistenz
)

@Serializable
data class WizardFeatureEdge(
    val id: String,
    val from: String,  // Source-Feature-ID (Dependency-Quelle)
    val to: String,    // Target haengt von Source ab
)

@Serializable
data class GraphPosition(val x: Double, val y: Double)

enum class FeatureScope { FRONTEND, BACKEND }
```

**Scope-spezifische Felder (gerendert ins `scopeFields`-Map):**

| Aktive Scopes                  | Sichtbare Felder                                              |
|--------------------------------|---------------------------------------------------------------|
| `{FRONTEND}`                   | `uiComponents`, `screens`, `userInteractions`                 |
| `{BACKEND}`                    | `apiEndpoints`, `dataModel`, `sideEffects`                    |
| `{FRONTEND, BACKEND}`          | Beide Sets zusammen                                           |
| `{}` (Library)                 | `publicApi`, `typesExposed`, `examples` (neutrale "Core"-Felder) |

**Kategorie-Gating** erweitert `frontend/src/lib/category-step-config.ts` um `allowedScopes`:

| Kategorie                        | Erlaubte Scopes pro Feature        | Picker-Verhalten              |
|----------------------------------|------------------------------------|-------------------------------|
| SaaS / Mobile App / Desktop App  | `FRONTEND`, `BACKEND`              | Multi-Select (beide moeglich) |
| API                              | `BACKEND` only                     | kein Picker, fest gesetzt     |
| CLI Tool                         | `BACKEND` only                     | kein Picker, fest gesetzt     |
| Library                          | — (keine Scopes)                   | kein Picker, "Core"-Felder    |

**Backward-Compat:** `parseWizardFeatures` in Feature 21 erkennt legacy `List<Map>`-Eintraege ohne `id`/`scopes` und migriert lazy: generiert UUID, leitet Default-Scopes aus der Projekt-Kategorie ab (SaaS/Mobile/Desktop → `{FRONTEND, BACKEND}`; API/CLI → `{BACKEND}`; Library → `{}`), `edges = []`. Keine Daten-Migration noetig — die Konversion geschieht beim ersten Read.

### Rete.js Visual Editor

Ein neuer Editor ersetzt `FeaturesForm.tsx` komplett. Layout ueber `ResizablePanelGroup` (shadcn, aus Feature 17):

- Links ~70 %: Rete.js-Canvas mit Feature-Nodes und Dependency-Kanten
- Rechts ~30 %: Feature-Detail-Panel fuer den aktiven Node

**Feature-Node (Rete.js Custom Control):**

```tsx
// frontend/src/components/wizard/steps/features/FeatureNode.tsx
<Node>
  <Header>
    <Title editable onDoubleClick={enterEditMode} />
    <ScopeBadges scopes={feature.scopes} />
    {/* Farben (aus aktuellem Design System):
        Frontend=Cyan, Backend=Violet, beide gesetzt=beide Badges nebeneinander,
        leer (Library) = neutrales "Core"-Badge */}
  </Header>
  <Port type="input"  label="depends on" />
  <Port type="output" label="required by" />
</Node>
```

**Kanten-Interaktion:**

- Drag Output-Port → Input-Port erzeugt Kante
- `lib/graph/cycleCheck.ts` fuehrt vor Anlegen einen DFS auf dem aktuellen Graph durch; wuerde die neue Kante einen Zyklus erzeugen, wird sie verworfen und ein Toast "Zyklus verhindert: A → B → A" angezeigt
- Rechtsklick auf Kante oeffnet Context-Menu mit "Loeschen"

**Buttons im Canvas-Footer:**

- `+ Feature` — neuer Node an freier Stelle, Fokus wandert ins Detail-Panel (Title-Input)
- `Features vorschlagen` — ruft `POST /features/propose` (nur aktiv bei leerem Graph oder mit Bestaetigungs-Dialog "ueberschreibt bestehenden Graph?")
- `Auto-Layout` — Dagre-basierte topologische Anordnung, Layer-X nach Graph-Tiefe, Layer-Y gleichmaessig

**Detail-Panel (scope-abhaengige Felder):**

```tsx
// frontend/src/components/wizard/steps/features/FeatureSidePanel.tsx
<Panel feature={active}>
  <FormField name="title" />
  {/* ScopePicker nur bei Kategorien mit wahlbaren Scopes (SaaS/Mobile/Desktop).
      API/CLI: Scope ist fix {BACKEND}, Picker unsichtbar.
      Library: gar kein Picker, "Core"-Felder unten. */}
  {allowedScopes.length > 1 && (
    <ScopeMultiSelect name="scopes" options={allowedScopes} />
  )}
  <FormField name="description" multiline />

  {active.scopes.has("FRONTEND") && (
    <Section title="Frontend">
      <FormField name="uiComponents"     multiline />
      <FormField name="screens"          multiline />
      <FormField name="userInteractions" multiline />
    </Section>
  )}

  {active.scopes.has("BACKEND") && (
    <Section title="Backend">
      <FormField name="apiEndpoints" multiline />
      <FormField name="dataModel"    multiline />
      <FormField name="sideEffects"  multiline />
    </Section>
  )}

  {active.scopes.size === 0 && category === "Library" && (
    <Section title="Core">
      <FormField name="publicApi"     multiline />
      <FormField name="typesExposed"  multiline />
      <FormField name="examples"      multiline />
    </Section>
  )}
</Panel>
```

Scope-Aenderung (Hinzufuegen oder Entfernen eines Scopes) blendet Felder nur aus, loescht aber **keine** Werte im `scopeFields`-Map — schaltet der User zurueck, sind die Eintraege wieder da.

**Persistenz-Verhalten:** Jede Aenderung (Node-Move, Kante, Feld-Edit) schreibt via `updateField("FEATURES", "features" | "edges", …)` in den Wizard-Store. Node-Position ist auf ~300 ms debounced, damit Drag-Moves nicht bei jedem Frame serialisieren.

### Feature-Proposal-Agent

Neuer Koog-Agent, der initiale Features aus den vorgelagerten Wizard-Specs ableitet.

```kotlin
// backend/src/main/kotlin/com/agentwork/productspecagent/agent/FeatureProposalAgent.kt
class FeatureProposalAgent(
    private val runner: KoogAgentRunner,
    private val specContextBuilder: SpecContextBuilder,
) {
    suspend fun proposeFeatures(projectId: String): WizardFeatureGraph {
        val context = specContextBuilder.buildProposalContext(projectId)
        // context enthaelt: idea.md, problem.md, target-audience.md, scope.md,
        // mvp.md, category, allowedScopes
        val raw = runner.run(PROPOSAL_SYSTEM_PROMPT, context)
        return parseProposalResponse(raw)
        // JSON: { features: [{title, scope, description, scopeFields}, ...],
        //        edges:    [{fromTitle, toTitle}, ...] }
    }
}
```

Der LLM-Prompt fordert strukturiertes JSON mit Titel, Beschreibung, Scope, scope-spezifischen Feldern und Dependencies als `(fromTitle, toTitle)`-Paare. Der Agent weist anschliessend stabile UUIDs zu, loest Title-Referenzen zu IDs auf und berechnet initiale Layout-Positionen per Dagre.

**Endpoint:**

```
POST /api/v1/projects/{projectId}/features/propose
→ 200 { features: [...], edges: [...] }
→ 422 { error: "Parsing fehlgeschlagen: ..." }
```

**Fallback:** Parsing-Fehler werfen `ProposalParseException`, Controller uebersetzt sie in 422. Frontend zeigt Toast "Vorschlag fehlgeschlagen, bitte manuell anlegen".

### Validator-Integration (Feature 18 Gate)

`SpecContextBuilder.buildWizardContext` bekommt einen neuen Graph-Block, sobald `currentStep == FEATURES`:

```
Features & Dependencies (Category: SaaS):
- [F-1] Login (Backend) — depends on: —
  API: POST /api/v1/auth/login, POST /api/v1/auth/logout
  Data: User, Session
- [F-2] Dashboard (Frontend) — depends on: F-1
  Screens: /dashboard, /dashboard/settings
- [F-3] User-Profile (Frontend + Backend) — depends on: F-1
  Screens: /profile
  API: GET /api/v1/auth/me, PATCH /api/v1/auth/me
Isolated nodes: —
```

Darstellung in Klammern fasst das `scopes`-Set zusammen: `(Frontend)`, `(Backend)`, `(Frontend + Backend)` bzw. `(Core)` fuer Library-Features (leeres Set).

Im System-Prompt wird eine zusaetzliche Regel ergaenzt (neben den bestehenden Marker-Regeln):

> Wenn isolierte Nodes existieren, ein Feature scope-inkonsistent wirkt (z.B. "Login UI" als `BACKEND`) oder offensichtlich ein Standard-Feature fehlt (z.B. SaaS ohne Auth), emittiere `[CLARIFICATION_NEEDED]: frage | grund`. Sonst keine Marker fuer den Graph.

Der in Feature 18 Runde 2 eingebaute `PREVIOUS CLARIFICATIONS & DECISIONS`-Block verhindert Endlos-Clarifications. Das bestehende Step-Blocker-Gate (Feature 18) greift automatisch auf die erzeugten Clarifications — **keine neue Gate-Logik noetig**.

### PlanGeneratorAgent — Scope-Awareness

`generatePlanForFeature` bekommt ein erweitertes `WizardFeatureInput`:

```kotlin
// backend/src/main/kotlin/com/agentwork/productspecagent/service/TaskService.kt
data class WizardFeatureInput(
    val id: String,                          // neu — fuer Dependency-Mapping
    val title: String,
    val description: String,
    val scopes: Set<FeatureScope>,           // neu — leer bei Library, sonst {FRONTEND}, {BACKEND} oder beides
    val scopeFields: Map<String, String>,    // neu
    val dependsOn: List<String>,             // neu — Wizard-Feature-IDs
)
```

Prompt-Erweiterungen im `PlanGeneratorAgent`:

- **Scope-Hint:** Wird aus `scopes` abgeleitet:
  - `{FRONTEND}` → *"Dieses Feature ist Frontend-only. Generiere ausschliesslich UI-bezogene Stories (Components, Screens, State, User-Interaktionen). Keine API- oder Datenbank-Stories."*
  - `{BACKEND}` → analog fuer API/Daten/Services
  - `{FRONTEND, BACKEND}` → kein Hint (beide Seiten relevant)
  - `{}` (Library) → *"Dieses Feature ist eine Library-Komponente. Fokussiere Stories auf Public API, Types und Usage-Examples."*
- **Scope-Felder-Block:** Als strukturierter Text im Prompt, damit Stories inhaltlich konkret werden statt generisch

**2-Phasen EPIC-Generation in `TaskService.replaceWizardFeatureTasks`:**

1. **Phase 1 — EPIC-Skelette:** Fuer jedes Wizard-Feature ein EPIC erzeugen (ohne `dependencies`-Feld). Baue Map `wizardFeatureId → epicTaskId`.
2. **Phase 2 — Dependency-Mapping:** Fuer jedes EPIC die `dependsOn`-IDs aus dem Wizard-Feature nehmen, ueber die Map in `epicTaskId`s uebersetzen und in `epic.dependencies` schreiben.
3. Persistieren ueber `TaskStorage` wie heute.

Stories/Tasks unter einem EPIC bekommen keine zusaetzlichen Dependencies — der User arbeitet im Graph auf Feature-Ebene, nicht auf Story-Ebene.

### Generierter Code (Docs-Scaffold, Feature 20)

`ScaffoldContextBuilder` rendert heute `"Abhaengig von: Feature N-1"` naiv per Index (offene Schuld aus Feature 21). Fix:

```kotlin
// backend/src/main/kotlin/com/agentwork/productspecagent/export/ScaffoldContextBuilder.kt
private fun buildDependenciesText(
    epic: SpecTask,
    idToTitle: Map<String, String>,
): String {
    if (epic.dependencies.isEmpty()) return ""
    return epic.dependencies
        .mapNotNull { idToTitle[it] }
        .joinToString(", ")
}
```

**Per-Feature-Docs** (`docs/features/NN-<slug>.md`) bekommen eine neue Scope-Sektion und dynamisch scope-spezifische Abschnitte:

```markdown
# Feature NN: {Titel}

**Scope:** Frontend  <!-- oder "Backend", oder "Frontend + Backend", oder "Core" bei Library -->

## Ziel
{EPIC-Beschreibung}

## Abhaengig von
{komma-separierte Feature-Titel} <!-- leer wenn keine Dependencies -->

## UI-Komponenten  <!-- nur wenn scopeFields.uiComponents nicht leer -->
{scopeFields.uiComponents}

## API-Endpunkte    <!-- nur wenn scopeFields.apiEndpoints nicht leer -->
{scopeFields.apiEndpoints}
...
```

Docs regenerieren sich automatisch bei `saveSpecFile` (Feature 20-Mechanismus, unveraendert).

### Mobile-Fallback

Unter 768 px Viewport zeigt `FeaturesFallbackList.tsx` eine klassische Liste mit Multi-Select fuer Dependencies pro Feature. Keine Rete.js-Initialisierung, kein Layout-Speicher. Das Daten-Format ist identisch — der User kann auf Desktop fortsetzen und den Graph dort sehen.

## Betroffene Dateien

### Backend

| Datei                                                              | Aenderung                                                    |
|--------------------------------------------------------------------|--------------------------------------------------------------|
| `domain/WizardFeatureGraph.kt`                                     | NEU — WizardFeature, WizardFeatureEdge, GraphPosition, FeatureScope |
| `agent/FeatureProposalAgent.kt`                                    | NEU — LLM-basierter Feature-Vorschlag                        |
| `api/FeatureProposalController.kt`                                 | NEU — `POST /api/v1/projects/{id}/features/propose`          |
| `agent/PlanGeneratorAgent.kt`                                      | Erweitertes `WizardFeatureInput`, scope-aware Prompt-Hints   |
| `service/TaskService.kt`                                           | 2-Phasen EPIC-Gen + Dependency-Mapping in `replaceWizardFeatureTasks` |
| `agent/SpecContextBuilder.kt`                                      | Neuer Graph-Block in Wizard-Context + Proposal-Context       |
| `agent/IdeaToSpecAgent.kt`                                         | Parse der Graph-Struktur aus FEATURES-Step (via parseWizardFeatures) |
| `export/ScaffoldContextBuilder.kt`                                 | Echte Dependencies + Scope-Felder in Docs-Rendering          |

### Frontend

| Datei                                                              | Aenderung                                                    |
|--------------------------------------------------------------------|--------------------------------------------------------------|
| `components/wizard/steps/FeaturesForm.tsx`                         | ERSETZT durch `FeaturesGraphEditor`                          |
| `components/wizard/steps/features/FeaturesGraphEditor.tsx`         | NEU — Hauptkomponente, ResizablePanelGroup-Layout            |
| `components/wizard/steps/features/FeatureNode.tsx`                 | NEU — Rete.js Custom Node mit Scope-Badge                    |
| `components/wizard/steps/features/FeatureSidePanel.tsx`            | NEU — Detail-Editor mit scope-abhaengigen Feldern            |
| `components/wizard/steps/features/FeaturesFallbackList.tsx`        | NEU — Mobile-Fallback (<768 px)                              |
| `lib/graph/topoLayout.ts`                                          | NEU — Dagre-Wrapper fuer Auto-Layout                         |
| `lib/graph/cycleCheck.ts`                                          | NEU — DFS-basierte Zyklus-Praevention                        |
| `lib/api.ts`                                                       | Neue Typen (`WizardFeature`, `WizardFeatureEdge`), `proposeFeatures()` |
| `lib/category-step-config.ts`                                      | Neues `allowedScopes` pro Kategorie                          |
| `lib/step-field-labels.ts`                                         | Neue scope-spezifische Feld-Labels                           |
| `lib/stores/wizard-store.ts`                                       | Graph-Actions: `addNode`, `removeNode`, `addEdge`, `removeEdge`, `moveNode` |
| `package.json`                                                     | `@dagrejs/dagre` Dependency                                  |

### Tests

| Datei                                                              | Aenderung                                                    |
|--------------------------------------------------------------------|--------------------------------------------------------------|
| `agent/FeatureProposalAgentTest.kt`                                | NEU — Mock-Runner, JSON-Parsing, Fallback-Pfad               |
| `api/FeatureProposalControllerTest.kt`                             | NEU — Endpoint, 200/422-Antworten                            |
| `service/TaskServiceWizardGraphTest.kt`                            | NEU — 2-Phasen-Gen, Dependency-Mapping, Idempotenz           |
| `agent/SpecContextBuilderGraphTest.kt`                             | NEU — Graph-Block-Rendering im Wizard-Context                |
| `agent/PlanGeneratorAgentScopeTest.kt`                             | NEU — Scope-Hints im Prompt, Frontend- vs. Backend-Variante  |
| `agent/IdeaToSpecAgentTest.kt`                                     | Erweitert — Graph-Input statt flacher Liste                  |
| `export/ScaffoldContextBuilderTest.kt`                             | Erweitert — echte Dependencies statt `"Feature N-1"`         |

## Akzeptanzkriterien

- [ ] User kann Feature-Nodes im Rete.js-Graph erstellen, loeschen und verschieben; Positionen werden persistiert
- [ ] Dependency-Kanten koennen per Drag zwischen Output- und Input-Port gezogen werden
- [ ] Zyklische Kanten werden client-seitig verhindert und ein Toast zeigt das Ergebnis
- [ ] Scope-Set (`{FRONTEND}`, `{BACKEND}`, `{FRONTEND, BACKEND}` oder leer) ist pro Feature waehlbar und wird ueber die Projekt-Kategorie gated (API/CLI: Scope fest `{BACKEND}`; Library: kein Picker, "Core"-Felder; SaaS/Mobile/Desktop: Multi-Select zwischen Frontend und Backend)
- [ ] Scope-spezifische Felder werden im Detail-Panel dynamisch ein- und ausgeblendet; Werte bleiben bei Scope-Wechsel im `scopeFields`-Map erhalten
- [ ] "Features vorschlagen"-Button erzeugt via `FeatureProposalAgent` eine initiale Feature-Liste + Dependencies aus Idea/Problem/Scope/MVP
- [ ] Bei leerem Graph ist "Weiter" geblockt mit Hinweis "Fuege mindestens ein Feature hinzu"
- [ ] Validator-Agent emittiert `[CLARIFICATION_NEEDED]`-Marker fuer isolierte Nodes, scope-inkonsistente Features oder fehlende Standard-Features
- [ ] Offene Clarifications blockieren den "Weiter"-Button ueber das bestehende Feature 18-Gate
- [ ] Generierte EPIC-Tasks tragen echte `dependencies` aus den Graph-Kanten
- [ ] `docs/features/00-feature-set-overview.md` zeigt echte Feature-Titel in der "Abhaengig von"-Spalte (nicht mehr `"Feature N-1"`)
- [ ] `docs/features/NN-<slug>.md` zeigen Scope-Badge und scope-spezifische Sektionen (nur wenn nicht leer)
- [ ] `PlanGeneratorAgent` erzeugt scope-fokussierte Stories (Frontend-Feature → UI-Stories, Backend → API-Stories)
- [ ] Alte Projekte ohne Graph-Struktur laden fehlerfrei (lazy-migriert zu 1 Node pro Legacy-Feature, keine Kanten)
- [ ] Mobile-Fallback (< 768 px Viewport) zeigt Listen-Ansicht mit Dependency-Multi-Select
- [ ] Bestehende Funktionalitaet bleibt unberuehrt (EPIC-Gen, Docs-Sync, Step-Blocker, andere Wizard-Steps)
