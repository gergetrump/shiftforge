CREATE TABLE saved_scenarios (
    id uuid PRIMARY KEY,
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 160),
    scenario_json jsonb NOT NULL CHECK (jsonb_typeof(scenario_json) = 'object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE planning_runs (
    id uuid PRIMARY KEY,
    scenario_id uuid NOT NULL REFERENCES saved_scenarios(id),
    input_snapshot jsonb NOT NULL CHECK (jsonb_typeof(input_snapshot) = 'object'),
    result_json jsonb NOT NULL CHECK (jsonb_typeof(result_json) = 'object'),
    algorithm text NOT NULL CHECK (algorithm IN ('greedy', 'gurobi')),
    status text NOT NULL CHECK (status IN ('optimal','feasible','heuristic_feasible','no_solution','solver_unavailable','solver_error','invalid_result')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (result_json->'input' = input_snapshot),
    CHECK (result_json->>'algorithm' = algorithm),
    CHECK (result_json->>'status' = status)
);
CREATE INDEX saved_scenarios_recent ON saved_scenarios (created_at DESC, id DESC);
CREATE INDEX planning_runs_recent ON planning_runs (created_at DESC, id DESC);
CREATE INDEX planning_runs_scenario ON planning_runs (scenario_id, created_at DESC, id DESC);
