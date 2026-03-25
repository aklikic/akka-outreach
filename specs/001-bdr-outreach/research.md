# Research: BDR LinkedIn Outreach Tool

**Feature**: 001-bdr-outreach | **Phase**: 0 | **Date**: 2026-03-25

## 1. Google Gemini Provider (Akka SDK)

**Decision**: Use `googleai-gemini` provider in `application.conf`, mapping `GOOGLE_API_KEY` env var.

**Rationale**: Akka SDK 3.5.x has built-in GoogleAI Gemini support. Config provider name is `googleai-gemini`. The SDK default env var is `GOOGLE_AI_GEMINI_API_KEY`, but we map to `GOOGLE_API_KEY` (as specified in the spec) via explicit `api-key = ${?GOOGLE_API_KEY}`. Recommended model: `gemini-2.0-flash` — fast, cost-effective, supports tool calling, suitable for text generation.

**Config**:
```hocon
akka.javasdk.agent {
  model-provider = googleai-gemini
  googleai-gemini {
    model-name = "gemini-2.0-flash"
    api-key = ${?GOOGLE_API_KEY}
    response-timeout = 60s
    max-retries = 1
  }
}
```

**Alternatives considered**: OpenAI, Anthropic — not specified in requirements.

---

## 2. Server-Sent Events (SSE)

**Decision**: Return a streaming `HttpResponse` with `text/event-stream` content type, backed by an Akka Streams `Source` that polls the `AccountsByStatusView` every 2 seconds.

**Rationale**: Akka HTTP supports streaming entity responses via `akka.http.javadsl.model.HttpResponse`. The endpoint injects a `Materializer`, creates a `Source.tick(...)` that queries the view, serializes the current list of accounts as a JSON SSE `data:` line, and streams it to the connected browser. The browser reconnects automatically on disconnect (SSE spec). This is simple, stateless on the server, and delivers the real-time UX requirement (FR-030).

**Pattern sketch**:
```java
// In OutreachEndpoint constructor: inject Materializer
@Get("/events")
public HttpResponse streamAccountUpdates() {
    Source<ByteString, NotUsed> source = Source
        .tick(Duration.ZERO, Duration.ofSeconds(2), "tick")
        .mapAsync(1, __ -> componentClient.forView()
            .method(AccountsByStatusView::getAllAccounts).invokeAsync())
        .map(result -> ByteString.fromString(
            "data: " + JsonSupport.encodeToString(result) + "\n\n"));
    return HttpResponse.create()
        .withEntity(HttpEntities.createChunked(
            ContentTypes.TEXT_EVENT_STREAM, source));
}
```

**Alternatives considered**: WebSocket (bidirectional, unnecessary here); long-polling (simpler but worse UX); true server-push with concurrent queues (complex, no benefit at MVP scale).

---

## 3. CSV Parsing

**Decision**: Apache Commons CSV 1.10.0

**Rationale**: Lightweight, zero transitive dependencies, clean API for header-based parsing, proper handling of missing/extra columns. `CSVFormat.DEFAULT.withFirstRecordAsHeader()` parses the CSV and provides named column access. Missing required columns are detectable by checking `headerMap` before iterating rows.

**Dependency**:
```xml
<dependency>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-csv</artifactId>
  <version>1.10.0</version>
</dependency>
```

**Alternatives considered**: OpenCSV (heavier, annotation-driven), Jackson CSV (overkill for MVP).

---

## 4. Deterministic Signal Selection

**Decision**: 5 static pools of 3 signals each, selected by `Math.abs(companyName.hashCode()) % 5`.

**Rationale**: `String.hashCode()` is stable within a JVM session and consistent for equal strings, making it suitable for repeatable demo behavior (SC-002). The modulo maps any company name deterministically to one of 5 pools.

**Signal pools** (implemented in `domain/SignalPool.java`):
- Pool 0: "Hiring AI engineers", "Evaluating LLM orchestration platforms", "Expanding engineering team by 40%"
- Pool 1: "Recently raised Series B ($45M)", "Migrating off legacy monolith", "New CTO hired from hyperscaler"
- Pool 2: "Launched new product line", "Opening offices in EMEA", "Published engineering blog on distributed systems"
- Pool 3: "Acquired competitor last quarter", "Actively hiring platform engineers", "Migrating to cloud-native infrastructure"
- Pool 4: "Evaluating developer tooling vendors", "Recently IPO'd", "Tech stack modernization initiative underway"

---

## 5. HubSpot Stub

**Decision**: Deterministic fake ID: `String.format("hs_%06d", Math.abs(companyName.hashCode()) % 1_000_000)`

**Rationale**: Same hashing approach as signal selection — stable, deterministic across runs, produces a plausible `hs_XXXXXX` 6-digit ID format. Always returns `synced` status (FR-017).

---

## 6. Bulk Generation Workflow Loop

**Decision**: `BulkGenerationWorkflow` uses a 3-step-per-account loop: `researchForCurrentStep` → `generateForCurrentStep` → `updateCurrentStep`, cycling via step transitions until `currentIndex >= accounts.size()`.

**Rationale**: Akka Workflows allow step methods to transition back to other step methods by method reference, enabling safe looping. Each step is one async call (one agent invocation or one entity update). The workflow state holds `currentIndex` and `currentSignals` (populated after research, consumed in generate). Failure on any step triggers 1 retry (per WorkflowSettings), then an `errorCurrentStep` that marks the account `ERROR` and advances to the next account — satisfying FR-025, FR-025a.

**State structure**:
```java
record State(
    List<AccountDetails> accounts,
    int currentIndex,
    List<String> currentSignals  // populated after research step
) { ... }
```