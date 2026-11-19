# Saved scenarios and planning runs

ShiftForge's HTTP transport is SparkJava; planning, validation and checking are independent of the transport. PostgreSQL history is optional, and ephemeral `/api/solve` remains useful without it.

## Storage and transactions

`db/V001__history.sql` creates two append-only application tables:

- `saved_scenarios`: UUID, display name, validated scenario JSONB, database-created timestamp.
- `planning_runs`: UUID, foreign key to a saved scenario, exact input snapshot, complete result JSONB, algorithm, result status and timestamp.

The APIs have no update/delete endpoint. Saving edited inputs creates a new scenario. PostgreSQL JSONB may reorder object keys, but preserves array ordering and values; “exact snapshot” means semantic JSON equality, not original whitespace. The database owner can still edit data manually; this is not a tamper-proof audit ledger.

`HistoryStore` uses typed jOOQ `Table`/`Field<T>` definitions, typed DSL queries and bind parameters. It deliberately does not use code generation: field types are checked in Java, while integration tests exercise correspondence with the actual schema. It does not claim generated-schema compile-time checking. JDBC connections use three-second connection and five-second socket/query timeouts. A connection is opened per operation, appropriate to the bounded local demo; there is no production pool or distributed concurrency claim.

Migrations run in a transaction at configured startup. A PostgreSQL transaction-scoped advisory lock serializes migration startup; a version/checksum table prevents silently applying an edited migration. DDL and its migration record commit together. Saved scenario inserts and run inserts are transactional. A run re-reads its referenced immutable scenario in the transaction, checks input equality and accepted-result feasibility, and stores the complete result. A SQL check also relates input/algorithm/status columns to the result JSON.

The server generates results itself; clients cannot upload fabricated solver results. Saving a diagnostic such as `solver_unavailable` is useful history and does not imply an accepted plan.

## Configuration

```bash
export SHIFTFORGE_DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/shiftforge'
export SHIFTFORGE_DATABASE_USER='shiftforge'
export SHIFTFORGE_DATABASE_PASSWORD='local-demo-only'
java -jar target/shiftforge.jar serve --ui frontend/dist
```

The credentials above are only the checked-in local Compose demo credentials. Supply separate credentials for a different environment; secrets are not API fields and must not be committed. With no database URL the API starts in no-history mode. A configured database must be reachable at startup so migrations can finish; startup fails otherwise. If PostgreSQL fails after startup, history operations return JSON503 without credentials or a false success, while ephemeral planning remains available. `/api/health` is process health and reports whether history is configured, not database readiness.

```bash
docker compose up --build
# The app waits for PostgreSQL health. Open http://127.0.0.1:8012.
docker compose down
# The named history-data volume is retained. Do not use -v unless intentionally deleting history.
```

Host ports bind to loopback. Override `SHIFTFORGE_APP_PORT`/`SHIFTFORGE_POSTGRES_PORT` if needed. The app image runs as an unprivileged user. No Gurobi license is embedded.

## HTTP API

All timestamps are UTC ISO8601 strings and IDs are UUIDs. JSON requests reject unknown fields and preserve the original strict scenario validation.

| Method/path | Behavior |
| --- | --- |
| `POST /api/scenarios` | Plain scenario JSON →201 saved `{id,name,scenario,created_at}` |
| `GET /api/scenarios?limit=20&offset=0` | Summary page, newest first |
| `GET /api/scenarios/:id` | Full saved scenario;404 if absent |
| `POST /api/runs` | `{scenario_id,algorithm,solver_time_limit_seconds}` →201 saved run |
| `GET /api/runs?limit=20&offset=0` | Summary page, newest first |
| `GET /api/runs/:id` | `{id,scenario_id,scenario_name,result,created_at}`;404 if absent |
| `POST /api/solve` | Existing ephemeral solver interface, unchanged |

`algorithm` defaults to `gurobi`; time limit defaults to10 and accepts integers1–60. Lists return `{items,limit,offset,has_more}` with limit1–100, offset0–1000000 and stable timestamp/UUID ordering. Pages use offsets, so simultaneous new inserts can shift later pages; they are not a frozen database snapshot. UUID shape errors and invalid pagination/inputs return422; concurrent solve attempts409; disabled/unavailable history503. Saves return201 only after commit. A saved run can have a `solver_unavailable` result and still return201 because its diagnostic record was saved; inspect `result.status` before interpreting a run as a plan. `/api/solve` continues returning503 for unavailable Gurobi. A write failure after a solve returns503, with no invented saved ID.

The run input always comes from the referenced saved scenario. To plan an edited draft, save it first. The synchronous service has one nonblocking planning slot per process, not a queue. HTTP timeouts are not cancellation; if a save response is lost, refresh history before retrying. There is no idempotency-key guarantee.

## Dashboard

Expand **Saved scenarios & run history**. Save the current input, select a saved scenario, then choose **Open scenario** or **Plan and save run**. Saved runs can be reopened after page/app restarts, inspected and downloaded with their captured inputs. Older/newer controls navigate20-item pages. A run's actual status is shown, including an unavailable optimizer. History and main comparison actions share a busy guard, preventing competing requests from replacing each other's displayed state.

## Dependency boundary

SparkJava 2.9.4's Jetty dependencies are consistently overridden through the Jetty BOM to9.4.58.v20250814, rather than its original9.4.48 set. The resolved tree and Java 21 behavior were checked. Spark/Jetty9 are older libraries; this compatibility pin is not a claim of long-term support or a comprehensive security audit. This local portfolio service should not be treated as a production internet service. Spark's external static handler is not used; canonical-path confinement rejects assets whose symlinks escape the UI root.

Primary references: [Spark routing/filter documentation](https://sparkjava.com/documentation), [jOOQ transaction API](https://www.jooq.org/doc/latest/manual/sql-execution/transaction-management/), [Jetty compatible release](https://download.eclipse.org/jetty/updates/jetty-bundles-9.x/9.4.58.v20250814/).
