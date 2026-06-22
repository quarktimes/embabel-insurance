# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Start the app (requires DEEPSEEK_API_KEY)
./mvnw spring-boot:run

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=UnderwritingAgentIntegrationTest

# Run E2E tests (require DEEPSEEK_API_KEY)
./mvnw test -Pe2e

# Skip integration tests (fast compile check)
./mvnw test -Dtest='!integration.*,!e2e.*'
```

**Env vars:** `DEEPSEEK_API_KEY` (required), `LANGFUSE_*` (optional observability).

## Architecture (Three-Layer Agent System)

```
┌─────────────────────────────────────────────┐
│  REST Controllers                           │
│  ChatController  InsuranceController        │
├─────────────────────────────────────────────┤
│  Service Layer                              │
│  ChatService    AgentService   PolicyService│
├─────────────────────────────────────────────┤
│  Embabel Agent Layer                        │
│  ChatbotAgent   UnderwritingAgent  Claims   │
│  (RAG/FAQ)      (核保+state routing)       │
│               + Guardrails                  │
└─────────────────────────────────────────────┘
```

### Directory Layout

- **agent/** — Three `@Agent` classes, each with internal `@State` routing:
  - `ChatbotAgent` — Agentic RAG (Lucene search → LLM answer), single-state QA
  - `UnderwritingAgent` — Multi-state (APPROVED/REFERRED/DENIED/ERROR routing) with Utility planner
  - `ClaimsAgent` — Multi-state (APPROVED/DENIED/INVESTIGATING/ERROR routing) with Utility planner
- **service/** — `AgentService` orchestrates UnderwritingAgent/ClaimsAgent via AgentPlatform; `ChatService` manages server-side sessions + calls ChatbotAgent via AgentInvocation
- **controller/** — REST endpoints (`/api/chat`, `/api/insurance/*`); all require HTTP Basic auth (`@PreAuthorize` for fine-grained access)
- **guardrail/** — Embabel GuardRail implementations for input/output validation
- **dto/** — `request/` and `response/` sub-packages for typed API contracts
- **config/** — SecurityConfig, CacheConfig, RAG config, DataInitializer, OpenAPI, Guardrail wiring

### Testing Strategy

| Test Type | Location | LLM Needed? | Profile |
|-----------|----------|-------------|---------|
| Unit tests | `agent/*Test.java`, `service/*Test.java` | No | default |
| Integration | `integration/*IntegrationTest.java` | No (FakeOperationContext) | default |
| E2E | `e2e/*E2ETest.java` | Yes (DeepSeek) | `e2e` |

Integration tests use Embabel `FakeOperationContext` to mock LLM responses — they exercise the full Agent state machine without API calls.

### Key Patterns

1. **Agent State Routing** — Agents use `@State(name = "APPROVED")` + `@Action` annotations. The Utility planner routes between states based on risk scores / fraud scores.

2. **Error Handling via Blackboard** — Agent errors are stored on `Blackboard` with keys like `underwriting_error`, `claims_error`, rather than thrown as exceptions. `AgentService` reads these after agent completion.

3. **Agent Timeout Protection** — All agent invocations via `runWithTimeout()` (120s default); stuck agents trigger `StuckHandler` callback with diagnostics.

4. **Chat Session Management** — `ChatService` manages sessions per userId in-memory with 30min TTL; clients receive a `sessionId` on first message and pass it back.

5. **Role-Based Auth** — Four roles with hierarchical permissions: ADMIN > (UNDERWRITER, CLAIMS) > USER. Method-level `@PreAuthorize` gates all insurance endpoints.

### Intent Routing (Phase 1 Target)

The current `ChatController` routes all messages to `ChatbotAgent` (AI FAQ). Phase 1 work will add intent classification so messages about underwriting/claims/payment route to the appropriate Agent or Service instead.
