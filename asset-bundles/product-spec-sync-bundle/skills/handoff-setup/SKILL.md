---
name: handoff-setup
description: Use when starting work from a Product-Spec-Agent handoff and the user wants to understand the project, select MVP scope, choose individual features, or prepare an implementation plan before coding.
---

# Handoff Setup

Use this skill after a Product-Spec-Agent handoff ZIP has been unpacked and
before implementation starts. The goal is to turn the handoff snapshot into an
explicit, user-approved MVP scope and a practical next-step plan.

## Required Inputs

Expect these files when available:

- `SPEC.md` for the product intent and constraints.
- `docs/features/` for feature-level detail.
- `implementation-order.md` for proposed execution sequence.
- `tasks/`, `decisions/`, and `clarifications/` for current delivery state.
- `.claude/living-sync.json` and the bundled Living Sync reporter for progress reporting.

If `SPEC.md` is missing, stop and ask the user for the correct handoff root or
sync instructions. Do not infer the product from scattered files.

## Setup Workflow

1. Sync first.
   - If a handoff Sync-URL is present, refresh the ZIP before analysis.
   - Inspect changed files after sync. Preserve local user edits.
   - If refreshed context conflicts with the current request, ask the user how
     to proceed before planning.

2. Build a project brief from `SPEC.md`.
   - Extract product goal, target users, core workflow, constraints, and
     explicit non-goals.
   - Keep the brief short. Quote file paths, not long source text.
   - Surface unclear or conflicting requirements as questions.

3. Build the candidate feature list.
   - Read `implementation-order.md` first.
   - Cross-check with `docs/features/`, `tasks/`, decisions, and
     clarifications.
   - Group candidates as `Must-have MVP`, `Should-have`, `Later`, and
     `Blocked/Needs decision`.
   - Preserve existing feature IDs, task numbers, or headings where present.

4. Interactive MVP Selection.
   - Present a numbered candidate list with one-line value, effort/risk, and
     dependencies for each item.
   - Ask the user to choose the MVP set. Accept ranges and comma-separated
     numbers, for example `1,2,5-7`.
   - If the user's choice omits an obvious dependency, explain the dependency
     and ask whether to include it or defer the dependent feature.
   - Do not silently expand scope.

5. Produce the setup decision.
   - Summarize selected MVP features, deferred features, blockers, and open
     questions.
   - Map selected features to the next concrete implementation steps.
   - Ask for confirmation before coding or generating a detailed plan.

## Living Sync

If the Living Sync reporter exists, use it for setup progress:

```bash
sh .claude/skills/global.living-sync-reporter/living-sync-reporter/bin/living-sync-reporter sync-note --severity INFO --message "Started handoff setup and MVP selection."
```

After the user confirms the MVP scope, report a concise note with selected
features and blockers. If feature IDs are available, report `feature-progress`
for selected features as `PLANNED` or `IN_PROGRESS` only when work actually
starts. When starting work on a concrete feature, read `feature_id` from the
matching `docs/features/*.md`, export it as `FEATURE_ID` and
`LIVING_SYNC_FEATURE_ID`, and pass `--feature "$FEATURE_ID"` on manual
`test-run`, `code-changes`, `token-usage`, and `sync-note` reports. If the
reporter is missing or unreachable, continue and mention the reporting gap.

## Output Shape

Use this structure for the user-facing setup summary:

```markdown
**Project Brief**
[2-5 bullets]

**MVP Candidates**
1. [Feature] - value; effort/risk; dependencies

**Recommended MVP**
[Numbered feature list plus rationale]

**Needs Your Choice**
Reply with feature numbers to include in MVP, or adjust the recommendation.
```

After selection:

```markdown
**Selected MVP**
[Confirmed list]

**Deferred**
[Deferred list]

**Next Implementation Steps**
1. [Step] -> verify: [check]
```

## Guardrails

- Stay interactive. Do not implement before the user confirms the MVP scope.
- Do not treat generated tasks as product truth when `spec.md` disagrees.
- Do not discard decisions or clarifications; they are constraints.
- Keep setup output concise enough for the user to choose.
- If requirements are ambiguous, ask one focused question at a time.
