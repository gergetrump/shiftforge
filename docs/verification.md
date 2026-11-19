# Verification — 19 November 2026

These checks distinguish code/behavior verification from native optimizer execution. Inputs are synthetic; results are not industrial performance measurements.

## Passed locally

- **46 license-free JUnit cases**, zero failures/errors. Sources were compiled for Java 21; tests ran on Temurin **21.0.11**. Coverage includes strict integer/structure validation, five exact greedy-reference exports, corrupt decision/state/metric rejection, termination interpretation, HTTP responses and concurrent-request/exception lock recovery.
- **Production frontend build:** TypeScript checks and Vite build passed using Node 24.
- **Three Playwright replay tests:** stale input marking and exact exports; separate unavailable-optimizer/usable-greedy states; rejecting a response for a different input. These tests replay historical checked Python results, and do not run Java Gurobi.
- **Independent live API review:** all **20 greedy scenarios** matched the Python reference exactly, including production decisions, stocks, activity and metrics. Cases comprise the five saved examples/disruptions and fifteen deterministic varied small scenarios. All **nine invalid requests** returned proper JSON **422** responses. Eight attempted optimizer requests returned truthful **503 / solver_unavailable**, with no fallback.
- **Independent live browser review against the Java API:** original and outage runs, base greedy objective **51**, checked exact-input downloads, stale-results state and visible Gurobi licensing diagnostics passed. Desktop width 1440 and mobile width 390 had no page-wide overflow or browser errors. `dashboard.png` records this real API demonstration, including the unavailable-optimizer condition.
- **Packaged CLI:** lookahead greedy objective **1** with a valid check. Changing the exported objective to 99 was correctly rejected by `check` with `valid: false` and exit code 1.
- **Docker:** the multi-stage image built successfully, compiling and testing with JDK 21. The container served its built dashboard, health/examples endpoints and a checked lookahead greedy result with objective **1**. Gurobi returned the expected **503** without a license. The process ran as unprivileged `planner` (UID/GID 999) on Temurin **21.0.13**. The temporary local test container was stopped after verification; no image or service was publicly deployed.

The API review summary is saved in [greedy-parity-review.json](greedy-parity-review.json). The five portable golden reference exports are checked into `src/test/resources/reference`; JUnit reruns their comparisons without a Python dependency.

## Native optimization limit

The code uses the actual Gurobi **14.0.0** Java API and builds a bounded MILP directly in Java. No Java-capable license was available. Actual native initialization returned error **10009**, reported as unavailable rather than a solution.

- `./mvnw -Pgurobi test` attempted **eight** native integration cases; **all eight visibly skipped** for the missing Java license. The other 46 tests passed.
- A selected native test with `-DrequireGurobi=true` failed as intended on `solver_unavailable`. This verifies that the strict profile cannot report success without real native execution.
- No Java optimizer objective, optimum, runtime or benchmark comparison is claimed. The expected licensed outcomes are encoded as future integration assertions, not recorded results.

Native model structure was reviewed against the Python formulation, including useful-production limits, capacity/staffing, activation, stock balance/exclusivity, finite bounds and status/bound checks. Static agreement does not replace a licensed integration run. When a license becomes available, run:

```bash
./mvnw -Pgurobi -DrequireGurobi=true test
```

## Reproduction

```bash
./mvnw clean verify
java -jar target/shiftforge.jar solve --example lookahead --algorithm greedy --output artifacts/greedy.json
java -jar target/shiftforge.jar check artifacts/greedy.json
cd frontend
npm ci --ignore-scripts
npm run build
npx playwright install --with-deps chromium
npm run test:e2e
```

The implementation host had JDK 25 for compilation and a Java 21 runtime. Local tests explicitly selected the latter with `-Dtest.java=/path/to/java21/bin/java`; normal JDK 21 installations and CI need no override. Maven's release target remains 21.

## Scope

The service is a local, synchronous prototype. No persistent database, restart recovery, public deployment, distributed lock or hard end-to-end solver cancellation has been tested or claimed. The original Python source repository was left unchanged. Its prior 13-scenario optimizer benchmark does not establish results for this implementation.

## SparkJava/PostgreSQL extension — 19 November 2026

The current service uses SparkJava 2.9.4 (Jetty 9.4.58 BOM override), PostgreSQL 17 and typed jOOQ 3.19.40 DSL. This supersedes the earlier scope's absence of persistence: completed saved scenarios/runs survive restart, while in-flight computation still does not resume. Gurobi's native execution limit is unchanged.

Actual checks for this extension:

- **48 default JUnit cases passed on Temurin21.0.13**, zero failures/errors/skips. The original46 remained passing after the HTTP migration; additional cases cover JSON413 for oversized input and static-file symlink confinement.
- **Seven real PostgreSQL integration cases passed on Java 21**, zero failures/errors/skips: scenario/run create/read/list, exact snapshots after separately saved edits, new store/server durability, stable bounded pagination, missing IDs, malformed requests/client-fabricated result rejection, database failures without false saves, and an actual insert-followed-by-error transaction rollback. Failsafe profile `-Ppostgres` requires `SHIFTFORGE_TEST_DATABASE_URL` and fails when it is absent. CI now supplies a healthy real PostgreSQL service and runs that profile.
- **Four Playwright replay tests passed**, including the original three regressions and a new saved-history test. The new test holds a saved-run response open, confirms main planner controls/history actions cannot overlap, then checks save/open/download snapshot behavior. Fixtures remain historical Python results; replay tests do not prove native Java optimization.
- **Production TypeScript/Vite build passed** after history and readable-status-label changes.
- **Independent live API review** repeated the20-case exact Python/Java greedy parity and nine invalid requests after the SparkJava change. It checked real PostgreSQL create/list/get, unchanged saved inputs after edited copies, pagination/missing IDs, rejection of uploaded fake results and saving explicit unavailable-solver diagnostics. Separate application process restart preserved exact records. During a real PostgreSQL outage, history GET/POST returned safe503 responses while ephemeral greedy planning remained200/valid; database recovery restored the exact records without restarting the application.
- **Independent live browser review** used the real Java 21 API and PostgreSQL. Save/run/open, shared busy guard, exact JSON download after draft edits, page reload/reopen and desktop/mobile layouts passed without page errors or horizontal page overflow. Final readable result labels were rechecked.
- **Docker Compose build and run passed** with a Java 21 build/runtime, unprivileged app user, PostgreSQL health dependency, process healthcheck, loopback host ports and a named database volume. A checked lookahead greedy result had objective1. Both full saved scenario and run survived `compose down` followed by `compose up` (containers and network recreated, volume retained), with exact JSON equality. Temporary containers were stopped after verification; the named test volume is retained as recoverable local test data. No public deployment is claimed.
- The resolved Maven tree pins all Jetty and Jetty-WebSocket modules to9.4.58.v20250814. This is a tested compatibility override, not a comprehensive security audit or long-term support claim. Spark's external static-file handler is not used.

### Test-first evidence

`HistoryStoreIT` was written and compiled before `HistoryStore`/`DatabaseConfig` existed. The initial build failed on those missing implementation classes; `/tmp/shiftforge-persistence-red.log` recorded that local red step. The implementation then made those tests pass, and further API/failure/security/browser cases were added. This establishes a bounded behavior/test-first exercise, not a claim that all code was written with TDD or that generated implementation demonstrates George's independent mastery. Original Python source was not changed.

### Reproduce persistence checks

```bash
docker compose up -d postgres
export SHIFTFORGE_TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/shiftforge'
export SHIFTFORGE_TEST_DATABASE_USER='shiftforge'
export SHIFTFORGE_TEST_DATABASE_PASSWORD='local-demo-only'
./mvnw -Ppostgres verify
```

The credentials and ports above are for this local Compose demo. Use a disposable test database; integration tests append only their own synthetic records and one deliberately rolled-back probe. Do not direct these checks at a production database. See[persistence design](persistence.md) for failure semantics and the distinction between committed diagnostic records and successful solver plans.
