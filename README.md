<h1 align="center">Product Spec Agent</h1>

<p align="center">
  <img src="logo.png" alt="Product Spec Agent logo" width="240">
</p>

<p align="center">
  <strong>Turn early product ideas into structured specs, plans, tasks, and agent-ready handoff artifacts.</strong>
</p>

<p align="center">
  <a href="https://github.com/arkadiuszczarnik/ProductSpecAgent"><img alt="Repository" src="https://img.shields.io/badge/github-ProductSpecAgent-181717?logo=github"></a>
  <img alt="Next.js" src="https://img.shields.io/badge/Next.js-16-black?logo=nextdotjs">
  <img alt="React" src="https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=061018">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-4-6DB33F?logo=springboot&logoColor=white">
  <img alt="Docker" src="https://img.shields.io/badge/Docker-ready-2496ED?logo=docker&logoColor=white">
</p>

Product Spec Agent is a spec-driven product workspace for turning rough ideas into implementation-ready product documentation. It guides product owners, founders, and engineering teams through discovery, scope, decisions, clarifications, feature planning, consistency checks, and AI-coding-agent handoff.

The goal is simple: move from "we have an idea" to a clean, versionable repository of product artifacts that can be used by tools like Codex, Claude Code, and other AI-assisted development workflows.

## Highlights

- **Guided idea-to-spec workflow**: capture product context step by step instead of starting from a blank document.
- **Structured decisions and clarifications**: surface open questions, trade-offs, and blockers early.
- **Feature workbench and cockpit**: keep project readiness, feature status, and next actions visible.
- **Spec, plan, and task artifacts**: generate documents that are useful for both humans and coding agents.
- **Agent-ready handoff**: export repository-ready files for downstream implementation work.
- **Modern full-stack architecture**: Next.js frontend, Kotlin/Spring Boot backend, Koog-powered agent orchestration, JWT auth, and S3-compatible storage.

## Demo Flow

1. Create a project from a rough product idea.
2. Complete the guided wizard for problem, audience, features, MVP, decisions, and review.
3. Use the cockpit to inspect readiness and feature progress.
4. Export specs, plans, tasks, and handoff files for implementation.

## Tech Stack

| Area | Stack |
| --- | --- |
| Frontend | Next.js App Router, React, TypeScript, Tailwind CSS, shadcn/ui, Rete.js |
| Backend | Kotlin, Java 21, Spring Boot, Spring Security, Validation |
| Agents | JetBrains Koog, OpenAI or Anthropic-compatible model routing |
| Storage | S3-compatible object storage, MinIO for local development |
| Testing | JUnit 5, Mockito Kotlin, Testcontainers, Playwright, ESLint |
| Deployment | Docker Compose |

## Quickstart

### Prerequisites

- Java 21
- Node.js and npm
- Docker
- An OpenAI API key or compatible Anthropic setup for agent features

### Run the full local stack

```bash
export OPENAI_API_KEY="your-api-key"
./start.sh
```

This starts:

- Backend: `http://localhost:8080`
- Frontend: `http://localhost:3000`
- MinIO console: `http://localhost:9001`

`start.sh` also provisions the local MinIO bucket and sets development defaults for S3, auth, and model routing.

### Run services manually

```bash
# Backend
cd backend
./gradlew bootRun --quiet
```

```bash
# Frontend
cd frontend
npm install
npm run dev
```

## Configuration

Common environment variables:

| Variable | Purpose |
| --- | --- |
| `OPENAI_API_KEY` | Enables OpenAI-backed Koog agents |
| `LLM_PROVIDER` | Selects `openai` or `claude` defaults in `start.sh` |
| `AUTH_JWT_SECRET` | JWT signing secret |
| `AUTH_ADMIN_EMAILS` | Comma-separated admin allowlist, `*` in local dev |
| `S3_ENDPOINT` | S3 endpoint, local MinIO by default |
| `S3_BUCKET` | Object storage bucket |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | S3 credentials |

For Docker Compose, provide required secrets through `.env`.

## Project Structure

```text
ProductSpecAgent/
├── backend/              # Kotlin/Spring Boot API, agents, auth, storage, exports
├── frontend/             # Next.js app, shared UI, stores, Playwright tests
├── docs/                 # Architecture docs, feature specs, product documentation
├── asset-bundles/        # Reusable design and agent assets
├── docker-compose.yml    # Backend + MinIO local runtime
└── start.sh              # One-command local development stack
```

## Development Commands

| Command | Description |
| --- | --- |
| `./start.sh` | Start MinIO, backend, and frontend |
| `cd backend && ./gradlew test` | Run backend tests |
| `cd backend && ./gradlew bootJar` | Build backend JAR |
| `cd frontend && npm run lint` | Run ESLint |
| `cd frontend && npm run build` | Build the Next.js app |
| `cd frontend && npm run test:e2e` | Run Playwright tests |
| `docker-compose up` | Run backend and MinIO with Docker Compose |

## Documentation

- [Monorepo structure](docs/architecture/monorepo-structure.md)
- [REST API design](docs/architecture/rest-api.md)
- [Authentication](docs/architecture/auth.md)
- [Persistence](docs/architecture/persistence.md)
- [Koog agents](docs/architecture/koog-agents.md)
- [Feature set overview](docs/features/00-feature-set-overview.md)
- [Frontend design system](docs/frontend/design-system.md)
