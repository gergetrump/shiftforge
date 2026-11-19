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
