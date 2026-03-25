# Feature Specification: BDR LinkedIn Outreach Tool

**Feature Branch**: `001-bdr-outreach`
**Created**: 2026-03-25
**Status**: Draft

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Single Account: Create & Generate Messages (Priority: P1)

A BDR enters one target company — name, industry, website, the type of person they're reaching out to (e.g. "VP of Engineering"), and optional notes. They submit the form and an AI-driven process kicks off: it gathers recent company signals (hiring activity, funding rounds, tech initiatives), uses those signals to craft three persona-aware LinkedIn messages, and surfaces all of it on the page. The BDR ends up with three ready-to-send messages grounded in real company context — without writing a word themselves.

**Why this priority**: This is the core value proposition. Everything else builds on it.

**Independent Test**: Can be fully tested by creating one account through the form, triggering generation, and verifying signals and three differentiated messages appear on the page.

**Acceptance Scenarios**:

1. **Given** the BDR is on the Single Account tab, **When** they fill in company name, industry, website, target persona, and optional notes and submit, **Then** a new account is created with status NEW, the account detail view opens, and the new account appears in the list below the form.
1a. **Given** the BDR enters a company name that already exists, **When** they are completing the form, **Then** a warning is shown indicating a duplicate company name exists, and the BDR can choose to proceed or cancel.
2. **Given** an account in NEW status exists, **When** the BDR clicks "Generate Messages", **Then** a loading indicator appears and the AI process starts.
3. **Given** the AI process completes, **When** the BDR views the account, **Then** they see the company's intent signals, three LinkedIn messages (connection request, first follow-up, second follow-up), and the account status has changed to MESSAGE_READY.
4. **Given** two accounts for different target personas (e.g. "VP Engineering" vs "CFO"), **When** messages are generated for each, **Then** the tone and focus of the messages visibly differ to reflect what matters to each persona.

---

### User Story 2 — Mark as Responded & Auto-Sync to HubSpot (Priority: P2)

After sending the LinkedIn messages, the BDR receives a reply. They click "Mark as Responded" on the account. The system automatically syncs the account to HubSpot and shows the BDR a confirmation with the HubSpot record ID — no manual CRM entry required.

**Why this priority**: Eliminates the manual logging step that costs BDRs time after every conversation.

**Independent Test**: Can be fully tested by marking a MESSAGE_READY account as responded and verifying the account shows RESPONDED status and a HubSpot ID.

**Acceptance Scenarios**:

1. **Given** an account in MESSAGE_READY status, **When** the BDR clicks "Mark as Responded", **Then** the account status changes to RESPONDED.
2. **Given** the account is marked RESPONDED, **Then** a HubSpot sync is triggered automatically and the account displays a HubSpot confirmation with the record ID (e.g. `hs_123456`).
3. **Given** an account already in RESPONDED status, **When** the BDR views it, **Then** the HubSpot ID is persistently visible.

---

### User Story 3 — Salesforce Export (Priority: P3)

A BDR wants to move account data into the Salesforce CRM pipeline. They click "Get SFDC Payload" on any account and the tool displays a structured Salesforce-ready export on the page for them to copy or pass downstream.

**Why this priority**: Useful but not blocking — BDRs can operate without it; it removes a manual formatting step.

**Independent Test**: Can be fully tested by requesting the SFDC payload for an existing account and verifying a structured export appears on the page.

**Acceptance Scenarios**:

1. **Given** any account, **When** the BDR clicks "Get SFDC Payload", **Then** a structured Salesforce-ready payload is displayed on the page.
2. **Given** the SFDC payload is displayed, **Then** it includes the company name, industry, website, target persona, status, and generated signals and messages where available.

---

### User Story 4 — Bulk CSV Upload & Generate All (Priority: P2)

A BDR has a list of 50 target companies in a spreadsheet. They export it as a CSV (columns: `companyName`, `industry`, `website`, `targetPersona`, `notes`), upload it to the Bulk Upload tab, and all accounts are created instantly. They click "Generate All" and the AI process runs for every row sequentially. While it runs, they watch the status column update in the table row by row. Afterwards they can expand any row to see the full signals and messages, or act on individual rows.

**Why this priority**: Unlocks the high-volume use case that multiplies BDR throughput.

**Independent Test**: Can be fully tested by uploading a small CSV (3–5 rows), triggering "Generate All", and verifying each row progresses through the status states with signals and messages appearing in the expandable panel.

**Acceptance Scenarios**:

1. **Given** the BDR is on the Bulk Upload tab, **When** they upload a valid CSV file, **Then** all rows are parsed and accounts are created and displayed in a table.
2. **Given** accounts are listed in the table, **When** the BDR clicks "Generate All", **Then** the AI process runs for each account sequentially and the table updates in real time as each account moves from NEW → MESSAGE_READY.
3. **Given** a row in the table, **When** the BDR clicks "Generate" for a single row, **Then** the AI process runs for just that account.
4. **Given** an account has completed generation, **When** the BDR clicks "Generate" on that row, **Then** a detail panel expands below the table showing the account's signals and full messages.
5. **Given** a row in RESPONDED status, **When** the BDR views the table, **Then** the HubSpot ID is visible in the HubSpot ID column.
6. **Given** an invalid CSV (missing required columns), **When** uploaded, **Then** the system shows a clear error describing which columns are missing.

---

### Edge Cases

- What happens when the AI generation process fails mid-way (e.g. model timeout)? The account should not be left in an indeterminate state; progress made before the failure should be preserved and the process should be resumable.
- What happens when a CSV row is missing a required field (company name or target persona)? That row should be rejected with a clear error; valid rows should still be created.
- What happens when a CSV contains a company name that already exists in the system? Each row is still created as a new account; the BDR sees a warning in the upload results for duplicate company names.
- What happens when the BDR tries to generate messages for an account that already has MESSAGE_READY status? The system should allow re-generation and overwrite the previous messages.
- What happens when "Generate All" is running and the BDR navigates away? The server-side process continues; returning to the tab reconnects to the real-time update stream and shows current statuses.
- What happens when a RESPONDED account is viewed and HubSpot sync had previously succeeded? The existing HubSpot ID remains visible without re-triggering a sync.

## Requirements *(mandatory)*

### Functional Requirements

**Account Management**

- **FR-001**: BDRs MUST be able to create an account by providing: company name (required), industry (required), website (required), target persona (required), and notes (optional). Multiple accounts with the same company name are permitted; the UI MUST warn the BDR before submission when a matching company name already exists.
- **FR-002**: Each account MUST have a status that progresses through: `NEW` → `MESSAGE_READY` → `RESPONDED`. An account MAY also enter an `ERROR` state if AI generation fails after one automatic retry; from `ERROR` the BDR can re-trigger generation to return to `NEW` → `MESSAGE_READY`.
- **FR-003**: BDRs MUST be able to view all accounts and their current status. On the Single Account tab, a persistent list of all accounts created via that tab MUST appear below the creation form; clicking an account in the list opens its detail view.

**AI Message Generation**

- **FR-004**: When a BDR triggers message generation, the system MUST first gather company intent signals before generating messages.
- **FR-005**: The signal research step and the message generation step MUST be handled by separate, dedicated AI agents with distinct responsibilities.
- **FR-006**: The orchestration of these agents MUST be reliable and resumable — if a step fails, the process can be retried without losing progress from completed steps.
- **FR-007**: The system MUST generate exactly three LinkedIn messages per account: a connection request, a first follow-up, and a second follow-up.
- **FR-008**: Generated messages MUST be persona-aware — the content and tone MUST reflect what matters to the specified target persona.
- **FR-009**: Generated messages MUST reference the company's intent signals to feel grounded and relevant.
- **FR-010**: The AI generation process MUST use Google Gemini as the underlying model, configured via a `GOOGLE_API_KEY` environment variable.
- **FR-011**: After successful generation, the account status MUST change to `MESSAGE_READY`.

**Company Intent Signals**

- **FR-012**: The system MUST retrieve intent signals for each company before generating messages.
- **FR-013**: For the MVP, signal retrieval MUST be deterministic — the same company name always produces the same signals, enabling repeatable demos without live data.
- **FR-014**: Each company MUST receive exactly three intent signals (e.g. "Hiring AI engineers", "Recently raised Series B").

**HubSpot Sync**

- **FR-015**: When a BDR marks an account as `RESPONDED`, the system MUST automatically trigger a HubSpot sync without any additional action from the BDR.
- **FR-016**: After a successful HubSpot sync, the system MUST store and display the HubSpot record ID against the account.
- **FR-017**: For the MVP, HubSpot sync MUST be a stub that always succeeds and returns a deterministic fake ID in the format `hs_<6-digit-number>` derived from the company name.

**Salesforce Export**

- **FR-018**: BDRs MUST be able to request a Salesforce-ready export payload for any account.
- **FR-019**: The SFDC payload MUST include: company name, industry, website, target persona, current status, intent signals, generated messages, and HubSpot ID (if available).
- **FR-020**: The SFDC payload MUST be displayed directly on the page in a structured format.

**Bulk Upload**

- **FR-021**: BDRs MUST be able to upload a CSV file with columns: `companyName`, `industry`, `website`, `targetPersona`, `notes`.
- **FR-022**: On upload, all valid rows MUST be parsed and accounts created immediately.
- **FR-023**: Rows missing required fields MUST be rejected with a clear per-row error; valid rows MUST still be created.
- **FR-024**: BDRs MUST be able to trigger AI generation for all uploaded accounts with a single "Generate All" action.
- **FR-025**: "Generate All" MUST process accounts sequentially, one at a time. Once started, it runs to completion with no cancellation mechanic.
- **FR-025a**: If an account's generation fails during "Generate All", the system MUST automatically retry that account once. If the retry also fails, the account MUST be marked with an `ERROR` status and the batch MUST continue with the remaining accounts.
- **FR-026**: BDRs MUST be able to trigger or re-trigger AI generation for individual rows independently.
- **FR-027**: Expanding a row MUST show the full intent signals and all three generated messages for that account.

**User Interface**

- **FR-028**: The tool MUST be a single-page web application served at `/`, bundled with the service — no separate frontend deployment.
- **FR-029**: The UI MUST have two tabs: "Single Account" and "Bulk Upload".
- **FR-030**: The UI MUST show a loading indicator while AI generation is in progress for any account. Status updates (progress, completion, errors) MUST be pushed from the server to the browser in real time so the BDR sees changes without manually refreshing the page.
- **FR-031**: Account status (`NEW`, `MESSAGE_READY`, `RESPONDED`, `ERROR`) MUST be clearly visible at all times with distinct visual treatment per state.
- **FR-032**: The tool MUST be accessible without authentication.

### Key Entities

- **Account**: Represents a target company being outreached. Key attributes: unique ID, company name, industry, website, target persona, notes, status (NEW / MESSAGE_READY / RESPONDED / ERROR), intent signals (list), generated messages (connection request, follow-up 1, follow-up 2), HubSpot ID, created timestamp.
- **IntentSignal**: A short string describing a recent company activity or buying trigger (e.g. "Hiring AI engineers"). Each account has exactly three.
- **OutreachMessage**: A ready-to-send LinkedIn message. Distinguished by type: connection request, first follow-up, second follow-up.
- **BulkUpload**: A batch of accounts created from a single CSV upload. Tracks the number of rows uploaded, created, and rejected.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A BDR can go from entering a company to having three ready-to-send LinkedIn messages in under 60 seconds (excluding model response time).
- **SC-002**: The same company name always produces the same three intent signals across multiple runs, enabling repeatable demonstrations.
- **SC-003**: A BDR can upload a CSV of 50 companies and have all accounts created within 5 seconds of upload completion.
- **SC-004**: Marking an account as responded triggers HubSpot sync and displays the record ID without any additional action from the BDR.
- **SC-005**: A failed AI generation step does not require the BDR to start over — the process can be retried and resumes from the last successful step.
- **SC-006**: The Salesforce payload for any account is available with a single click and contains all account data including signals and messages.
- **SC-007**: The tool runs end-to-end with a single start command and requires only a `GOOGLE_API_KEY` environment variable — no other external accounts or services needed.

## Clarifications

### Session 2026-03-25

- Q: Can two accounts be created for the same company (e.g., same company, different personas)? → A: Yes, duplicates are allowed, but the UI must warn the BDR when a company name already exists before they submit.
- Q: Does the Single Account tab show previously created accounts or only the current one? → A: A persistent list of all accounts created via the Single Account tab appears below the form; clicking one opens its detail view.
- Q: During "Generate All", if one account's AI generation fails, should the batch skip-and-continue, stop, or retry? → A: Retry the failed account once automatically; if it still fails, mark it as ERROR and continue with remaining accounts.
- Q: Can "Generate All" be cancelled mid-run? → A: No — once started it runs to completion; no cancel mechanic in the MVP.
- Q: How does the UI learn that generation has completed — polling or server push? → A: Server pushes status updates to the browser in real time (e.g. server-sent events).

## Assumptions

- The three generated LinkedIn messages are always: (1) a connection request note, (2) a first follow-up, (3) a second follow-up. The distinction between them informs persona-aware tone and urgency calibration.
- "Generate All" in bulk mode runs accounts sequentially to avoid overwhelming the LLM API with parallel requests.
- Re-generating messages for an account that already has `MESSAGE_READY` status is allowed and overwrites previous messages and signals.
- The SFDC payload is a structured JSON object displayed in the UI — not a downloadable file in the MVP.
- HubSpot sync is always triggered automatically on the `RESPONDED` transition, not manually. If already synced, the existing ID is shown without re-syncing.
- There is no pagination in the bulk upload table for the MVP — all rows are displayed.
- Data does not need to survive service restarts in the MVP — in-memory or ephemeral storage is acceptable.