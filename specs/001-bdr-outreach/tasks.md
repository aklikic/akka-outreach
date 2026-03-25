# Tasks: BDR LinkedIn Outreach Tool

**Input**: Design documents from `/specs/001-bdr-outreach/`
**Prerequisites**: plan.md ✅ spec.md ✅ research.md ✅ data-model.md ✅ contracts/ ✅

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1–US4)
- All source paths are relative to the repository root

---

## Phase 1: Setup

**Purpose**: Project initialization — add dependencies and configure the runtime environment.

- [ ] T001 Add Apache Commons CSV 1.10.0 dependency to `pom.xml`
- [ ] T002 Add `akka.javasdk.agent.model-provider = googleai-gemini` and `GOOGLE_API_KEY` mapping to `src/main/resources/application.conf`
- [ ] T003 Create `src/main/resources/static-resources/` directory (placeholder `index.html` sufficient for now)
- [ ] T004 Run `mvn compile` to verify baseline build passes

---

## Phase 2: Foundational (Domain Layer)

**Purpose**: Immutable domain records that all components depend on. No Akka imports; independently compilable.

**⚠️ CRITICAL**: All user story phases depend on this phase being complete first.

- [ ] T005 [P] Create `AccountStatus` enum (`NEW`, `MESSAGE_READY`, `RESPONDED`, `ERROR`) in `src/main/java/com/example/domain/AccountStatus.java`
- [ ] T006 [P] Create `OutreachMessage` record and `MessageType` enum (`CONNECTION_REQUEST`, `FOLLOW_UP_1`, `FOLLOW_UP_2`) in `src/main/java/com/example/domain/OutreachMessage.java`
- [ ] T007 [P] Create `SignalPool` utility class with `List<String> signalsFor(String companyName)` — 5 fixed pools of 3 signals, selected deterministically via `Math.abs(companyName.hashCode()) % 5` — in `src/main/java/com/example/domain/SignalPool.java`
- [ ] T008 Create `Account` domain record with fields `accountId`, `companyName`, `industry`, `website`, `targetPersona`, `notes`, `status`, `signals` (`List<String>`), `messages` (`List<OutreachMessage>`), `hubspotId`, `createdAt` (`Instant`) and `with*` immutable mutation methods in `src/main/java/com/example/domain/Account.java` (depends on T005, T006)
- [ ] T009 Run `mvn compile` to verify domain layer compiles cleanly

**Checkpoint**: Domain layer complete — user story implementation can now begin.

---

## Phase 3: User Story 1 — Single Account: Create & Generate Messages (Priority: P1) 🎯 MVP

**Goal**: A BDR can enter one company, trigger AI generation, and see three persona-aware LinkedIn messages grounded in company signals — all from a single-page UI with real-time status updates.

**Independent Test**: POST `/accounts` → POST `/accounts/{id}/generate` → poll `GET /events` until status=`MESSAGE_READY` → verify 3 signals and 3 messages appear.

### Agents

- [ ] T010 [P] [US1] Create `SignalResearchAgent` with `@Component(id = "signal-research")`, `@AgentRole` description, single command handler `research(CompanyDetails details)` → `List<String>`, and `@FunctionTool getSignalsForCompany(String companyName)` calling `SignalPool.signalsFor` in `src/main/java/com/example/application/SignalResearchAgent.java`
- [ ] T011 [P] [US1] Create `MessageGenerationAgent` with `@Component(id = "message-generation")`, `@AgentRole` description, single command handler `generate(GenerateRequest request)` → `Messages`, using `responseConformsTo(Messages.class)`; define inner records `GenerateRequest(CompanyDetails details, List<String> signals)` and `Messages(List<OutreachMessage> messages)` in `src/main/java/com/example/application/MessageGenerationAgent.java`

### Workflow

- [ ] T012 [US1] Create `OutreachGenerationWorkflow` with `@Component(id = "outreach-generation")`, `Account` as state type, and all command handlers:
  - `create(CreateRequest)` — initialise state, status=NEW
  - `startGeneration()` — transition to `researchSignalsStep`
  - `markAsResponded()` — update status=RESPONDED
  - `recordHubSpotSync(String hubspotId)` — store hubspotId in state
  - `notifyDone(String bulkJobId)` — no-op placeholder (wired in US4)

  Steps with `WorkflowSettings` using 60s timeout for agent steps, 5s for status steps, and `defaultStepRecovery(maxRetries(1).failoverTo(OutreachGenerationWorkflow::errorStep))`:
  - `researchSignalsStep` → calls `SignalResearchAgent.research`, stores signals, transitions to `generateMessagesStep`
  - `generateMessagesStep` → calls `MessageGenerationAgent.generate`, stores messages, transitions to `updateStatusStep`
  - `updateStatusStep` → sets status=MESSAGE_READY, `thenEnd`
  - `errorStep` → sets status=ERROR, `thenEnd`

  Session ID = workflow ID (`commandContext().workflowId()`) for both agents.
  File: `src/main/java/com/example/application/OutreachGenerationWorkflow.java` (depends on T010, T011)

### View

- [ ] T013 [US1] Create `AccountsByStatusView` with `@Consume.FromWorkflow(OutreachGenerationWorkflow.class)`, `onUpdate(Account state)` handler mapping all `Account` fields to `AccountEntry` row; queries `getAllAccounts()` (`SELECT * AS entries FROM accounts ORDER BY createdAt ASC`) and `getAccountById(String accountId)` (`SELECT * FROM accounts WHERE accountId = :accountId`) in `src/main/java/com/example/application/AccountsByStatusView.java` (depends on T012)

### Endpoint

- [ ] T014 [US1] Create `OutreachEndpoint` with `@HttpEndpoint("/")`, `@Acl(allow = ALL)`, injecting `ComponentClient`:
  - `GET /` — serve `index.html` via `HttpResponses.staticResource("static-resources/index.html")`
  - `POST /accounts` — generate UUID accountId, call `OutreachGenerationWorkflow.create`, return 201 `{accountId, status}`
  - `GET /accounts` — call `AccountsByStatusView.getAllAccounts`, return entries
  - `POST /accounts/{accountId}/generate` — call `OutreachGenerationWorkflow.startGeneration`, return 200 `{accountId, status}`

  File: `src/main/java/com/example/api/OutreachEndpoint.java` (depends on T012, T013)

- [ ] T015 [US1] Add `GET /events` SSE endpoint to `OutreachEndpoint`: inject `Materializer`; use `Source.tick(Duration.ZERO, Duration.ofSeconds(2), "tick").mapAsync(1, ...)` to query `AccountsByStatusView.getAllAccounts()` and serialize result as `data: {...}\n\n` frames; return as `text/event-stream` `HttpResponse` in `src/main/java/com/example/api/OutreachEndpoint.java` (depends on T014)

### Single Account UI

- [ ] T016 [US1] Implement Single Account tab in `src/main/resources/static-resources/index.html`:
  - Two-tab layout (Single Account / Bulk Upload) using plain HTML/CSS/JS
  - Company creation form (`companyName`, `industry`, `website`, `targetPersona`, `notes`)
  - Duplicate company name warning (check current account list client-side before submit)
  - After creation: account detail view with Generate Messages button, status badge, loading indicator
  - Signals and three messages displayed when status=MESSAGE\_READY
  - Persistent list of all accounts below form; click to open detail view
  - `EventSource` connecting to `GET /events` for real-time status updates

### Tests

- [ ] T017 [US1] Create `SignalResearchAgentTest` using `TestKitSupport` + `TestModelProvider`; verify `research(details)` returns 3 signals matching the deterministic pool for the given company name in `src/test/java/com/example/application/SignalResearchAgentTest.java`
- [ ] T018 [US1] Create `MessageGenerationAgentTest` using `TestKitSupport` + `TestModelProvider`; mock model to return valid `Messages` JSON; verify 3 `OutreachMessage` records returned with correct `MessageType` values in `src/test/java/com/example/application/MessageGenerationAgentTest.java`
- [ ] T019 [US1] Create `OutreachGenerationWorkflowTest` using `TestKitSupport`; cover full happy path (create → startGeneration → MESSAGE\_READY with signals and messages) and error path (agent failure → ERROR status) in `src/test/java/com/example/application/OutreachGenerationWorkflowTest.java`
- [ ] T020 [US1] Create `OutreachEndpointIntegrationTest` using `TestKitSupport` + `httpClient`; test `POST /accounts` returns 201 with accountId, `POST /accounts/{id}/generate` returns 200, `GET /accounts` returns account list in `src/test/java/com/example/api/OutreachEndpointIntegrationTest.java`
- [ ] T021 [US1] Run `mvn verify` to confirm US1 test suite passes

**Checkpoint**: US1 fully functional — BDR can create an account, trigger AI generation, and see messages via the UI.

---

## Phase 4: User Story 2 — Mark as Responded & Auto-Sync to HubSpot (Priority: P2)

**Goal**: After receiving a LinkedIn reply, a BDR marks the account as responded and the system automatically syncs it to HubSpot, surfacing the record ID without any extra steps.

**Independent Test**: Given a MESSAGE\_READY account, `POST /accounts/{id}/respond` → verify status=RESPONDED → verify hubspotId format `hs_XXXXXX` appears via `GET /events`.

### HubSpot Stub

- [ ] T022 [P] [US2] Create `HubSpotService` plain Java class (no Akka annotations) with `String syncAccount(String companyName)` returning `String.format("hs_%06d", Math.abs(companyName.hashCode()) % 1_000_000)` in `src/main/java/com/example/domain/HubSpotService.java`

### Consumer

- [ ] T023 [US2] Create `HubSpotSyncConsumer` with `@Component(id = "hubspot-sync")`, `@Consume.FromWorkflow(OutreachGenerationWorkflow.class)`, `onUpdate(Account state)` handler that triggers when `state.status() == RESPONDED && state.hubspotId() == null`: compute hubspotId via `HubSpotService.syncAccount`, then call `OutreachGenerationWorkflow.recordHubSpotSync(hubspotId)` via injected `ComponentClient` in `src/main/java/com/example/application/HubSpotSyncConsumer.java` (depends on T022)

### Endpoint

- [ ] T024 [US2] Add `POST /accounts/{accountId}/respond` to `OutreachEndpoint`: call `OutreachGenerationWorkflow.markAsResponded()`, return 200 `{accountId, status: "RESPONDED"}` in `src/main/java/com/example/api/OutreachEndpoint.java`

### UI

- [ ] T025 [US2] Add to Single Account tab in `src/main/resources/static-resources/index.html`:
  - Mark as Responded button (visible when status=MESSAGE\_READY)
  - RESPONDED status badge with distinct colour
  - HubSpot confirmation area displaying `hubspotId` once populated via SSE update

### Tests

- [ ] T026 [US2] Extend `OutreachEndpointIntegrationTest` with respond flow: `POST /accounts/{id}/respond` → assert 200 RESPONDED → await `hubspotId` via `GET /accounts` → assert format `hs_\d{6}` in `src/test/java/com/example/api/OutreachEndpointIntegrationTest.java`
- [ ] T027 [US2] Run `mvn verify` to confirm US2 test suite passes

**Checkpoint**: US2 complete — responding to a prospect auto-syncs to HubSpot with no manual CRM entry.

---

## Phase 5: User Story 4 — Bulk CSV Upload & Generate All (Priority: P2)

**Goal**: A BDR uploads a CSV of up to 50 companies, all accounts are created immediately, and a single "Generate All" click runs the AI pipeline sequentially across every row with real-time progress visible in the table.

**Independent Test**: Upload 3-row CSV → verify 3 accounts created → click Generate All → poll `GET /events` until all rows reach MESSAGE\_READY (or ERROR) → expand row to verify signals and messages.

### Workflow

- [ ] T028 [US4] Create `BulkGenerationWorkflow` with `@Component(id = "bulk-generation")`, state `State(List<String> accountIds, int currentIndex)`, and:
  - `start(StartRequest(bulkJobId, accountIds))` command — initialise state, transition to `delegateStep`
  - `delegateStep` — call `OutreachGenerationWorkflow.startGeneration(accountIds[currentIndex])` passing `bulkJobId`; pause (no transition — waits for `notifyDone`)
  - `notifyDone(NotifyDoneRequest(accountId, boolean success))` command — advance `currentIndex`; if more accounts remain transition to `delegateStep`, else `thenEnd`

  Also wire `OutreachGenerationWorkflow.errorStep` to call `BulkGenerationWorkflow.notifyDone` (update T012's errorStep and updateStatusStep to call `notifyDone` when `bulkJobId` is present in state).
  File: `src/main/java/com/example/application/BulkGenerationWorkflow.java` (depends on T012)

### Endpoint

- [ ] T029 [US4] Add `POST /accounts/bulk` to `OutreachEndpoint`: parse CSV body using Apache Commons CSV (`CSVFormat.DEFAULT.withFirstRecordAsHeader()`); for each row validate required fields (`companyName`, `industry`, `website`, `targetPersona`); create an `OutreachGenerationWorkflow` per valid row; collect rejected rows; return `BulkUploadResult(total, created, rejected)` in `src/main/java/com/example/api/OutreachEndpoint.java` (depends on T028)
- [ ] T030 [US4] Add `POST /accounts/generate-all` to `OutreachEndpoint`: query `AccountsByStatusView` for accounts in NEW or ERROR status; generate UUID `bulkJobId`; call `BulkGenerationWorkflow.start(bulkJobId, accountIds)`; return `{bulkJobId, accountCount}` in `src/main/java/com/example/api/OutreachEndpoint.java` (depends on T029)

### Bulk Upload UI

- [ ] T031 [US4] Implement Bulk Upload tab in `src/main/resources/static-resources/index.html`:
  - CSV file upload area with format hint (`companyName,industry,website,targetPersona,notes`)
  - Upload result summary (total/created/rejected rows)
  - Accounts table: company, industry, persona, status badge, message summary, HubSpot ID, actions column (Generate / Mark Responded / SFDC)
  - Generate All button above table (hidden while batch running)
  - Per-row Generate button for individual re-triggering
  - Expandable detail panel below each row showing full signals and 3 messages
  - Real-time table updates via existing `EventSource` SSE connection

### Tests

- [ ] T032 [US4] Create `BulkGenerationWorkflowTest` using `TestKitSupport`; cover: start with 2 accounts → first `delegateStep` → `notifyDone` advances to second → second `notifyDone` ends workflow in `src/test/java/com/example/application/BulkGenerationWorkflowTest.java`
- [ ] T033 [US4] Run `mvn verify` to confirm US4 test suite passes

**Checkpoint**: US4 complete — BDR can upload a CSV and watch all rows progress in real time.

---

## Phase 6: User Story 3 — Salesforce Export (Priority: P3)

**Goal**: A BDR can retrieve a structured Salesforce-ready payload for any account with a single click.

**Independent Test**: Given any account (any status), `GET /accounts/{id}/sfdc` → verify response contains company name, industry, website, target persona, status, signals, messages, and hubspotId.

### Endpoint

- [ ] T034 [US3] Add `GET /accounts/{accountId}/sfdc` to `OutreachEndpoint`: query `AccountsByStatusView.getAccountById`, map to `SfdcPayload` record with fields `Company`, `Industry`, `Website`, `TargetPersona`, `LeadStatus`, `IntentSignals`, `LinkedInMessages` (nested `ConnectionRequest`, `FollowUp1`, `FollowUp2`), `HubSpotId`; return 200 in `src/main/java/com/example/api/OutreachEndpoint.java`

### UI

- [ ] T035 [US3] Add Get SFDC Payload button (visible on all accounts) to both Single Account detail view and Bulk Upload row actions in `src/main/resources/static-resources/index.html`; on click: `GET /accounts/{id}/sfdc` and display formatted JSON in a pre-block panel

**Checkpoint**: US3 complete — SFDC payload available on demand for any account.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T036 Run full `mvn verify` to confirm all tests pass end-to-end
- [ ] T037 [P] Walk through `specs/001-bdr-outreach/quickstart.md` manually to validate single-account and bulk flows work as documented
- [ ] T038 [P] Update `README.md` with build/run instructions (`export GOOGLE_API_KEY=...`, `mvn compile exec:java`) and example `curl` commands for each endpoint

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — **blocks all user story phases**
- **US1 (Phase 3)**: Depends on Phase 2 — no other story dependencies
- **US2 (Phase 4)**: Depends on Phase 2 + US1 workflow (`OutreachGenerationWorkflow`)
- **US4 (Phase 5)**: Depends on Phase 2 + US1 workflow (`OutreachGenerationWorkflow`)
- **US3 (Phase 6)**: Depends on Phase 2 + US1 view (`AccountsByStatusView`)
- **Polish (Phase 7)**: Depends on all desired stories complete

### User Story Dependencies

- **US1 (P1)**: Unblocked after Foundational — implement first, everything else builds on it
- **US2 (P2)**: Requires US1 workflow (`OutreachGenerationWorkflow`) to exist; adds `markAsResponded` + `recordHubSpotSync` usage
- **US4 (P2)**: Requires US1 workflow (`OutreachGenerationWorkflow`) to exist; adds `notifyDone` wiring
- **US3 (P3)**: Requires US1 view (`AccountsByStatusView.getAccountById`); otherwise independent

### Within Each User Story (US1 example)

1. Agents (T010, T011) — parallel, no inter-dependency
2. Workflow (T012) — depends on agents
3. View (T013) — depends on workflow
4. Endpoint (T014, T015) — depends on workflow + view
5. UI (T016) — depends on endpoint being defined
6. Tests (T017–T020) — agents tests parallel; workflow and endpoint tests sequential

---

## Parallel Opportunities

### Foundational phase (Phase 2)

```
T005 AccountStatus.java  ──┐
T006 OutreachMessage.java ──┼─→ T008 Account.java → T009 compile
T007 SignalPool.java      ──┘
```

### US1 implementation

```
T010 SignalResearchAgent.java  ──┐
T011 MessageGenerationAgent.java ┴─→ T012 OutreachGenerationWorkflow.java
                                       → T013 AccountsByStatusView.java
                                       → T014/T015 OutreachEndpoint.java
                                       → T016 index.html

T017 SignalResearchAgentTest    ──┐
T018 MessageGenerationAgentTest ──┴─ (after T010/T011)
T019 OutreachGenerationWorkflowTest  (after T012)
T020 OutreachEndpointIntegrationTest (after T014)
```

---

## Implementation Strategy

### MVP First (User Story 1 only — ~20 tasks)

1. Complete Phase 1 (Setup) → Phase 2 (Domain)
2. Complete Phase 3 (US1): agents → workflow → view → endpoint → UI → tests
3. **STOP and VALIDATE**: run `mvn verify`, open `http://localhost:9000`, create an account, trigger generation, watch SSE update
4. Demo-ready with core value proposition delivered

### Incremental Delivery

| Milestone | Phases | What BDRs can do |
|-----------|--------|------------------|
| MVP | 1 + 2 + 3 | Create account, generate messages, view via UI |
| +HubSpot | + 4 | Mark responded, get automatic HubSpot ID |
| +Bulk | + 5 | Upload CSV, Generate All with real-time progress |
| +SFDC | + 6 | Export structured Salesforce payload |

---

## Task Count Summary

| Phase | Tasks | Story |
|-------|-------|-------|
| Setup | 4 | — |
| Foundational | 5 | — |
| US1 | 12 | P1 |
| US2 | 6 | P2 |
| US4 | 6 | P2 |
| US3 | 2 | P3 |
| Polish | 3 | — |
| **Total** | **38** | |