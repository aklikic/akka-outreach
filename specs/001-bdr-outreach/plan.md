# Implementation Plan: BDR LinkedIn Outreach Tool

**Branch**: `001-bdr-outreach` | **Date**: 2026-03-25 | **Spec**: [spec.md](spec.md)

## Summary

A single-page web tool that lets BDRs enter target companies and receive AI-generated, persona-aware LinkedIn outreach messages grounded in real company signals. Built on Akka SDK 3.5 with a Google Gemini LLM backend, two AI agents (signal research + message generation), two orchestration workflows (single-account pipeline and bulk coordinator), a consumer-driven HubSpot stub, and a streaming SSE endpoint for real-time UI updates. Account state lives entirely in the `OutreachGenerationWorkflow` — no separate entity needed.

## Technical Context

**Language/Version**: Java 21
**Primary Dependencies**: Akka SDK 3.5.16 (parent POM), Apache Commons CSV 1.10.0
**Storage**: Akka Workflow state journal (durable workflow state; ephemeral across restarts — acceptable per spec Assumptions)
**Testing**: JUnit 5, AssertJ, Akka TestKit (`TestKitSupport`, `TestModelProvider`)
**Target Platform**: Local JVM (single command: `mvn compile exec:java`)
**LLM Provider**: Google Gemini via `googleai-gemini` Akka SDK provider; API key via `GOOGLE_API_KEY` env var
**Performance Goals**: Account creation <5s for 50-row CSV; message generation <60s excluding LLM response time (SC-001, SC-003)
**Constraints**: No authentication; no external service accounts beyond Gemini; data need not survive service restart
**Scale/Scope**: Internal BDR tool; small team; no concurrent user isolation needed

## Constitution Check

### I. Akka SDK First ✅
All components use Akka SDK primitives: Workflows, Agents, View, Consumer, HTTP Endpoint. Apache Commons CSV is the only external dependency beyond the SDK, justified for CSV parsing (no equivalent in Akka SDK or stdlib for header-based CSV).

### II. Design Principles ✅
- **Domain independence**: `Account`, `OutreachMessage`, `SignalPool`, `HubSpotService` have no Akka imports.
- **API isolation**: `OutreachEndpoint` defines its own request/response records; domain types not exposed directly.
- **Single responsibility**: Each component has one focused job (research, generate, sync, view, orchestrate).
- **Descriptive naming**: All names are domain-aligned (`OutreachGenerationWorkflow`, `MessageGenerationAgent`, `HubSpotSyncConsumer`).

### III. Test Coverage ✅ (planned)
- `OutreachGenerationWorkflowTest` (integration, `TestKitSupport`)
- `SignalResearchAgentTest` (integration, `TestKitSupport` + `TestModelProvider`)
- `MessageGenerationAgentTest` (integration, `TestKitSupport` + `TestModelProvider`)
- `OutreachEndpointIntegrationTest` (integration, `TestKitSupport`, `httpClient`)

### IV. Simplicity ✅
- HubSpot sync is a plain Java class, not an Akka component.
- Signal stubs are a static utility, not a service.
- SSE is implemented as server-side polling (simple, sufficient for MVP).
- No pagination, no auth, no download functionality.

## Project Structure

### Documentation (this feature)

```text
specs/001-bdr-outreach/
├── plan.md              ← this file
├── spec.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── http-api.md
└── checklists/
    └── requirements.md
```

### Source Code

```text
src/main/java/com/example/
├── domain/
│   ├── Account.java               # State record: all account fields (used as Workflow state)
│   ├── AccountStatus.java         # Enum: NEW, MESSAGE_READY, RESPONDED, ERROR
│   ├── OutreachMessage.java       # Record: messageType (enum), body
│   └── SignalPool.java            # Static deterministic signal pools
│
├── application/
│   ├── OutreachGenerationWorkflow.java  # Single-account pipeline + account state owner
│   ├── BulkGenerationWorkflow.java      # Delegates to OGW per account, awaits notifyDone
│   ├── SignalResearchAgent.java         # Deterministic signal lookup via @FunctionTool
│   ├── MessageGenerationAgent.java      # Gemini-backed message generation
│   ├── AccountsByStatusView.java        # Lists all accounts via @Consume.FromWorkflow
│   └── HubSpotSyncConsumer.java         # Consumes OGW state updates, triggers stub sync
│
└── api/
    └── OutreachEndpoint.java      # REST + SSE + static content

src/main/resources/
├── application.conf               # Gemini model config
└── static-resources/
    └── index.html                 # Single-page UI (HTML + CSS + JS inline)

src/test/java/com/example/
├── application/
│   ├── OutreachGenerationWorkflowTest.java
│   ├── SignalResearchAgentTest.java
│   └── MessageGenerationAgentTest.java
└── api/
    └── OutreachEndpointIntegrationTest.java
```

**Structure Decision**: Single-project layout (Java service with embedded SPA). No separate frontend build — the HTML/CSS/JS is inlined in `index.html` and served via `HttpResponses.staticResource()`.

## Component Responsibilities

### Domain Layer

| Class | Type | Responsibility |
|-------|------|---------------|
| `Account` | record | Immutable account state used as `OutreachGenerationWorkflow` state; `with*` mutation methods; no Akka imports |
| `AccountStatus` | enum | Status values: NEW, MESSAGE_READY, RESPONDED, ERROR |
| `OutreachMessage` | record | Single LinkedIn message with `MessageType` enum |
| `SignalPool` | utility | `signalsFor(companyName)` — deterministic pool selection by hash |

### Application Layer

| Class | Type | Responsibility |
|-------|------|---------------|
| `OutreachGenerationWorkflow` | Workflow | Owns all account state; orchestrates research → generate; handles create/respond/sfdc commands |
| `BulkGenerationWorkflow` | Workflow | Delegates to `OutreachGenerationWorkflow` per account; advances on `notifyDone` push |
| `SignalResearchAgent` | Agent | Single command `research(CompanyDetails)` → signals via stub @FunctionTool |
| `MessageGenerationAgent` | Agent | Single command `generate(GenerateRequest)` → `Messages` struct via Gemini |
| `AccountsByStatusView` | View | `getAllAccounts()`, `getAccountById(id)` via `@Consume.FromWorkflow(OutreachGenerationWorkflow.class)` |
| `HubSpotSyncConsumer` | Consumer | Consumes `OutreachGenerationWorkflow` state updates; syncs when status = RESPONDED |

### API Layer

| Class | Type | Responsibility |
|-------|------|---------------|
| `OutreachEndpoint` | HTTP Endpoint | All REST endpoints, SSE stream, static UI serving |

## Key Design Decisions

### 1. Agent Session Strategy
- `SignalResearchAgent` and `MessageGenerationAgent`: session ID = `accountId`. This allows the agents to share session context if called within the same account's pipeline, and is automatically unique per account.

### 2. OutreachGenerationWorkflow as Account Owner
- `OutreachGenerationWorkflow` (workflow ID = accountId) holds all account state in its `Account` state record. The endpoint creates accounts by calling `OGW.create(accountId, details)`.
- No separate `AccountEntity` — eliminates an entire component layer and its event model.
- `AccountsByStatusView` and `HubSpotSyncConsumer` both use `@Consume.FromWorkflow(OutreachGenerationWorkflow.class)` with `onUpdate(Account state)` handlers.

### 3. Workflow Error Handling
- `OutreachGenerationWorkflow` uses `WorkflowSettings` with `defaultStepTimeout(ofSeconds(60))` for agent steps and `defaultStepRecovery(maxRetries(1).failoverTo(OutreachGenerationWorkflow::errorStep))`.
- The `errorStep` updates workflow state to `ERROR` and optionally calls `BulkGenerationWorkflow.notifyDone(accountId, false)` if a `bulkJobId` is present.

### 4. BulkGenerationWorkflow Push Notification
- `BulkGenerationWorkflow` passes its own workflow ID (`bulkJobId`) when starting each `OutreachGenerationWorkflow`.
- When `OutreachGenerationWorkflow` finishes (success or error), it calls `BulkGenerationWorkflow.notifyDone(accountId)` via `ComponentClient`.
- `BulkGenerationWorkflow` wakes on this command, advances `currentIndex`, and either delegates to the next `OutreachGenerationWorkflow` or ends. No polling required.

### 3. SSE Real-time Updates
- `OutreachEndpoint` injects `Materializer` for stream materialisation.
- `GET /events` uses `Source.tick(Duration.ZERO, Duration.ofSeconds(2), "tick")` → query `AccountsByStatusView` → serialize to SSE `data:` lines.
- The browser reconnects on disconnect automatically (SSE spec). No server-side state for connections.

### 5. Duplicate Company Warning (FR-001)
- Implemented client-side: the frontend checks the current account list (fetched via `GET /accounts` or from SSE state) before enabling the submit button if a company name matches.
- The backend does NOT reject duplicates — it creates the account regardless.

### 6. Static UI
- Single `index.html` file in `src/main/resources/static-resources/`.
- JavaScript fetch API calls the REST endpoints.
- EventSource API connects to `GET /events` for live updates.
- No build tool, no npm, no framework — plain HTML/CSS/JS for internal tool simplicity.

### 7. pom.xml Changes
- Add Apache Commons CSV dependency.
- No other changes to the parent POM (Akka SDK parent handles all other deps).