# Model and provenance

## Planning model

Each product `p` has initial inventory and demand due at each ordered shift `t`. Each cell `c` has eligible products, processing minutes per unit, available minutes each shift and a fixed crew requirement. An active cell reserves its full crew once for the shift, including when it produces multiple products.

Decisions:

- `x[p,c,t]`: integer production on eligible product/cell pairs.
- `y[c,t]`: binary cell activity.
- `I[p,t]`, `B[p,t]`: nonnegative integer end-of-shift inventory and backlog.
- `z[p,t]`: binary sign choice enforcing inventory/backlog exclusivity.

Let `Q[p] = max(0, total demand[p] - initial inventory[p])`. Production is bounded by `Q[p]` and available cell minutes. The model enforces:

1. `sum_p minutes[p,c] * x[p,c,t] <= capacity[c,t] * y[c,t]`.
2. Production implies activity, and activity implies at least one produced unit.
3. `sum_c crew[c] * y[c,t] <= available operators[t]`.
4. Total production for a product is at most `Q[p]`.
5. `I[p,t] - B[p,t] = previous net stock + sum_c x[p,c,t] - demand[p,t]`.
6. Finite bounds and `z` prevent simultaneous positive inventory and backlog.

The single objective is **minimize `sum_p,t B[p,t]`**, measured in backlog unit-shifts. Inventory, active cells and utilization have no secondary objective. Multiple optimal allocations may exist; compare objective values and independently checked feasibility, not exact optimizer allocations.

Materials/storage are unlimited. There are no setup times, individual-worker assignments, operation sequences, physical machine start times or energy objectives. Production may anticipate future demand. The greedy baseline processes only current shortage and does not preproduce; its limitations are part of the demonstration.

## Independent checker

`Plans.check` validates complete unique output keys, nonnegative integer decisions, eligible production, useful-production bounds, machine minutes, exact activity/output correspondence, staffing, stock balances/exclusivity, backlog objective and all reported metrics. Capacity checks use long arithmetic to avoid integer overflow on corrupted exports. It does not call the optimizer.

Result feasibility does not prove optimality. `PlanningService.assess` separately requires consistent native termination, objective/bound and zero configured gap tolerances before using `optimal`. The CLI `check` does not authenticate edited solver metadata.

## Source boundary

- Original repository: https://github.com/gergetrump/factory-capacity-planner
- Source commit inspected: `3fcff5d80ded1ca816d14eda60c93a2a73266992`.
- `frontend` adapts the original React dashboard with ShiftForge branding and Java API port 8012.
- `src/main/resources/examples` contains original synthetic inputs.
- `src/test/resources/reference` contains five greedy exports generated from the source Python implementation on 19 November 2026. They establish deterministic compatibility, not Java optimizer performance.
- `frontend/tests/fixtures` contains four historical checked Python results (base/short-outage, Gurobi/greedy) used only for HTTP replay. Their original solver metadata remains historical Python evidence. They do not establish a Java Gurobi solve.
- The Java model, service, immutable records, strict validation, checker, HTTP server, CLI and JUnit tests were written for ShiftForge with substantial Codex assistance. No Python service or interpreter is invoked by the application.

No private company data, license file, credential, source virtual environment or original `.git` history is included. Gurobi is an external proprietary dependency governed by its own licensing; the Maven distribution is fetched during build rather than checked into this repository.

## SparkJava and persistence extension —19 November2026

The user requested adding SparkJava, PostgreSQL, jOOQ and real persistence integration tests. This extension replaces only the HTTP transport and adds append-only saved scenarios/runs and dashboard history flows. The model, algorithms and independent physical checker remain unchanged. Typed jOOQ fields/DSL are used without generated schema classes. New persistence behavior began with an integration test written before repository/config classes existed; its initial compile failure was observed, then the implementation and additional failure/UI tests were added. This records a specific test-first exercise, not a claim that every component was developed with TDD. Substantial Codex assistance and the native Gurobi license limit continue to apply. See[persistence design](persistence.md).
