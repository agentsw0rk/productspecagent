# OKF Feature Source-of-Truth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the first vertical OKF slice in which a project can migrate its feature graph to `docs/okf/`, synchronize it bidirectionally through `productspec/okf`, and use the resulting feature projection in Explorer, export, and Rete.

**Architecture:** Git is authoritative for OKF-active features. A safe parser and feature codec produce immutable bundle snapshots; changes are staged in S3 as one reviewable changeset and reach Git only after approval. `WizardService` remains the compatibility boundary for existing agents and UI code, but reads OKF projections and turns semantic feature writes into pending changesets once a project is active.

**Tech Stack:** Kotlin 2.3.10, Java 21, Spring Boot 4.0.5, SnakeYAML safe constructor, commonmark-java 0.28.0, Eclipse JGit 7.6, kotlinx.serialization, S3 `ObjectStore`, React 19, Next.js 16, Zustand 5, Rete.js 2, Playwright.

---

## Scope Boundary

This plan implements only slice 1 from the approved design:

- OKF core parsing, rendering, validation, and safe relative links
- the `feature` profile and feature graph codec
- connected Git repository with canonical `productspec/okf` branch
- project state, pending changeset, conflict, and presentation caches in S3
- feature migration dry-run, approval, Git roundtrip, and cutover
- inbound and outbound feature synchronization
- Explorer, export, Wizard, and Rete feature projection

It does not migrate Decisions, Tasks, Architecture, `docs/spec.md`, uploads, or design artifacts. It does not add agent write tools. Legacy Tasks remain unchanged after cutover and are not regenerated from inbound Git changes in this slice.

## References

- `docs/examples/okf/SPEC.md` for OKF 0.1 core behavior
- `docs/superpowers/specs/2026-07-17-okf-source-of-truth-design.md` for approved ProductSpecAgent decisions
- `https://github.com/commonmark/commonmark-java` for the Markdown AST API and version 0.28.0
- `https://projects.eclipse.org/projects/technology.jgit/releases/7.6.0` for the JGit release used by this plan

## File Map

### Backend files to create

| File | Responsibility |
|---|---|
| `backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfModels.kt` | Parsed bundle, validation issue, and feature profile models |
| `backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfStateModels.kt` | Project mode, connection, changeset, conflict, and API state |
| `backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfApiModels.kt` | REST request and response DTOs |
| `backend/src/main/kotlin/com/agentwork/productspecagent/config/OkfGitProperties.kt` | Worktree and deployment-secret configuration |
| `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfParser.kt` | Safe YAML-frontmatter parser and renderer |
| `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfProfileValidator.kt` | Core/profile validation and Markdown-link checks |
| `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfFeatureCodec.kt` | `WizardFeatureGraph` to/from feature documents |
| `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfGitClient.kt` | Isolated JGit checkout, fetch, branch, commit, and push |
| `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfThreeWayPlanner.kt` | ID-aware conflict detection and non-overlapping rebase |
| `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfProjectionService.kt` | Git snapshot to raw-doc mirror and typed feature projection |
| `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfSyncService.kt` | Sync, approval, idempotency, and push-failure handling |
| `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfMigrationService.kt` | Dry-run, pending migration changeset, and cutover |
| `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfFeatureService.kt` | Wizard-compatible read and proposed-write facade |
| `backend/src/main/kotlin/com/agentwork/productspecagent/storage/OkfStateStorage.kt` | State, pending changeset, conflict, and approved receipt storage |
| `backend/src/main/kotlin/com/agentwork/productspecagent/storage/OkfProjectionStorage.kt` | Raw `docs/okf/` mirror and serialized feature projection |
| `backend/src/main/kotlin/com/agentwork/productspecagent/storage/OkfPresentationStorage.kt` | Disposable Rete positions keyed by document UUID |
| `backend/src/main/kotlin/com/agentwork/productspecagent/api/OkfController.kt` | Connection, status, migration, sync, approval, and resolution endpoints |

### Backend files to modify

| File | Change |
|---|---|
| `backend/build.gradle.kts` | Add SnakeYAML, commonmark-java, and JGit |
| `backend/src/main/resources/application.yml` | Add safe OKF Git configuration |
| `backend/src/main/kotlin/com/agentwork/productspecagent/storage/ProjectStorage.kt` | Delete a derived docs subtree after cutover |
| `backend/src/main/kotlin/com/agentwork/productspecagent/service/WizardService.kt` | Route active feature reads/writes through OKF |
| `backend/src/main/kotlin/com/agentwork/productspecagent/service/WizardStepCompletion.kt` | Prevent step completion with unapproved feature changes and stop active task regeneration |
| `backend/src/main/kotlin/com/agentwork/productspecagent/service/ProjectService.kt` | Suppress legacy generated `docs/features/` for OKF-active projects |
| `backend/src/main/kotlin/com/agentwork/productspecagent/api/GlobalExceptionHandler.kt` | Map OKF validation, conflict, and pending-change errors |
| `start.sh` | Allow local `file:` remotes only in the local development process |

### Frontend files to create

| File | Responsibility |
|---|---|
| `frontend/src/lib/stores/okf-store.ts` | Load status, migration preview, pending changeset, sync, and approval |
| `frontend/src/components/okf/OkfSyncDialog.tsx` | Connection, migration, pending diff, conflict, and retry workflow |
| `frontend/src/components/okf/OkfDiffView.tsx` | Stable file-by-file changeset diff presentation |
| `frontend/e2e/fixtures/okf-git-fixture.ts` | Temporary local bare Git remote for Playwright |
| `frontend/e2e/okf-feature-sync.spec.ts` | Migration, external edit, selection, and conflict regression flow |

### Frontend files to modify

| File | Change |
|---|---|
| `frontend/src/lib/api.ts` | Add OKF DTOs and endpoint clients |
| `frontend/src/lib/stores/wizard-store.ts` | Open OKF review instead of advancing with an unapproved feature changeset |
| `frontend/src/components/explorer/ExplorerPanel.tsx` | Show OKF sync status and open the sync dialog |
| `frontend/src/components/wizard/steps/features/FeaturesGraphEditor.tsx` | Surface pending feature changes and reload after approval |
| `frontend/e2e/features-graph.spec.ts` | Preserve the acceptance-criteria regression while adding active-OKF assertions |

## Task 1: Add Dependencies and the Safe OKF Parser

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfModels.kt`
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfParser.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfParserTest.kt`

- [ ] **Step 1: Add parser tests that define frontmatter behavior**

Create tests for valid frontmatter, a body-only document, unknown-field order, duplicate YAML keys, excessive aliases, CRLF input, and render/parse roundtrip. The core assertions must include:

```kotlin
class OkfParserTest {
    private val parser = OkfParser()

    @Test
    fun `roundtrip preserves unknown fields and body`() {
        val source = """
            ---
            type: feature
            id: 550e8400-e29b-41d4-a716-446655440000
            custom_field: retained
            tags: []
            ---
            ## Outcome

            Login works.
        """.trimIndent()

        val parsed = parser.parse("features/login.md", source)
        val reparsed = parser.parse(parsed.path, parser.render(parsed))

        assertEquals(listOf("type", "id", "custom_field", "tags"), reparsed.frontmatter.keys.toList())
        assertEquals("retained", reparsed.frontmatter["custom_field"])
        assertEquals(parsed.body, reparsed.body)
    }

    @Test
    fun `body only document is parsed for tolerant validation`() {
        val parsed = parser.parse("features/login.md", "## Outcome\n\nLogin works.\n")
        assertTrue(parsed.frontmatter.isEmpty())
        assertEquals("## Outcome\n\nLogin works.\n", parsed.body)
    }

    @Test
    fun `duplicate frontmatter keys are rejected`() {
        assertThrows<OkfParseException> {
            parser.parse("features/login.md", "---\ntype: feature\ntype: decision\n---\n")
        }
    }
}
```

- [ ] **Step 2: Run the parser test and verify it fails**

Run:

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.service.okf.OkfParserTest"
```

Expected: compilation fails because `OkfParser`, `OkfDocument`, and `OkfParseException` do not exist.

- [ ] **Step 3: Add managed parser dependencies**

Add these entries under `dependencies`:

```kotlin
// OKF parsing and Git synchronization
implementation("org.yaml:snakeyaml")
implementation("org.commonmark:commonmark:0.28.0")
implementation("org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r")
```

Use Spring Boot dependency management for SnakeYAML. Do not add the Python OKF reference implementation.

- [ ] **Step 4: Add the core parsed-document types**

Create `OkfModels.kt` with these exact public types:

```kotlin
package com.agentwork.productspecagent.domain.okf

import kotlinx.serialization.Serializable

data class OkfDocument(
    val path: String,
    val frontmatter: LinkedHashMap<String, Any?>,
    val body: String,
)

data class OkfBundle(
    val documents: List<OkfDocument>,
) {
    val byPath: Map<String, OkfDocument> = documents.associateBy { it.path }
}

@Serializable
enum class OkfIssueSeverity { ERROR, WARNING }

@Serializable
data class OkfIssue(
    val severity: OkfIssueSeverity,
    val path: String,
    val field: String? = null,
    val message: String,
)

class OkfParseException(
    val documentPath: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("$documentPath: $message", cause)
```

- [ ] **Step 5: Implement the bounded safe parser and deterministic renderer**

Implement `OkfParser` with a first-line frontmatter delimiter, normalized LF line endings, a `SafeConstructor`, and these loader limits:

```kotlin
@Service
class OkfParser {
    private val loaderOptions = LoaderOptions().apply {
        maxAliasesForCollections = 20
        nestingDepthLimit = 40
        codePointLimit = 1_000_000
        isAllowDuplicateKeys = false
        isAllowRecursiveKeys = false
    }
    private val dumperOptions = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
        indent = 2
        indicatorIndent = 2
        width = 120
    }
    private val yaml = Yaml(
        SafeConstructor(loaderOptions),
        Representer(dumperOptions),
        dumperOptions,
        loaderOptions,
    )

    fun parse(path: String, raw: String): OkfDocument {
        val content = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (!content.startsWith("---\n")) {
            return OkfDocument(path, linkedMapOf(), content)
        }
        val end = content.indexOf("\n---\n", startIndex = 4)
        if (end < 0) throw OkfParseException(path, "unterminated YAML frontmatter")
        val yamlText = content.substring(4, end)
        val body = content.substring(end + 5)
        val loaded = try {
            yaml.load<Any?>(yamlText)
        } catch (ex: Exception) {
            throw OkfParseException(path, ex.message ?: "invalid YAML frontmatter", ex)
        }
        if (loaded !is Map<*, *>) throw OkfParseException(path, "frontmatter must be a mapping")
        val frontmatter = linkedMapOf<String, Any?>()
        loaded.forEach { (key, value) ->
            if (key !is String) throw OkfParseException(path, "frontmatter keys must be strings")
            frontmatter[key] = value
        }
        return OkfDocument(path, frontmatter, body)
    }

    fun render(document: OkfDocument): String {
        if (document.frontmatter.isEmpty()) return document.body
        val dumped = yaml.dump(document.frontmatter).removeSuffix("...\n").trimEnd()
        return "---\n$dumped\n---\n${document.body}"
    }
}
```

- [ ] **Step 6: Run the focused parser tests**

Run the command from Step 2. Expected: all `OkfParserTest` tests pass.

- [ ] **Step 7: Commit the parser foundation**

```bash
git add backend/build.gradle.kts backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfModels.kt backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfParser.kt backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfParserTest.kt
git commit -m "feat(okf): add safe bundle parser"
```

## Task 2: Validate the ProductSpecAgent Profile and Relative Links

**Files:**
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfProfileValidator.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfProfileValidatorTest.kt`

- [ ] **Step 1: Write failing profile and link tests**

Cover required fields, UUID uniqueness, tags as a YAML list, ISO timestamp, unknown types as warnings, path traversal as an error, a valid relative link, and a broken relative link as a warning:

```kotlin
@Test
fun `profile reports duplicate ids and broken links without dropping unknown fields`() {
    val first = parser.parse("features/a.md", validFeature("same-id", "[B](b.md)"))
    val second = parser.parse("features/b.md", validFeature("same-id", "[Missing](missing.md)"))

    val issues = validator.validate(OkfBundle(listOf(first, second)))

    assertTrue(issues.any { it.severity == OkfIssueSeverity.ERROR && it.message.contains("duplicate id") })
    assertTrue(issues.any { it.severity == OkfIssueSeverity.WARNING && it.message.contains("missing.md") })
}

@Test
fun `link escaping bundle is an error`() {
    val doc = parser.parse("features/a.md", validFeature(UUID.randomUUID().toString(), "[Outside](../../secret.md)"))
    assertTrue(validator.validate(OkfBundle(listOf(doc))).any {
        it.severity == OkfIssueSeverity.ERROR && it.message.contains("escapes bundle")
    })
}
```

- [ ] **Step 2: Run the validator test and verify it fails**

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.service.okf.OkfProfileValidatorTest"
```

Expected: compilation fails because `OkfProfileValidator` does not exist.

- [ ] **Step 3: Implement validation with the CommonMark AST**

Use `org.commonmark.parser.Parser` and visit `Link` nodes. Normalize each non-URL destination relative to the source document. Never use string replacement for path resolution.

```kotlin
@Service
class OkfProfileValidator {
    private val markdown = Parser.builder().build()
    private val required = listOf("type", "id", "title", "description", "tags", "timestamp")
    private val knownTypes = setOf(
        "feature",
        "decision",
        "architecture-overview",
        "architecture-component",
        "architecture-interface",
        "architecture-data-model",
        "architecture-constraint",
    )

    fun validate(bundle: OkfBundle): List<OkfIssue> {
        val issues = mutableListOf<OkfIssue>()
        val ids = mutableMapOf<String, String>()
        bundle.documents.forEach { document ->
            if (document.path.endsWith("index.md")) {
                issues += validateLinks(document, bundle.byPath.keys)
                return@forEach
            }
            required.filterNot(document.frontmatter::containsKey).forEach {
                issues += OkfIssue(OkfIssueSeverity.ERROR, document.path, it, "required field is missing")
            }
            val id = document.frontmatter["id"] as? String
            if (id != null) {
                runCatching { UUID.fromString(id) }.onFailure {
                    issues += OkfIssue(OkfIssueSeverity.ERROR, document.path, "id", "id must be a UUID")
                }
                ids.putIfAbsent(id, document.path)?.let { first ->
                    issues += OkfIssue(OkfIssueSeverity.ERROR, document.path, "id", "duplicate id also used by $first")
                }
            }
            if (document.frontmatter["tags"] !is List<*>) {
                issues += OkfIssue(OkfIssueSeverity.ERROR, document.path, "tags", "tags must be a YAML list")
            }
            val timestamp = document.frontmatter["timestamp"] as? String
            if (timestamp != null && runCatching { java.time.OffsetDateTime.parse(timestamp) }.isFailure) {
                issues += OkfIssue(OkfIssueSeverity.ERROR, document.path, "timestamp", "timestamp must be ISO-8601 with offset")
            }
            val type = document.frontmatter["type"] as? String
            if (type != null && type !in knownTypes) {
                issues += OkfIssue(OkfIssueSeverity.WARNING, document.path, "type", "unknown type '$type' is preserved")
            }
            issues += validateTextFields(document)
            issues += validateLinks(document, bundle.byPath.keys)
        }
        return issues
    }

    private fun validateLinks(document: OkfDocument, paths: Set<String>): List<OkfIssue> {
        val result = mutableListOf<OkfIssue>()
        var section: String? = null
        val textRenderer = TextContentRenderer.builder().build()
        markdown.parse(document.body).accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                if (heading.level == 2) section = textRenderer.render(heading).trim()
                visitChildren(heading)
            }

            override fun visit(link: Link) {
                val destination = link.destination.substringBefore('#')
                val scheme = runCatching { URI(destination).scheme }.getOrNull()
                if (destination.isBlank()) return
                if (scheme != null) {
                    if (scheme !in setOf("http", "https") || section != "Citations") {
                        result += OkfIssue(OkfIssueSeverity.ERROR, document.path, null, "external links are allowed only in Citations")
                    }
                    return
                }
                val sourceParent = Path.of(document.path).parent ?: Path.of("")
                val target = runCatching { sourceParent.resolve(destination).normalize() }.getOrElse {
                    result += OkfIssue(OkfIssueSeverity.ERROR, document.path, null, "link '$destination' is not a valid path")
                    return
                }
                if (target.isAbsolute || target.startsWith("..")) {
                    result += OkfIssue(OkfIssueSeverity.ERROR, document.path, null, "link '$destination' escapes bundle")
                } else if (target.toString().replace('\\', '/') !in paths) {
                    result += OkfIssue(OkfIssueSeverity.WARNING, document.path, null, "link target '$destination' does not exist")
                }
            }
        })
        return result
    }
}
```

Add these exact helper checks before returning from concept validation:

```kotlin
private fun validateTextFields(document: OkfDocument): List<OkfIssue> {
    val issues = mutableListOf<OkfIssue>()
    val title = document.frontmatter["title"] as? String
    if (title.isNullOrBlank()) {
        issues += OkfIssue(OkfIssueSeverity.ERROR, document.path, "title", "title must not be blank")
    }
    val description = document.frontmatter["description"] as? String
    if (description.isNullOrBlank() || '\n' in description || description.length > 240 || description.lastOrNull() !in ".!?") {
        issues += OkfIssue(OkfIssueSeverity.ERROR, document.path, "description", "description must be one sentence of at most 240 characters")
    }
    val normalized = Path.of(document.path).normalize()
    if (normalized.isAbsolute || normalized.startsWith("..") || normalized.toString().replace('\\', '/') != document.path) {
        issues += OkfIssue(OkfIssueSeverity.ERROR, document.path, null, "document path is not a safe normalized bundle path")
    }
    return issues
}
```

Track the current level-two heading while visiting the CommonMark AST. An `http` or `https` link outside `## Citations` is an `ERROR`; other URI schemes are rejected. A URL in the optional `resource` frontmatter field is valid.

- [ ] **Step 4: Run the focused validator tests**

Run the command from Step 2. Expected: all validator tests pass.

- [ ] **Step 5: Commit profile validation**

```bash
git add backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfProfileValidator.kt backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfProfileValidatorTest.kt
git commit -m "feat(okf): validate profile and relative links"
```

## Task 3: Encode and Decode the Feature Graph

**Files:**
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfModels.kt`
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfFeatureCodec.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfFeatureCodecTest.kt`

- [ ] **Step 1: Write failing roundtrip and rename tests**

Test two features, one edge, scopes, `scopeFields`, acceptance-criterion IDs, stable document UUIDs, a title rename, unknown frontmatter preservation, and deterministic indexes:

```kotlin
@Test
fun `feature graph roundtrip preserves ids criteria and dependencies`() {
    val login = WizardFeature(
        id = "550e8400-e29b-41d4-a716-446655440000",
        title = "Login",
        scopes = setOf(FeatureScope.FRONTEND, FeatureScope.BACKEND),
        description = "Users can sign in.",
        scopeFields = mapOf("auth" to "OIDC"),
        acceptanceCriteria = listOf(AcceptanceCriterion("650e8400-e29b-41d4-a716-446655440000", "Valid credentials succeed")),
    )
    val dashboard = WizardFeature(
        id = "750e8400-e29b-41d4-a716-446655440000",
        title = "Dashboard",
        description = "Users see their account summary.",
    )
    val graph = WizardFeatureGraph(
        listOf(login, dashboard),
        listOf(WizardFeatureEdge("850e8400-e29b-41d4-a716-446655440000", login.id, dashboard.id)),
    )

    val bundle = codec.encode(graph, existing = null, timestamp = fixedTime)
    val decoded = codec.decode(bundle, positions = emptyMap()).graph

    assertEquals(graph.features.map { it.copy(position = GraphPosition()) }, decoded.features)
    assertEquals(graph.edges, decoded.edges)
    assertTrue(bundle.byPath.containsKey("features/login.md"))
    assertTrue(bundle.byPath["features/dashboard.md"]!!.body.contains("[Login](login.md)"))
}
```

- [ ] **Step 2: Run the codec test and verify it fails**

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.service.okf.OkfFeatureCodecTest"
```

Expected: compilation fails because `OkfFeatureCodec` does not exist.

- [ ] **Step 3: Define the feature profile representation**

Add these types to `OkfModels.kt`:

```kotlin
data class OkfFeatureDecodeResult(
    val graph: WizardFeatureGraph,
    val issues: List<OkfIssue>,
)

data class OkfFeaturePath(
    val id: String,
    val path: String,
)
```

Use these exact feature extensions in YAML:

```yaml
type: feature
id: 550e8400-e29b-41d4-a716-446655440000
title: Login
description: Users can sign in.
tags: []
timestamp: 2026-07-17T12:00:00+02:00
scopes: [FRONTEND, BACKEND]
scope_fields:
  auth: OIDC
legacy_ids: []
```

Acceptance criteria and edges remain human-readable Markdown and carry stable IDs in comments:

```markdown
## Acceptance Criteria

- [ ] Valid credentials succeed <!-- okf:id=650e8400-e29b-41d4-a716-446655440000 -->

## Relationships

- depends-on: [Login](login.md) <!-- okf:id=850e8400-e29b-41d4-a716-446655440000 -->
```

- [ ] **Step 4: Implement deterministic paths, body sections, and indexes**

Implement these public methods:

```kotlin
@Service
class OkfFeatureCodec(private val parser: OkfParser) {
    fun encode(
        graph: WizardFeatureGraph,
        existing: OkfBundle?,
        timestamp: OffsetDateTime,
    ): OkfBundle

    fun decode(
        bundle: OkfBundle,
        positions: Map<String, GraphPosition>,
    ): OkfFeatureDecodeResult
}
```

The implementation must follow these rules:

1. Reuse an existing document by `id`, copy its `LinkedHashMap`, and update only known feature fields.
2. Generate a lowercase ASCII slug from `title`; on collision append `-${id.take(8)}`.
3. A title change renames the file but never changes `id`.
4. Render empty `Problem` and `Outcome` sections without inventing text; approval validation rejects an empty `description`.
5. Encode the target feature's incoming graph edges as `depends-on` links to their source feature.
6. Missing criterion or edge comment IDs decode to `UUID.nameUUIDFromBytes(...)` and emit a warning.
7. Ignore graph positions while encoding; apply `positions[id] ?: GraphPosition()` while decoding.
8. Generate `index.md` and `features/index.md` deterministically. Generated indexes are not concepts and have no `type`.
9. Never generate `log.md`.

- [ ] **Step 5: Run the codec and validator tests**

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.service.okf.OkfFeatureCodecTest" --tests "com.agentwork.productspecagent.service.okf.OkfProfileValidatorTest"
```

Expected: both suites pass, including rename and stable-ID assertions.

- [ ] **Step 6: Commit the feature codec**

```bash
git add backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfModels.kt backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfFeatureCodec.kt backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfFeatureCodecTest.kt
git commit -m "feat(okf): add feature graph codec"
```

## Task 4: Persist OKF State, Changesets, Projections, and Positions

**Files:**
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfStateModels.kt`
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/storage/OkfStateStorage.kt`
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/storage/OkfProjectionStorage.kt`
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/storage/OkfPresentationStorage.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/storage/OkfStorageTest.kt`

- [ ] **Step 1: Write failing storage contract tests**

Use `InMemoryObjectStore` to verify default legacy state, credential-free connection persistence, one mutable pending changeset, approved receipt idempotency, raw bundle replacement, feature projection roundtrip, and position replacement.

```kotlin
@Test
fun `connection persistence never contains credentials`() {
    val storage = OkfStateStorage(InMemoryObjectStore())
    storage.saveState(OkfProjectState(
        projectId = "p1",
        connection = OkfGitConnection("https://git.example/repo.git", "main"),
    ))

    val state = storage.loadState("p1")!!
    assertEquals("https://git.example/repo.git", state.connection?.remoteUrl)
    assertFalse(state.connection!!.remoteUrl.contains("token"))
}
```

- [ ] **Step 2: Run the storage test and verify it fails**

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.storage.OkfStorageTest"
```

Expected: compilation fails because the state models and storages do not exist.

- [ ] **Step 3: Add serializable state and changeset models**

Create the exact enums and core fields below. Store file contents in the pending changeset so a retry never depends on a mutable wizard payload.

```kotlin
@Serializable enum class OkfMode { LEGACY, MIGRATION_READY, OKF_ACTIVE }
@Serializable enum class OkfSyncStatus { CLEAN, INCOMING, PROPOSAL_PENDING, CONFLICT, INVALID_REMOTE, PUSH_FAILED }
@Serializable enum class OkfChangesetKind { EDIT, MIGRATION }
@Serializable enum class OkfOperationType { ADD, UPDATE, RENAME, DELETE }
@Serializable enum class OkfChangesetStatus { PENDING, APPROVED }

@Serializable
data class OkfGitConnection(
    val remoteUrl: String,
    val baseBranch: String = "main",
    val canonicalBranch: String = "productspec/okf",
)

@Serializable
data class OkfFileOperation(
    val type: OkfOperationType,
    val path: String,
    val previousPath: String? = null,
    val baseContent: String? = null,
    val content: String? = null,
    val documentId: String? = null,
)

@Serializable
data class OkfChangeset(
    val id: String,
    val projectId: String,
    val kind: OkfChangesetKind,
    val status: OkfChangesetStatus = OkfChangesetStatus.PENDING,
    val baseCommit: String?,
    val operations: List<OkfFileOperation>,
    val issues: List<OkfIssue>,
    val createdAt: String,
    val updatedAt: String,
    val approvedCommit: String? = null,
)

@Serializable
data class OkfConflict(
    val changesetId: String,
    val documentId: String?,
    val path: String,
    val baseContent: String?,
    val remoteContent: String?,
    val proposedContent: String?,
)

@Serializable
data class OkfProjectState(
    val projectId: String,
    val mode: OkfMode = OkfMode.LEGACY,
    val syncStatus: OkfSyncStatus = OkfSyncStatus.CLEAN,
    val connection: OkfGitConnection? = null,
    val baseCommit: String? = null,
    val pendingChangesetId: String? = null,
    val lastError: String? = null,
    val updatedAt: String = Instant.EPOCH.toString(),
)
```

- [ ] **Step 4: Implement object-store keys and replacement semantics**

Use these keys:

```text
projects/{id}/okf/state.json
projects/{id}/okf/changesets/pending.json
projects/{id}/okf/changesets/approved/{changesetId}.json
projects/{id}/okf/conflicts/current.json
projects/{id}/okf/feature-projection.json
projects/{id}/okf/presentation.json
projects/{id}/docs/okf/**
```

`OkfProjectionStorage.replaceRawBundle()` must delete only `projects/{id}/docs/okf/` before writing the new snapshot. `OkfPresentationStorage` stores only `Map<String, GraphPosition>` and is allowed to disappear without affecting feature semantics.

- [ ] **Step 5: Run storage tests**

Run the command from Step 2. Expected: all `OkfStorageTest` tests pass.

- [ ] **Step 6: Commit OKF persistence**

```bash
git add backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfStateModels.kt backend/src/main/kotlin/com/agentwork/productspecagent/storage/OkfStateStorage.kt backend/src/main/kotlin/com/agentwork/productspecagent/storage/OkfProjectionStorage.kt backend/src/main/kotlin/com/agentwork/productspecagent/storage/OkfPresentationStorage.kt backend/src/test/kotlin/com/agentwork/productspecagent/storage/OkfStorageTest.kt
git commit -m "feat(okf): persist sync state and projections"
```

## Task 5: Connect and Operate the Canonical Git Branch

**Files:**
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/config/OkfGitProperties.kt`
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfGitClient.kt`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `start.sh`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfGitClientTest.kt`

- [ ] **Step 1: Write failing JGit integration tests with temporary repositories**

Create a temporary bare remote plus a seeded `main` branch. Verify canonical-branch creation, fetch, full `docs/okf/` snapshot, commit/push, expected-head rejection, and resource cleanup.

```kotlin
@TempDir lateinit var tempDir: Path

@Test
fun `reads base and creates canonical branch only on approved push`() {
    val remote = seedBareRemote(tempDir.resolve("remote.git"))
    val client = OkfGitClient(properties(tempDir.resolve("worktrees")))
    val connection = OkfGitConnection(remote.toUri().toString(), "main")

    val initial = client.fetch("project-1", connection)
    assertEquals("main", initial.branch)
    val result = client.commitAndPush(
        projectId = "project-1",
        connection = connection,
        expectedRemoteCommit = initial.commit,
        files = mapOf("index.md" to "---\nokf_version: \"0.1\"\nprofile: \"productspecagent/1\"\n---\n"),
        message = "docs(okf): migrate features",
    )

    assertNotEquals(initial.commit, result.commit)
    assertEquals("productspec/okf", result.branch)
}
```

- [ ] **Step 2: Run the JGit test and verify it fails**

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.service.okf.OkfGitClientTest"
```

Expected: compilation fails because `OkfGitClient` and its properties do not exist.

- [ ] **Step 3: Add deployment-secret configuration**

Create:

```kotlin
@ConfigurationProperties("okf.git")
data class OkfGitProperties(
    val worktreeRoot: Path = Path.of("data/okf-worktrees"),
    val username: String = "oauth2",
    val token: String = "",
    val allowFileRemotes: Boolean = false,
)
```

Add to `application.yml`:

```yaml
okf:
  git:
    worktree-root: ${OKF_GIT_WORKTREE_ROOT:data/okf-worktrees}
    username: ${OKF_GIT_USERNAME:oauth2}
    token: ${OKF_GIT_TOKEN:}
    allow-file-remotes: ${OKF_GIT_ALLOW_FILE_REMOTES:false}
```

Add to local `start.sh` before backend startup:

```bash
export OKF_GIT_ALLOW_FILE_REMOTES="${OKF_GIT_ALLOW_FILE_REMOTES:-true}"
```

No token is accepted by the project API or stored in S3.

- [ ] **Step 4: Implement the JGit boundary**

Expose these data and methods:

```kotlin
data class OkfGitSnapshot(val commit: String, val branch: String, val files: Map<String, String>)
data class OkfGitCommitResult(val commit: String, val branch: String)

@Service
class OkfGitClient(private val properties: OkfGitProperties) {
    fun fetch(projectId: String, connection: OkfGitConnection): OkfGitSnapshot
    fun readAt(projectId: String, connection: OkfGitConnection, commit: String): OkfGitSnapshot
    fun commitAndPush(
        projectId: String,
        connection: OkfGitConnection,
        expectedRemoteCommit: String,
        files: Map<String, String>,
        message: String,
    ): OkfGitCommitResult
}
```

Implementation rules:

1. Validate `projectId` as a UUID or a test-safe `[A-Za-z0-9_-]+` segment before resolving the worktree path.
2. Accept only `https:`; accept `file:` only when `allowFileRemotes` is true. Reject credentials embedded in the URL.
3. Clone `baseBranch` when no checkout exists. A fetch reads `origin/productspec/okf` when present and otherwise reads `origin/{baseBranch}` without creating or pushing a branch.
4. Read only UTF-8 regular-file blobs below `docs/okf/`; reject Git symlink entries and paths that do not normalize inside that root.
5. Before committing, compare the fetched remote head with `expectedRemoteCommit`.
6. Reset only the managed isolated worktree, create or check out local `productspec/okf`, replace only `docs/okf/`, stage that path, commit with a deterministic message, and push `HEAD:refs/heads/productspec/okf` without force. The first approved migration push is the operation that creates the remote canonical branch.
7. Use `Git.use {}` and close `RevWalk`, `TreeWalk`, and repository resources.
8. Convert non-fast-forward and authentication failures into typed exceptions; never mutate S3 state inside this class.

- [ ] **Step 5: Run JGit tests**

Run the command from Step 2. Expected: canonical branch and push tests pass; the stale-head test reports the typed remote-advanced exception.

- [ ] **Step 6: Commit Git support**

```bash
git add backend/src/main/kotlin/com/agentwork/productspecagent/config/OkfGitProperties.kt backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfGitClient.kt backend/src/main/resources/application.yml backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfGitClientTest.kt start.sh
git commit -m "feat(okf): connect canonical git branch"
```

## Task 6: Implement ID-Aware Changeset Approval and Sync

**Files:**
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfThreeWayPlanner.kt`
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfProjectionService.kt`
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfSyncService.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfThreeWayPlannerTest.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfSyncServiceTest.kt`

- [ ] **Step 1: Write failing three-way planner tests**

Cover different document IDs, the same ID after a remote rename, a path collision with different IDs, generated `index.md` changes, remote deletion, and local deletion.

```kotlin
@Test
fun `same document id conflicts even when remote renamed its file`() {
    val result = planner.plan(
        base = bundle("features/login.md", id, "base"),
        remote = bundle("features/sign-in.md", id, "remote"),
        proposed = bundle("features/login.md", id, "local"),
    )
    assertEquals(listOf(id), result.conflicts.mapNotNull { it.documentId })
}

@Test
fun `different documents integrate and regenerate indexes`() {
    val result = planner.plan(base, remoteChangesFeatureA, localChangesFeatureB)
    assertTrue(result.conflicts.isEmpty())
    assertEquals(setOf(featureAId, featureBId), result.merged.documents.mapNotNull { it.frontmatter["id"] as? String }.toSet())
}
```

- [ ] **Step 2: Run planner tests and verify they fail**

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.service.okf.OkfThreeWayPlannerTest"
```

Expected: compilation fails because the planner does not exist.

- [ ] **Step 3: Implement document-identity conflict planning**

Expose:

```kotlin
data class OkfMergePlan(
    val merged: OkfBundle?,
    val conflicts: List<OkfConflict>,
)

@Service
class OkfThreeWayPlanner(private val featureCodec: OkfFeatureCodec) {
    fun plan(base: OkfBundle, remote: OkfBundle, proposed: OkfBundle): OkfMergePlan
}
```

Compare concept documents by frontmatter `id`, compare path collisions separately, and exclude every `index.md` from conflict detection. If no concept conflict exists, merge the remote concepts with locally changed concept IDs, then regenerate indexes through `OkfFeatureCodec`.

- [ ] **Step 4: Write failing sync-service tests**

Use a fake `OkfGitClient` and in-memory storages. Test clean inbound sync, invalid remote, non-overlapping pending rebase, conflict persistence, approval idempotency, push failure retaining pending state, and successful migration approval switching to `OKF_ACTIVE` only after a readback projection.

```kotlin
@Test
fun `failed push keeps approved content retryable without advancing base commit`() {
    git.failNextPush = true
    val before = stateStorage.loadState(projectId)!!

    assertThrows<OkfPushException> { service.approve(projectId, changesetId) }

    val after = stateStorage.loadState(projectId)!!
    assertEquals(before.baseCommit, after.baseCommit)
    assertEquals(OkfSyncStatus.PUSH_FAILED, after.syncStatus)
    assertNotNull(stateStorage.loadPending(projectId))
}
```

- [ ] **Step 5: Implement projection and synchronized approval**

`OkfProjectionService.project()` first stores the fetched raw-doc mirror so invalid Git content remains inspectable in Explorer. It then parses documents independently, validates the bundle, decodes only profile-valid feature documents with cached positions, and stores the typed projection only when no parse or profile error exists. On errors it retains the last confirmed typed projection and base commit, returns all issues, and lets `OkfSyncService` set `INVALID_REMOTE`.

`OkfSyncService` must expose:

```kotlin
fun status(projectId: String): OkfProjectState
fun sync(projectId: String): OkfProjectState
fun pending(projectId: String): OkfChangeset?
fun approve(projectId: String, changesetId: String): OkfChangeset
fun resolve(projectId: String, changesetId: String, resolution: OkfConflictResolution): OkfChangeset?
```

Use a `ConcurrentHashMap<String, ReentrantLock>` for same-process serialization and the expected remote SHA plus non-force push for cross-process protection. Approval must be idempotent: return the stored approved receipt when the same changeset ID is retried.

Every generated `OkfFileOperation` includes `baseContent` from the changeset base snapshot and `content` from the proposal. For renames, `previousPath` identifies the base file. Generated index operations are included in the user-visible diff but are excluded from concept-conflict detection and regenerated after every rebase.

Conflict resolution supports exactly:

```kotlin
@Serializable enum class OkfConflictResolution { REMOTE, PROPOSED }
```

`REMOTE` drops the local operation for that document. `PROPOSED` rebases the proposed whole document onto the current remote commit. Both regenerate indexes and require a new diff review before approval.

- [ ] **Step 6: Run planner and sync tests**

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.service.okf.OkfThreeWayPlannerTest" --tests "com.agentwork.productspecagent.service.okf.OkfSyncServiceTest"
```

Expected: all merge, conflict, retry, and cutover tests pass.

- [ ] **Step 7: Commit synchronization**

```bash
git add backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfThreeWayPlanner.kt backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfProjectionService.kt backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfSyncService.kt backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfThreeWayPlannerTest.kt backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfSyncServiceTest.kt
git commit -m "feat(okf): approve and synchronize changesets"
```

## Task 7: Add Feature Migration Dry-Run and Cutover

**Files:**
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfApiModels.kt`
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfMigrationService.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfMigrationServiceTest.kt`

- [ ] **Step 1: Write failing migration tests**

Test valid UUID preservation, deterministic mapping of legacy string IDs, `legacy_ids`, scalar acceptance-criteria normalization, empty-description errors, slug collision reporting, idempotent repeated dry-runs, and `MIGRATION_READY` without early cutover.

```kotlin
@Test
fun `legacy string id maps deterministically and remains discoverable`() {
    seedFeature(id = "feature-a", description = "Users can sign in.")

    val first = service.dryRun(projectId)
    val second = service.dryRun(projectId)

    val firstId = first.documents.single().documentId
    assertEquals(firstId, second.documents.single().documentId)
    assertTrue(UUID.fromString(firstId).toString().isNotBlank())
    val featureOperation = first.operations.single { it.documentId == firstId }
    val migrated = parser.parse(featureOperation.path, featureOperation.content!!)
    assertEquals(listOf("feature-a"), migrated.frontmatter["legacy_ids"])
}
```

- [ ] **Step 2: Run migration tests and verify they fail**

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.service.okf.OkfMigrationServiceTest"
```

Expected: compilation fails because the service and migration DTOs do not exist.

- [ ] **Step 3: Define migration DTOs**

Add:

```kotlin
@Serializable
data class OkfMigrationDocument(
    val sourceId: String,
    val documentId: String,
    val path: String,
    val normalizations: List<String>,
)

@Serializable
data class OkfMigrationPreview(
    val projectId: String,
    val baseCommit: String,
    val documents: List<OkfMigrationDocument>,
    val operations: List<OkfFileOperation>,
    val issues: List<OkfIssue>,
)
```

- [ ] **Step 4: Implement deterministic migration and pending changeset creation**

Use `WizardService.getWizardData()` while the project is still `LEGACY`. Decode its normalized feature array and edge array. Preserve valid UUIDs; map invalid IDs with:

```kotlin
private fun migratedUuid(projectId: String, kind: String, legacyId: String): String =
    UUID.nameUUIDFromBytes("productspecagent:$projectId:$kind:$legacyId".toByteArray()).toString()
```

Rewrite feature, criterion, and edge references through the same ID map. `dryRun()` fetches the canonical branch when it exists and otherwise reads the configured base branch; it never creates a remote branch, commit, or S3 changeset. `prepare()` saves one `MIGRATION` changeset whose operations and issues equal the current dry-run result, changes the mode to `MIGRATION_READY`, and returns that changeset. Any `ERROR` issue prevents `prepare()`.

Do not switch to `OKF_ACTIVE` here. `OkfSyncService.approve()` performs push, readback, projection, and only then switches an approved migration changeset to active.

- [ ] **Step 5: Run migration tests**

Run the command from Step 2. Expected: all migration and idempotency tests pass.

- [ ] **Step 6: Commit migration support**

```bash
git add backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfApiModels.kt backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfMigrationService.kt backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfMigrationServiceTest.kt
git commit -m "feat(okf): preview and prepare feature migration"
```

## Task 8: Cut Wizard Feature Reads and Writes Over to OKF

**Files:**
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfFeatureService.kt`
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/service/WizardService.kt`
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/service/WizardStepCompletion.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfFeatureServiceTest.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/service/WizardServiceTest.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/service/WizardStepCompletionServiceTest.kt`

- [ ] **Step 1: Write failing active-mode compatibility tests**

Assert that legacy mode is unchanged, active reads replace only `FEATURES.fields.features` and `edges`, active semantic writes create or update one pending changeset, position-only writes update presentation storage without a changeset, pending content survives reload, and feature completion is rejected while a changeset is pending.

```kotlin
@Test
fun `active feature write creates pending change instead of replacing wizard json`() {
    activateOkfWith(loginBundle)
    val updated = featureStep(title = "Sign in", positionX = 120.0)

    service.saveStepData(projectId, "FEATURES", updated)

    assertEquals("Login", legacyFeatureTitleInProjectStorage())
    assertEquals("Sign in", service.getWizardData(projectId).featureTitle())
    assertEquals(OkfChangesetKind.EDIT, okfStateStorage.loadPending(projectId)!!.kind)
}
```

- [ ] **Step 2: Run focused Wizard tests and verify they fail**

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.service.okf.OkfFeatureServiceTest" --tests "com.agentwork.productspecagent.service.WizardServiceTest" --tests "com.agentwork.productspecagent.service.WizardStepCompletionServiceTest"
```

Expected: active-mode assertions fail because Wizard is still backed only by `wizard.json`.

- [ ] **Step 3: Implement the OKF feature facade**

Expose:

```kotlin
@Service
class OkfFeatureService(
    private val stateStorage: OkfStateStorage,
    private val projectionStorage: OkfProjectionStorage,
    private val presentationStorage: OkfPresentationStorage,
    private val parser: OkfParser,
    private val codec: OkfFeatureCodec,
    private val validator: OkfProfileValidator,
) {
    fun isActive(projectId: String): Boolean
    fun graph(projectId: String): WizardFeatureGraph
    fun propose(projectId: String, graph: WizardFeatureGraph): OkfChangeset?
    fun hasPending(projectId: String): Boolean
}
```

`graph()` overlays a pending feature changeset on the confirmed projection so reload keeps the user's draft. `propose()` first separates `position` changes, persists them to `OkfPresentationStorage`, and returns `null` when the semantic graph is unchanged.

- [ ] **Step 4: Route `WizardService` through the facade in active mode**

Extend the constructor without breaking direct unit-test construction:

```kotlin
class WizardService(
    private val storage: ProjectStorage,
    @Lazy private val okfFeatures: OkfFeatureService? = null,
)
```

For `getWizardData()`, replace only the active FEATURES graph with JSON produced from `okfFeatures.graph()`. For `saveStepData()` and `saveWizardData()`, normalize first, send the graph to `propose()`, and persist only `completedAt` plus non-feature fields in legacy wizard storage. All non-FEATURES behavior stays byte-for-byte equivalent.

- [ ] **Step 5: Gate feature-step completion and stop active Task regeneration**

Add `private val okfFeatureService: OkfFeatureService? = null` to the end of the `WizardStepCompletionService` constructor so existing direct test construction remains valid. Add a typed `OkfChangesetPendingException`. Immediately after each `wizardService.saveStepData(...)` call used by completion, require no pending OKF changeset before mutating `FlowState`:

```kotlin
if (command.step == FlowStepType.FEATURES && okfFeatureService?.hasPending(command.projectId) == true) {
    throw OkfChangesetPendingException(command.projectId)
}
```

In `syncWizardFeatureTasks()`, return immediately for an OKF-active project:

```kotlin
if (step != FlowStepType.FEATURES || taskService == null) return
if (okfFeatureService?.isActive(projectId) == true) return
```

This preserves Tasks as an explicit non-goal and prevents inbound Git sync from triggering LLM plan generation.

- [ ] **Step 6: Run focused Wizard tests**

Run the command from Step 2. Expected: legacy tests and new active-mode tests all pass.

- [ ] **Step 7: Commit Wizard cutover**

```bash
git add backend/src/main/kotlin/com/agentwork/productspecagent/service/okf/OkfFeatureService.kt backend/src/main/kotlin/com/agentwork/productspecagent/service/WizardService.kt backend/src/main/kotlin/com/agentwork/productspecagent/service/WizardStepCompletion.kt backend/src/test/kotlin/com/agentwork/productspecagent/service/okf/OkfFeatureServiceTest.kt backend/src/test/kotlin/com/agentwork/productspecagent/service/WizardServiceTest.kt backend/src/test/kotlin/com/agentwork/productspecagent/service/WizardStepCompletionServiceTest.kt
git commit -m "feat(okf): route active features through git changesets"
```

## Task 9: Expose the OKF REST Workflow

**Files:**
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfApiModels.kt`
- Create: `backend/src/main/kotlin/com/agentwork/productspecagent/api/OkfController.kt`
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/api/GlobalExceptionHandler.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/api/OkfControllerTest.kt`

- [ ] **Step 1: Write failing MockMvc contract tests**

Test authenticated connection, credential-bearing URL rejection, status, dry-run, prepare, pending diff, approve, sync, conflict response, and conflict resolution. Use mocked services; JGit behavior is already covered below the controller.

```kotlin
mockMvc.perform(
    put("/api/v1/projects/$projectId/okf/connection")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"remoteUrl":"https://git.example/project.git","baseBranch":"main"}""")
).andExpect(status().isOk())
 .andExpect(jsonPath("$.connection.canonicalBranch").value("productspec/okf"))
```

- [ ] **Step 2: Run controller tests and verify they fail**

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.api.OkfControllerTest"
```

Expected: 404 or compilation failure because the endpoint does not exist.

- [ ] **Step 3: Add exact API DTOs and routes**

Add:

```kotlin
@Serializable data class ConnectOkfRequest(val remoteUrl: String, val baseBranch: String = "main")
@Serializable data class ResolveOkfConflictRequest(val resolution: OkfConflictResolution)
@Serializable data class OkfStatusResponse(
    val state: OkfProjectState,
    val pending: OkfChangeset? = null,
    val conflicts: List<OkfConflict> = emptyList(),
)
```

Implement these routes under `/api/v1/projects/{projectId}/okf`:

```text
PUT  /connection
GET  /status
POST /sync
POST /migration/dry-run
POST /migration/prepare
GET  /changesets/pending
POST /changesets/{changesetId}/approve
POST /changesets/{changesetId}/resolve
```

Connection updates persist only URL and branch names. Return `400` for invalid URLs/profile input, `409` for pending changes, stale remote, or conflict, `502` for Git authentication/push failure, and `422` for invalid remote bundle content.

- [ ] **Step 4: Run controller and security tests**

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.api.OkfControllerTest" --tests "com.agentwork.productspecagent.api.SecurityIntegrationTest"
```

Expected: authenticated routes pass and anonymous access returns 401 through the existing default security rule.

- [ ] **Step 5: Commit the REST workflow**

```bash
git add backend/src/main/kotlin/com/agentwork/productspecagent/domain/okf/OkfApiModels.kt backend/src/main/kotlin/com/agentwork/productspecagent/api/OkfController.kt backend/src/main/kotlin/com/agentwork/productspecagent/api/GlobalExceptionHandler.kt backend/src/test/kotlin/com/agentwork/productspecagent/api/OkfControllerTest.kt
git commit -m "feat(okf): expose migration and sync api"
```

## Task 10: Add the Frontend Sync, Diff, and Conflict Workflow

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/lib/stores/wizard-store.ts`
- Create: `frontend/src/lib/stores/okf-store.ts`
- Create: `frontend/src/components/okf/OkfDiffView.tsx`
- Create: `frontend/src/components/okf/OkfSyncDialog.tsx`
- Modify: `frontend/src/components/explorer/ExplorerPanel.tsx`
- Modify: `frontend/src/components/wizard/steps/features/FeaturesGraphEditor.tsx`

- [ ] **Step 1: Add exact API types and client functions**

Add string unions matching the backend enums and these functions:

```typescript
export type OkfMode = "LEGACY" | "MIGRATION_READY" | "OKF_ACTIVE";
export type OkfSyncStatus = "CLEAN" | "INCOMING" | "PROPOSAL_PENDING" | "CONFLICT" | "INVALID_REMOTE" | "PUSH_FAILED";
export type OkfConflictResolution = "REMOTE" | "PROPOSED";

export const getOkfStatus = (projectId: string) =>
  apiFetch<OkfStatusResponse>(`/api/v1/projects/${projectId}/okf/status`);

export const connectOkf = (projectId: string, remoteUrl: string, baseBranch: string) =>
  apiFetch<OkfStatusResponse>(`/api/v1/projects/${projectId}/okf/connection`, {
    method: "PUT",
    body: JSON.stringify({ remoteUrl, baseBranch }),
  });

export const syncOkf = (projectId: string) =>
  apiFetch<OkfStatusResponse>(`/api/v1/projects/${projectId}/okf/sync`, { method: "POST" });

export const dryRunOkfMigration = (projectId: string) =>
  apiFetch<OkfMigrationPreview>(`/api/v1/projects/${projectId}/okf/migration/dry-run`, { method: "POST" });

export const prepareOkfMigration = (projectId: string) =>
  apiFetch<OkfChangeset>(`/api/v1/projects/${projectId}/okf/migration/prepare`, { method: "POST" });

export const approveOkfChangeset = (projectId: string, changesetId: string) =>
  apiFetch<OkfChangeset>(`/api/v1/projects/${projectId}/okf/changesets/${changesetId}/approve`, { method: "POST" });

export const resolveOkfConflict = (
  projectId: string,
  changesetId: string,
  resolution: OkfConflictResolution,
) => apiFetch<OkfChangeset | null>(`/api/v1/projects/${projectId}/okf/changesets/${changesetId}/resolve`, {
  method: "POST",
  body: JSON.stringify({ resolution }),
});
```

- [ ] **Step 2: Add the Zustand workflow store**

Use one store keyed by the currently loaded project:

```typescript
interface OkfStoreState {
  projectId: string | null;
  status: OkfStatusResponse | null;
  migration: OkfMigrationPreview | null;
  loading: boolean;
  error: string | null;
  dialogOpen: boolean;
  setDialogOpen(open: boolean): void;
  refresh(projectId: string): Promise<void>;
  connect(projectId: string, remoteUrl: string, baseBranch: string): Promise<void>;
  dryRun(projectId: string): Promise<void>;
  prepare(projectId: string): Promise<void>;
  approve(projectId: string): Promise<void>;
  sync(projectId: string): Promise<void>;
  resolve(projectId: string, resolution: OkfConflictResolution): Promise<void>;
  reset(): void;
}
```

After `approve`, `sync`, or `resolve`, always reload status from the backend. Preserve backend error messages; do not replace them with a generic alert.

- [ ] **Step 3: Implement a stable file diff view**

`OkfDiffView` receives `OkfFileOperation[]`, orders by `path`, displays operation type and path, and renders basis and proposed content side by side. Do not add a diff dependency in this slice.

```tsx
export function OkfDiffView({ operations }: { operations: OkfFileOperation[] }) {
  return (
    <div className="space-y-3">
      {[...operations].sort((a, b) => a.path.localeCompare(b.path)).map((operation) => (
        <section key={`${operation.type}:${operation.path}`} className="border rounded-md overflow-hidden">
          <header className="px-3 py-2 border-b text-xs font-medium">
            {operation.type} {operation.previousPath ? `${operation.previousPath} -> ` : ""}{operation.path}
          </header>
          <div className="grid min-w-0 grid-cols-2 divide-x">
            <pre className="max-h-64 overflow-auto p-3 text-xs whitespace-pre">{operation.baseContent ?? ""}</pre>
            <pre className="max-h-64 overflow-auto p-3 text-xs whitespace-pre">{operation.content ?? ""}</pre>
          </div>
        </section>
      ))}
    </div>
  );
}
```

- [ ] **Step 4: Implement the sync dialog states**

`OkfSyncDialog` must render exactly one primary workflow at a time:

1. no connection: remote URL and base branch form
2. `LEGACY`: migration dry-run, issue list, and prepare button
3. pending: operation diff, blocking errors, and approve button
4. conflict: base, remote, proposed content and explicit Remote/Proposed resolution buttons
5. active clean: last commit and Sync button
6. push failed: retained diff and Retry approval button

Disable approval when any issue has severity `ERROR`. Use Lucide `GitBranch`, `RefreshCw`, `GitCompare`, `AlertTriangle`, and `Check` icons.

- [ ] **Step 5: Integrate status into Explorer and the graph editor**

In `ExplorerPanel`, load OKF status with the files and replace the generic badge only when a connection exists. Add an icon button with tooltip `OKF-Synchronisation` that opens the dialog.

In `FeaturesGraphEditor`, refresh OKF status after the debounced semantic fingerprint changes. When status is `PROPOSAL_PENDING`, show an icon-plus-text `Aenderungen pruefen` command that opens the same dialog. After approval or sync, call both `loadWizard(projectId)` and the Explorer reload callback so Rete and `docs/okf/` update from the confirmed projection.

In `wizard-store.ts`, import the existing `ApiError` and inspect `(error.body as { error?: string }).error`. When completion returns `OKF_CHANGESET_PENDING`, call `useOkfStore.getState().setDialogOpen(true)`, keep the FEATURES step active, and do not append a generic system-error chat message.

- [ ] **Step 6: Run frontend static checks**

```bash
cd frontend
npm run lint
npm run build
```

Expected: ESLint reports no new errors and Next.js production build completes.

- [ ] **Step 7: Commit the frontend workflow**

```bash
git add frontend/src/lib/api.ts frontend/src/lib/stores/okf-store.ts frontend/src/lib/stores/wizard-store.ts frontend/src/components/okf/OkfDiffView.tsx frontend/src/components/okf/OkfSyncDialog.tsx frontend/src/components/explorer/ExplorerPanel.tsx frontend/src/components/wizard/steps/features/FeaturesGraphEditor.tsx
git commit -m "feat(okf): add feature sync review workflow"
```

## Task 11: Integrate Explorer, Export, and Legacy Scaffold Cleanup

**Files:**
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/storage/ProjectStorage.kt`
- Modify: `backend/src/main/kotlin/com/agentwork/productspecagent/service/ProjectService.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/service/ProjectServiceTest.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/api/FileControllerTest.kt`
- Test: `backend/src/test/kotlin/com/agentwork/productspecagent/export/ExportPackageBuilderTest.kt`

- [ ] **Step 1: Write failing integration tests for active projects**

Assert that cutover removes only derived `docs/features/`, preserves Tasks/uploads/design, exposes `docs/okf/index.md` through `GET /files`, and includes `docs/okf/**` in ZIP export.

```kotlin
@Test
fun `active okf project suppresses legacy feature scaffold only`() {
    activateOkf(projectId)
    storage.saveDocsFile(projectId, "docs/features/00-feature-set-overview.md", "legacy")
    storage.saveDocsFile(projectId, "docs/tasks/task-1.json", "{}")

    service.regenerateDocsScaffold(projectId)

    val paths = service.listDocsFiles(projectId).map { it.first }
    assertFalse(paths.any { it.startsWith("docs/features/") })
    assertTrue("docs/tasks/task-1.json" in paths)
    assertTrue("docs/okf/index.md" in paths)
}
```

- [ ] **Step 2: Run focused integration tests and verify they fail**

```bash
cd backend
./gradlew test --tests "com.agentwork.productspecagent.service.ProjectServiceTest" --tests "com.agentwork.productspecagent.api.FileControllerTest" --tests "com.agentwork.productspecagent.export.ExportPackageBuilderTest"
```

Expected: active projects still regenerate legacy feature docs or fixtures cannot activate OKF.

- [ ] **Step 3: Add narrow legacy-scaffold cleanup**

Add to `ProjectStorage`:

```kotlin
fun deleteDocsDirectory(projectId: String, relativePrefix: String) {
    require(relativePrefix.startsWith("docs/") && relativePrefix.endsWith("/"))
    objectStore.deletePrefix("projects/$projectId/$relativePrefix")
}
```

Inject `OkfStateStorage? = null` into `ProjectService`. At the beginning of `generateDocsScaffold`, when the mode is `OKF_ACTIVE`, delete only `docs/features/` and return without invoking `DocsScaffoldGenerator`. Existing direct test construction must continue to use the default `null` dependency.

No `ProjectPackageAssembler` code change is required: it already exports all non-generated `docs/` files, and the synchronized raw mirror lives at `docs/okf/`.

- [ ] **Step 4: Run Explorer and export integration tests**

Run the command from Step 2. Expected: all focused tests pass and legacy preservation tests remain green.

- [ ] **Step 5: Commit Explorer and export integration**

```bash
git add backend/src/main/kotlin/com/agentwork/productspecagent/storage/ProjectStorage.kt backend/src/main/kotlin/com/agentwork/productspecagent/service/ProjectService.kt backend/src/test/kotlin/com/agentwork/productspecagent/service/ProjectServiceTest.kt backend/src/test/kotlin/com/agentwork/productspecagent/api/FileControllerTest.kt backend/src/test/kotlin/com/agentwork/productspecagent/export/ExportPackageBuilderTest.kt
git commit -m "feat(okf): expose canonical bundle in explorer and export"
```

## Task 12: Verify Migration, External Sync, Rete Selection, and Conflict UI

**Files:**
- Create: `frontend/e2e/fixtures/okf-git-fixture.ts`
- Create: `frontend/e2e/okf-feature-sync.spec.ts`
- Modify: `frontend/e2e/features-graph.spec.ts`

- [ ] **Step 1: Add a temporary Git remote fixture**

Use Node `mkdtempSync` and `execFileSync("git", args, { cwd })`; do not construct Git internals manually.

```typescript
export function createOkfRemote(): { remoteUrl: string; cleanup(): void } {
  const root = mkdtempSync(join(tmpdir(), "productspec-okf-e2e-"));
  const bare = join(root, "remote.git");
  const seed = join(root, "seed");
  execFileSync("git", ["init", "--bare", bare]);
  mkdirSync(seed);
  execFileSync("git", ["init", "-b", "main"], { cwd: seed });
  execFileSync("git", ["config", "user.email", "e2e@example.com"], { cwd: seed });
  execFileSync("git", ["config", "user.name", "E2E"], { cwd: seed });
  writeFileSync(join(seed, "README.md"), "# E2E\n");
  execFileSync("git", ["add", "README.md"], { cwd: seed });
  execFileSync("git", ["commit", "-m", "test: seed remote"], { cwd: seed });
  execFileSync("git", ["remote", "add", "origin", pathToFileURL(bare).toString()], { cwd: seed });
  execFileSync("git", ["push", "-u", "origin", "main"], { cwd: seed });
  return { remoteUrl: pathToFileURL(bare).toString(), cleanup: () => rmSync(root, { recursive: true, force: true }) };
}
```

- [ ] **Step 2: Add the full OKF feature E2E test**

The test must:

1. seed and authenticate a project,
2. add two features and an edge in legacy Wizard data,
3. connect the temporary remote,
4. run migration dry-run and assert zero errors,
5. prepare and approve migration,
6. reload the workspace and select both Rete nodes without page errors,
7. open Explorer and read `docs/okf/features/*.md`,
8. clone the remote externally, edit a different feature document, commit, and push,
9. trigger sync and assert the changed title appears in Rete,
10. create a local pending edit plus an external edit to the same feature,
11. assert the conflict UI shows base, remote, and proposed content,
12. choose Remote and verify the pending conflict clears.

Use API calls for repository setup and UI actions for diff approval, sync, feature selection, and conflict resolution.

- [ ] **Step 3: Keep the acceptance-criteria regression in both modes**

Extend `features-graph.spec.ts` with an OKF-active feature whose Markdown contains one task-list criterion. Double-click its Rete node and assert `1 AC`, the criterion text, and an empty `pageErrors` array. Do not remove or weaken the existing legacy-string regression.

- [ ] **Step 4: Run the focused E2E suite**

Start the application with `OKF_GIT_ALLOW_FILE_REMOTES=true`, then run:

```bash
cd frontend
npm run test:e2e -- okf-feature-sync.spec.ts features-graph.spec.ts
```

Expected: migration, external sync, same-document conflict, Rete selection, and both acceptance-criteria regressions pass in Chromium.

- [ ] **Step 5: Commit the E2E coverage**

```bash
git add frontend/e2e/fixtures/okf-git-fixture.ts frontend/e2e/okf-feature-sync.spec.ts frontend/e2e/features-graph.spec.ts
git commit -m "test(okf): cover feature migration and git sync"
```

## Task 13: Run Full Verification and Record the Slice Boundary

**Files:**
- Modify: `docs/superpowers/specs/2026-07-17-okf-source-of-truth-design.md`

- [ ] **Step 1: Run the complete backend suite**

```bash
cd backend
./gradlew test
```

Expected: all JUnit tests pass.

- [ ] **Step 2: Build the backend artifact**

```bash
cd backend
./gradlew bootJar
```

Expected: `BUILD SUCCESSFUL` and a JAR under `backend/build/libs/`.

- [ ] **Step 3: Run frontend lint and production build**

```bash
cd frontend
npm run lint
npm run build
```

Expected: no new lint errors and a successful Next.js build.

- [ ] **Step 4: Run the full Playwright suite**

With backend, frontend, MinIO, and local file remotes enabled:

```bash
cd frontend
npm run test:e2e
```

Expected: all Chromium tests pass.

- [ ] **Step 5: Add an implementation-status note to the approved design**

Append a short `Implementation Status` section stating that slice 1 is complete and listing the exact remaining slices: Decisions, Architecture, agent write tools, and legacy cleanup. Do not mark those slices implemented.

```markdown
## Implementation Status

- Slice 1 complete: OKF Core, feature profile, Git sync, migration, Explorer/export, and Rete projection.
- Remaining: Decisions, Architecture, agent write tools, and final legacy cleanup.
```

- [ ] **Step 6: Commit verification metadata**

```bash
git add docs/superpowers/specs/2026-07-17-okf-source-of-truth-design.md
git commit -m "docs(okf): record feature sync slice"
```

## Execution Notes

- Run each task in order. Tasks 1-4 contain no Git network operations and should stay fast.
- Use temporary directories for all JGit tests; never point tests at the ProductSpecAgent checkout.
- Do not stage or commit the currently unrelated `docs/examples/okf/`, `docs/agents/`, or pre-existing frontend working-tree changes unless they are explicitly part of the task being executed.
- The expected-head check and non-force push are the cross-instance correctness boundary. The in-memory project lock only prevents duplicate work inside one backend process.
- Any new profile field requires a profile-version decision; do not add fields opportunistically during this slice.
