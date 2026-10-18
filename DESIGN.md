# System Architecture & Design Rationale

This document answers the core architecture questions for the Nnomm flash-deal claim service, tied directly to our implementation, PostgreSQL mechanics, and SQL queries.

---

### Question 1: Two customers on different instances claim the last unit at the same instant

#### 1. SQL Execution Walkthrough
When Customer 1 (on Instance 1) and Customer 2 (on Instance 2) both submit a claim for the last remaining unit ($stock\_available = 1$, $quantity = 1$):

1. **Transaction Start & Row Lock Acquisition**:
   Both instances start an ACID transaction and execute [`DealRepository.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/repository/DealRepository.kt#L63):
   ```sql
   SELECT id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at, created_at
   FROM deals
   WHERE id = ?
   FOR UPDATE;
   ```
2. **PostgreSQL Row-Level Lock Resolution**:
   - PostgreSQL assigns an exclusive row-level tuple lock (`ExclusiveLock` on the tuple) to the first transaction whose lock request reaches the engine (say, Customer 1).
   - Customer 2’s transaction blocks and enters `LockWait` status in the PostgreSQL kernel waiting on Customer 1's transaction ID.
3. **Winner (Customer 1) Execution**:
   - Reads `stock_available = 1`. Validation passes ($1 \ge 1$).
   - Executes [`DealRepository.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/repository/DealRepository.kt#L78):
     ```sql
     UPDATE deals SET stock_available = stock_available - 1 WHERE id = ? AND stock_available >= 1;
     ```
   - Inserts the claim row with status `RESERVED` ([`ClaimRepository.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/repository/ClaimRepository.kt#L45)).
   - Updates the idempotency record with status `201` and the response body.
   - Commits transaction (`COMMIT`). The row lock on the deal tuple is released.
4. **Loser (Customer 2) Execution**:
   - Customer 2’s blocked `SELECT ... FOR UPDATE` unblocks. Under `READ COMMITTED` isolation, PostgreSQL fetches the latest committed version of the row.
   - Customer 2 reads `stock_available = 0`.
   - The stock check (`deal.stockAvailable < quantity`) fails in [`ClaimService.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/service/ClaimService.kt#L105).
   - The service records a 409 conflict with code `SOLD_OUT` in the idempotency table and commits.
   - Customer 2 receives `409 Conflict: {"error": {"code": "SOLD_OUT", "message": "..."}}`.

#### Why a Kotlin Mutex or synchronized block fails
A Kotlin `Mutex` or JVM `synchronized` block is entirely in-process memory:
- Mutexes only synchronize threads sharing the same JVM heap.
- Instance 1 and Instance 2 run in separate JVM processes on different operating system containers/nodes. Neither JVM can inspect or block the other's memory.
- Without database-level serialization (`FOR UPDATE`), both instances would read `stock_available = 1` concurrently, both would decrement it, and the deal would oversell (violating Rule 1).

---

### Question 2: Service crash before responding vs before commit

#### Case A: Crashes after commit, before HTTP response
1. The database transaction has committed: the claim exists as `RESERVED`, deal stock is decremented, and the idempotency record contains `status_code = 201` and the serialized response body.
2. The client times out and retries with the same `Idempotency-Key`.
3. In [`IdempotencyRepository.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/repository/IdempotencyRepository.kt#L81):
   - `acquireOrCheck` selects the idempotency row with `FOR UPDATE`.
   - Detects `record.statusCode != null` and that `created_at` is within 24 hours.
   - Returns `IdempotencyOutcome.Replay(201, responseBody)`.
4. The service sends back the original 201 response with the HTTP header:
   `Idempotent-Replayed: true`.
5. No second claim is created, stock is not decremented again, and the client recovers transparently.

#### Case B: Crashes before commit
1. The database transaction was never committed. PostgreSQL terminates the backend connection upon process death and automatically rolls back all uncommitted mutations.
2. In `idempotency_keys`, the in-progress row has `locked_until = now() + 60 seconds`.
3. If the client retries within 60 seconds:
   - The row has `status_code = NULL` and `now() < locked_until`.
   - Returns `409 REQUEST_IN_PROGRESS`.
4. Once 60 seconds elapse from the crash:
   - `now() >= locked_until`.
   - In [`IdempotencyRepository.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/repository/IdempotencyRepository.kt#L98), the service recognizes that the prior worker died mid-request. It refreshes `locked_until = now() + 60s` and re-executes the claim cleanly as a `NewExecution`.
   - No orphaned claims, no double deductions.

---

### Question 3: Webhook payment.succeeded and Expiry job reach the claim in the same millisecond

Both workers begin a transaction and execute `SELECT ... FROM claims WHERE id = ? FOR UPDATE`:

| Ordering | Execution Steps | Final Claim Status | Deal Stock Available | Invariant Holds? |
|---|---|---|---|---|
| **Ordering 1: Payment webhook acquires lock first** | 1. Webhook locks claim.<br>2. Evaluates `now < claim.expiresAt` (true).<br>3. Amount matches $\rightarrow$ updates status to `PAID` ([`PaymentWebhookService.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/service/PaymentWebhookService.kt#L93)). Commits.<br>4. Expiry job unblocks, re-reads claim. Status is now `PAID` (not `RESERVED`), so expiry skips it. | `PAID` | Stock remains deducted (units stayed sold) | Yes: `stock_available + units_held == stock_total` |
| **Ordering 2: Expiry job acquires lock first** | 1. Expiry job locks claim via `FOR UPDATE SKIP LOCKED` ([`ClaimRepository.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/repository/ClaimRepository.kt#L125)).<br>2. Verifies `expires_at <= now`.<br>3. Restores units to deal (`stock_available + quantity`).<br>4. Sets claim status to `EXPIRED`. Commits.<br>5. Payment webhook unblocks, re-reads claim. Status is `EXPIRED`.<br>6. Per Page 5 rules, updates claim to `REFUND_PENDING` ([`PaymentWebhookService.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/service/PaymentWebhookService.kt#L115)). Units stay in stock. Commits. | `REFUND_PENDING` | Stock restored (+quantity returned to stock) | Yes: `stock_available + units_held == stock_total` |
| **Ordering 3: Webhook locks claim, but clock has just ticked past `expires_at`** | 1. Webhook locks claim.<br>2. Sees claim status `RESERVED`, but `now >= expires_at`.<br>3. Treats as late payment: restores deal stock (`stock_available + quantity`) and sets status to `REFUND_PENDING` ([`PaymentWebhookService.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/service/PaymentWebhookService.kt#L106)). Commits.<br>4. Expiry worker unblocks; claim is no longer `RESERVED`, so it takes no further action. | `REFUND_PENDING` | Stock restored (+quantity returned to stock) | Yes: `stock_available + units_held == stock_total` |

---

### Question 4: Webhook Signature, Timestamps, and Constant-Time Comparison

1. **Raw Body vs Parsed JSON**:
   - JSON serializers differ in whitespace, key ordering, number formatting (e.g., `19800` vs `19800.0`), and unicode escape sequences.
   - If the signature were computed over re-serialized or parsed JSON, canonical differences would alter the byte stream and invalidate valid signatures.
   - By hashing the exact raw request bytes ([`WebhookSignature.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/security/WebhookSignature.kt#L45)), the exact cryptographic integrity of what the payment gateway transmitted is verified.
2. **Timestamp Protection**:
   - The timestamp parameter `t` prevents **replay attacks**.
   - If a malicious actor intercepts a valid signed webhook request, they cannot store it and replay it hours or days later because `|t - now| > 300` rejects the call as `UNAUTHENTICATED` ([`WebhookSignature.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/security/WebhookSignature.kt#L31)).
3. **Constant-Time Comparison**:
   - Standard string equality (`==` or `.equals()`) short-circuits on the first mismatched byte, leaking execution time proportional to the length of the matching prefix.
   - An attacker can exploit this timing side-channel to brute-force the HMAC character by character.
   - `MessageDigest.isEqual(...)` executes in constant time regardless of where the mismatch occurs, neutralizing timing attacks ([`WebhookSignature.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/security/WebhookSignature.kt#L40)).

---

### Question 5: Transaction Isolation Level & Anomalies

- **Isolation Level Used**: `READ COMMITTED` (PostgreSQL default, configured in [`DatabaseFactory.kt`](file:///f:/Nnomm_Software/src/main/kotlin/com/nnomm/flashdeal/db/DatabaseFactory.kt#L23)).
- **Why It Is Enough**:
  - We use explicit row-level locking (`SELECT ... FOR UPDATE`) on the deal row and claim row.
  - In PostgreSQL `READ COMMITTED`, when a query encounters a row locked by another transaction, it waits for the locking transaction to finish. Once unlocked, it re-reads the updated row before applying locks and filters.
  - This provides strict serializability for that specific deal without the prohibitive abort-and-retry overhead (PostgreSQL serialization error `40001: could not serialize access`) that `SERIALIZABLE` produces under heavy flash-deal contention.
- **Anomaly Under A Weaker Approach (Read Uncommitted / Non-locking Read)**:
  - **Lost Update Anomaly**: Two transactions read `stock_available = 10`. Both subtract 2 in application memory and issue `UPDATE deals SET stock_available = 8`. Four units were claimed, but `stock_available` reflects only two deducted, causing immediate deal overselling.
  - **Read Skew / Dirty Read**: An uncommitted transaction reserving stock could be observed by an expiry worker or customer before rollback, exposing phantom states.

---

### Question 6: Most Critical Constraint & What Breaks If Dropped

- **The MVP Constraint**:
  The partial unique index on `claims` defined in [`V1__initial_schema.sql`](file:///f:/Nnomm_Software/src/main/resources/db/migration/V1__initial_schema.sql#L30):
  ```sql
  CREATE UNIQUE INDEX idx_claims_active_per_customer_deal 
  ON claims (deal_id, customer_id) 
  WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');
  ```
- **What Breaks If Dropped**:
  - **Double Claims**: Rule 2 strictly mandates: *"A customer can hold at most one RESERVED, PAID or NEEDS_REVIEW claim on a deal."*
  - If dropped, two concurrent requests from the same customer using different `Idempotency-Key` headers (tested in [`ConcurrencyTests.kt`](file:///f:/Nnomm_Software/src/test/kotlin/com/nnomm/flashdeal/ConcurrencyTests.kt#L217)) could both pass application-level checks if executed at the same microsecond, inserting multiple active claims for the same customer.
  - The partial unique index is enforced by PostgreSQL's B-Tree engine using physical page locking during index insertion, ensuring mathematical impossibility of duplicate active claims regardless of instance counts.

---

### Question 7: Viral Deal & Traffic Grows 100×

#### What Fails First:
- **Row-Lock Contention on PostgreSQL**:
  With 100× traffic (tens of thousands of requests per second for the same deal), all requests contend for the exact same physical deal tuple lock (`SELECT ... FOR UPDATE WHERE id = dealId`).
- This creates lock queues in PostgreSQL, saturating the connection pool (`HikariCP`), inflating connection wait times, exhausting thread pools, and causing HTTP 504 gateway timeouts.

#### What Would Change:
1. **In-Memory Distributed Reservation (Redis Token Bucket / Lua Scripting)**:
   - Decrement deal stock atomically in Redis using a single-hop Lua script:
     `redis.eval("if redis.call('get', KEYS[1]) >= ARGV[1] then return redis.call('decrby', KEYS[1], ARGV[1]) else return -1 end")`.
   - Slashes write latency from 15ms database round-trips to sub-millisecond in-memory operations.
2. **Asynchronous Claim Materialization via Kafka / SQS**:
   - Once Redis confirms the reservation token, enqueue the claim event to an append-only log.
   - Background consumer workers batch-insert claims and sync stock in PostgreSQL asynchronously without customer-facing latency.
3. **Queue / Waiting Room (Virtual Queue)**:
   - Gate traffic with a rate-controlled token bucket (Cloudflare Waiting Room) so only active claims reach backend workers while excess traffic receives instant `SOLD_OUT` or queued position indicators.
