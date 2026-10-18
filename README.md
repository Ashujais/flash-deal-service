# Nnomm Flash-Deal Claim Service

A high-concurrency flash-deal claim service built in **Kotlin** (JVM 17), **Ktor**, and **PostgreSQL**, engineered for correctness under heavy load, out-of-order webhooks, retries, and network drops across dual active instances sharing a single database.

---

## Quick Start

### 1. Run with Docker Compose
Starts PostgreSQL and two instances of the service on ports **8080** and **8081**:
```bash
docker compose up --build
```
Both instances share the same database (`nnomm_db`), run automatic Flyway migrations on boot, and coordinate safely using PostgreSQL row-level locks and partial indexes.

### 2. Run the Full Test Suite
The entire test suite runs against real PostgreSQL (via EmbeddedPostgres / Testcontainers, never mocks or H2):
```bash
./gradlew test
```
*(On Windows: `.\gradlew.bat test`)*

---

## Architecture & Technology Stack

- **Language & Runtime**: Kotlin 2.0 on JVM 17
- **Web Framework**: Ktor 2.3 Server (Netty engine) - lightweight coroutine-native HTTP server with low memory footprint and sub-second boot times.
- **Database & Pooling**: PostgreSQL 15+, HikariCP connection pool, Flyway database migrations.
- **Security**: HS256 JWT authentication (`com.auth0:java-jwt`), HMAC-SHA256 constant-time webhook signatures (`MessageDigest.isEqual`).
- **Observability**: Structured JSON logging with request ID on every line (`MDC`), Prometheus `/metrics` endpoint via Micrometer.
- **Resilience**: PostgreSQL `SELECT ... FOR UPDATE`, partial unique indexes, 24-hour idempotency store, and coroutine-based background expiry sweepers.

---

## API Endpoints

All timestamps are ISO 8601 in UTC. Money is integer paise.

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/v1/deals` | Bearer JWT (role: `restaurant_admin`) | Creates a deal for the admin's restaurant |
| `GET` | `/v1/deals/{dealId}` | Bearer JWT (any user) | Retrieves deal details and `stock_available` |
| `POST` | `/v1/deals/{dealId}/claims` | Bearer JWT (role: `customer`) + `Idempotency-Key` | Reserves 1–3 units of a live deal |
| `GET` | `/v1/claims/{claimId}` | Bearer JWT (claim owner or deal admin) | Returns claim status (404 for anyone else) |
| `POST` | `/v1/webhooks/payments` | `X-Payment-Signature` (HMAC-SHA256) | Processes payment provider webhooks |
| `GET` | `/v1/me/claims` *(Stretch Goal 1)* | Bearer JWT (role: `customer`) | Cursor-paginated customer claim history |
| `GET` | `/v1/deals/{dealId}/claims` *(Stretch Goal 4)* | Bearer JWT (role: `restaurant_admin`) | List deal claims with optional `?status=` filter |
| `GET` | `/metrics` *(Stretch Goal 3)* | Public | Prometheus scrape endpoint |

---

## Key Invariants & Concurrency Guarantees

1. **Zero Overselling**:
   Deal stock deduction is serialized using `SELECT ... FROM deals WHERE id = ? FOR UPDATE`. Concurrent requests queue on PostgreSQL row locks. If `stockAvailable < quantity`, the request is immediately rejected with `409 SOLD_OUT`. Units held by `RESERVED`, `PAID`, and `NEEDS_REVIEW` claims never exceed `stock_total`.
2. **One Active Claim Per Customer Per Deal**:
   Enforced at the database engine level via a partial unique index:
   ```sql
   CREATE UNIQUE INDEX idx_claims_active_per_customer_deal 
   ON claims (deal_id, customer_id) 
   WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');
   ```
   When a claim expires, it exits the index, allowing the customer to claim again.
3. **Idempotency with 24-Hour Replay & Crash Protection**:
   - Keys are scoped to `(customer_id, idempotency_key)`.
   - Repeated requests within 24 hours replay the exact original response with `Idempotent-Replayed: true`.
   - Concurrent requests with the same key return `409 REQUEST_IN_PROGRESS`.
   - If a node crashes mid-request, the in-progress lock expires after 60 seconds (`locked_until`), allowing retry takeover.
4. **Payment vs Expiry Race**:
   Both the payment webhook handler and the expiry worker acquire exclusive row locks on the claim row (`FOR UPDATE`). Exactly one outcome wins:
   - Payment wins: Status transitions to `PAID`, units stay sold.
   - Expiry wins: Status transitions to `EXPIRED` and units return to deal stock; when payment arrives later, it transitions to `REFUND_PENDING` and units stay in stock.

---

## Assumptions Where the Brief is Silent

1. **Unknown `claim_id` on Payment Webhooks**:
   Returns `404 NOT_FOUND` with error code `NOT_FOUND` so the payment provider receives explicit feedback that the target resource does not exist in our database.
2. **Secondary `payment.succeeded` with New `event_id` on Resolved Claims**:
   If a claim is already `PAID`, `NEEDS_REVIEW`, or `REFUND_PENDING`, and another `payment.succeeded` event arrives with a *new* `event_id` (representing an overpayment or duplicate external charge):
   - We record the event in `payment_events` for auditing and financial reconciliation.
   - We log a warning for human/merchant review.
   - We do not modify the claim status or alter deal stock (protecting stock invariants).
   - We return `200 OK` so the payment provider stops retrying.
3. **Instant API Expiry Visibility**:
   The brief requires: *"From expires_at on, the API never shows it as RESERVED, and its units are back in stock within 10 seconds."*
   In `ClaimRecord.effectiveStatus()`, if `now >= expires_at`, the API dynamically displays `EXPIRED` even if the background sweeper coroutine hasn't committed the update yet, guaranteeing strict 0-second compliance on read.
4. **Cursor Format for Pagination**:
   Cursor is a URL-safe Base64 encoded string of `${created_at_epoch_ms}:${claim_id}`. Because pagination uses `(created_at < ? OR (created_at = ? AND id < ?)) ORDER BY created_at DESC, id DESC`, new claims created concurrently will never skip or repeat entries.

---

## What We'd Do Next With More Time

1. **In-Memory Redis Token Bucket for 100× Concurrency**:
   Replace single-row PostgreSQL lock contention during the first 5 seconds of a viral flash deal with an atomic Redis Lua reservation script (`DECRBY` with floor check). Claims are confirmed instantly in RAM and batched into PostgreSQL asynchronously.
2. **Transactional Outbox & Event Streaming**:
   Implement the Transactional Outbox pattern with Debezium CDC and Kafka for broadcasting claim and payment state changes to downstream notification and analytics services.
3. **OpenTelemetry Distributed Tracing**:
   Add OTel instrumentation across Ktor endpoints, HikariCP, and database queries for end-to-end trace correlation in Jaeger/Grafana Tempo.
4. **Automated End-to-End Stress Testing**:
   Integrate automated k6 or Gatling load tests into the CI pipeline to continuously assert zero oversell under 10,000 requests/sec.

---

## AI Tools Disclosure

- **AI Tools Used**: Google Antigravity (Gemini) for scaffolding project boilerplate, designing stress test harnesses, and reviewing concurrency edge cases.
- **Suggestion Rejected and Rationale**:
  - *Suggestion*: The AI initially suggested using **Optimistic Locking** (`UPDATE deals SET stock_available = ... WHERE version = ...`) with automatic retry loops.
  - *Why Rejected*: In a flash deal scenario where hundreds of customers race for limited units in the same second, optimistic locking triggers severe contention, massive serialization failures, and CPU-burning retry storms. Instead, we used **Pessimistic Locking** (`SELECT ... FOR UPDATE` with `READ COMMITTED`), which cleanly serializes requests in PostgreSQL's native FIFO lock queue, eliminating retries and providing immediate, deterministic resolution.
