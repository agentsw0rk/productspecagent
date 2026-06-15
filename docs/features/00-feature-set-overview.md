# Feature Set: Product-Spec-Agent

Jedes Feature ist eine eigenständige, implementierbare Einheit. Die Reihenfolge ist so gewählt, dass jedes Feature auf dem vorherigen aufbaut.

## Reihenfolge

| # | Feature | Datei | Abhängig von | Aufwand |
|---|---------|-------|-------------|---------|
| 0 | Project Setup | [00-project-setup.md](00-project-setup.md) | — | L |
| 1 | Idea-to-Spec Flow | [01-idea-to-spec-flow.md](01-idea-to-spec-flow.md) | Feature 0 | XL |
| 2 | Guided Decisions | [02-guided-decisions.md](02-guided-decisions.md) | Feature 0, 1 | L |
| 3 | Clarification Engine | [03-clarification-engine.md](03-clarification-engine.md) | Feature 0, 1 | L |
| 4 | Spec + Plan + Tasks | [04-spec-plan-tasks.md](04-spec-plan-tasks.md) | Feature 0, 1 | XL |
| 5 | Git-Repository Output | [05-git-repository-output.md](05-git-repository-output.md) | Feature 0, 1, 4 | M |
| 6 | Beautiful UI | [06-beautiful-ui.md](06-beautiful-ui.md) | Feature 0 | L |
| 7 | Consistency Checks | [07-consistency-checks.md](07-consistency-checks.md) | Feature 0, 1, 4 | L |
| 8 | Agent-ready Handoff | [08-agent-ready-handoff.md](08-agent-ready-handoff.md) | Feature 0, 4, 5 | L |
| 9 | Spec File Explorer | [09-spec-file-explorer.md](09-spec-file-explorer.md) | Feature 0, 1, 6 | L |
| 10 | Project Scaffold Export | [10-project-scaffold-export.md](10-project-scaffold-export.md) | Feature 4, 5, 8 | M |
| 11 | Guided Wizard Forms | [11-guided-wizard-forms.md](11-guided-wizard-forms.md) | Feature 0, 1, 4, 6 | XL |
| 12 | Dynamische Wizard-Steps | [12-dynamic-wizard-steps.md](12-dynamic-wizard-steps.md) | Feature 11 | M |
| 13 | Wizard-Chat Integration | [13-wizard-chat-integration.md](13-wizard-chat-integration.md) | Feature 11, 1 | L |
| 14 | Agent Locale Detection | [14-agent-locale-detection.md](14-agent-locale-detection.md) | Feature 1 | S |
| 15 | Hide Internal Files in Explorer | [15-hide-internal-files-in-explorer.md](15-hide-internal-files-in-explorer.md) | Feature 9 | S |
| 16 | SPEC-Step entfernen | [16-remove-spec-step.md](16-remove-spec-step.md) | Feature 11, 12, 13 | M |
| 17 | Resizable Sidebar | [17-resizable-sidebar.md](17-resizable-sidebar.md) | Feature 6 | S |
| 18 | Step Blocker Gate | [18-step-blocker-gate.md](18-step-blocker-gate.md) | Feature 2, 3, 11 | M |
| 19 | Explorer Debug Toggle | [19-explorer-debug-toggle.md](19-explorer-debug-toggle.md) | Feature 9, 15 | S |
| 20 | Spec-to-Docs Sync | [20-spec-to-docs-sync.md](20-spec-to-docs-sync.md) | Feature 1, 4, 10, 11 | M |
| 21 | Wizard FEATURES → EPIC + Stories Tasks | [21-wizard-features-to-tasks-done.md](21-wizard-features-to-tasks-done.md) | Feature 4, 11, 20 | M |
| 22 | Intelligenter FEATURES-Step mit DAG | [22-features-graph-wizard.md](22-features-graph-wizard.md) | Feature 11, 12, 17, 18, 20, 21 | L |
| 23 | Project Create vereinfachen | [23-simplify-project-create.md](23-simplify-project-create.md) | Feature 1, 11, 12 | S |
| 24 | PROBLEM-Step „Auswirkung" entfernen | [24-remove-problem-impact-field.md](24-remove-problem-impact-field.md) | Feature 11 | XS |
| 25 | TARGET_AUDIENCE „TechLevel" entfernen | [25-remove-target-audience-techlevel-field.md](25-remove-target-audience-techlevel-field.md) | Feature 11 | XS |
| 26 | Merge PROBLEM + TARGET_AUDIENCE | [26-merge-problem-target-audience.md](26-merge-problem-target-audience.md) | Feature 24, 25 | M |
| 27 | SCOPE-Step entfernen, FEATURES vor MVP | [27-remove-scope-merge-into-features.md](27-remove-scope-merge-into-features.md) | Feature 11, 22, 26 | M |
| 28 | Projekt-Dokumente in GraphMesh | [28-project-document-upload.md](28-project-document-upload.md) | Feature 0, 1 | M |
| 29 | Projekt aus Übersicht löschen | [29-project-delete.md](29-project-delete.md) | Feature 0 | S |
| 30 | Statische CLAUDE.md mit Sync-URL im Handoff | [30-handoff-url-sync.md](30-handoff-url-sync.md) | Feature 0, 5, 8 | S–M |
| 31 | Project Storage auf S3 | [31-project-storage-s3.md](31-project-storage-s3.md) | Feature 0 | L |
| 33 | Asset-Bundle-Storage-Foundation (Sub A) | [33-asset-bundle-storage-foundation.md](33-asset-bundle-storage-foundation.md) | Feature 31 | M |
| 34 | Asset-Bundle-Admin-UI (Sub B) | [34-asset-bundle-admin-ui.md](34-asset-bundle-admin-ui.md) | Feature 33 | L |
| 35 | Feature-Proposal nutzt Upload-Dokumente | [35-feature-proposal-with-uploads.md](35-feature-proposal-with-uploads.md) | Feature 22, 28 | S |
| 36 | Features-Edit-Modal | [36-features-edit-modal.md](36-features-edit-modal.md) | Feature 22 | S |
| 37 | Editable Agent Prompts | [37-editable-agent-prompts.md](37-editable-agent-prompts.md) | Feature 31, 33 | M |
| 38 | Per-Agent Model Selection | [38-per-agent-model-selection.md](38-per-agent-model-selection.md) | Feature 0, 37 | M |
| 39 | Chat-Anzeige-Fix für strukturierte Wizard-Felder | [39-chat-object-rendering-fix.md](39-chat-object-rendering-fix.md) | Feature 13, 22 | S |
| 40 | Design-Bundle-Step | [40-design-bundle-step.md](40-design-bundle-step.md) | Feature 33 | L |
| 41 | Asset-Bundle-Coverage-View | [41-asset-bundle-coverage-view.md](41-asset-bundle-coverage-view.md) | Feature 33, 34 | S |
| 42 | Login-Gate | [42-login-gate.md](42-login-gate.md) | Feature 0 | M |
| 43a | shadcn Wizard-Forms (Phase 1) | [43a-shadcn-wizard-forms-done.md](43a-shadcn-wizard-forms-done.md) | Feature 6 | M |
| 43b | shadcn Buttons (Phase 2) | _geplant_ | Feature 43a | M |
| 43c | shadcn Dialoge & Selects (Phase 3) | _geplant_ | Feature 43a | M |
| 44 | Akzeptanzkriterien im Feature-Edit-Modal | [44-feature-acceptance-criteria-done.md](44-feature-acceptance-criteria-done.md) | Feature 22, 36 | M |
| 45 | Living-Sync via MCP | [45-living-sync-mcp-done.md](45-living-sync-mcp-done.md) | Feature 8, 30, 31, 44 | L |
| 46 | Wizard-Options-Admin | [46-wizard-options-admin.md](46-wizard-options-admin.md) | Feature 11, 12, 34, 41 | M |
| 47 | Agentic Design Workbench | [47-agentic-design-workbench.md](47-agentic-design-workbench.md) | Feature 11, 12, 13, 40, 46 | XL |
| 48 | Final Review Step | [48-final-review-step.md](48-final-review-step.md) | Feature 11, 12, 13, 46 | M |
| 49 | Wizard Blocker Apply Agent | [49-wizard-blocker-apply-agent-done.md](49-wizard-blocker-apply-agent-done.md) | Feature 2, 3, 11, 18 | M |
| 50 | Project Cockpit Feature Workbench | [50-project-cockpit-feature-workbench.md](50-project-cockpit-feature-workbench.md) | Feature 22, 45, 47, 48 | M |
| 51 | Feature-Done-Markdown Import via MCP | [51-feature-done-markdown-import-via-mcp.md](51-feature-done-markdown-import-via-mcp.md) | Feature 22, 45, 50 | M |
| 52 | Cockpit Launch Gate nach Wizard-Abschluss | [52-cockpit-launch-gate-after-wizard.md](52-cockpit-launch-gate-after-wizard.md) | Feature 11, 12, 13, 48, 50 | S–M |
| 53 | Cockpit Feature-Daten aus S3 | [53-cockpit-feature-data-from-s3.md](53-cockpit-feature-data-from-s3.md) | Feature 22, 31, 50, 52 | S |
| 54 | Cockpit Feature-Erfassung mit Wizard-Modal | [54-cockpit-feature-create-with-wizard-modal.md](54-cockpit-feature-create-with-wizard-modal.md) | Feature 22, 36, 44, 50, 53 | S–M |
| 55 | Cockpit Living-Sync-Reporter-Anbindung | [55-cockpit-living-sync-reporter.md](55-cockpit-living-sync-reporter.md) | Feature 45, 51, 53, 54 | S |
| 56 | Hackathon Lock | [56-hackathon-lock-done.md](56-hackathon-lock-done.md) | Feature 42 | S |
| 57 | Session-Storage Bearer Auth | [57-session-storage-bearer-auth.md](57-session-storage-bearer-auth.md) | Feature 42 | M |

## Architecture Docs

| Thema | Datei |
|-------|-------|
| Monorepo-Struktur | [../architecture/monorepo-structure.md](../architecture/monorepo-structure.md) |
| Authentifizierung | [../architecture/auth.md](../architecture/auth.md) |
| REST API Design | [../architecture/rest-api.md](../architecture/rest-api.md) |
| Persistenz | [../architecture/persistence.md](../architecture/persistence.md) |
| Koog Agents | [../architecture/koog-agents.md](../architecture/koog-agents.md) |

## Frontend Docs

| Thema | Datei |
|-------|-------|
| Design System | _nicht vorhanden_ |

## Tech-Stack

### Frontend
- Next.js (App Router) + React + TypeScript
- shadcn/ui (Component Library)
- Rete.js (Node-Graph Visualisierung)

### Backend
- Kotlin 2.3 + Spring Boot 4
- JetBrains Koog 0.8.0 (AI Agent Framework)
- Filesystem/Git Persistenz

### Deployment
- Docker Compose (Self-hosted)
- Multi-User mit JWT Auth
