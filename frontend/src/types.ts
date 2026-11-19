export interface Scenario {
  schema_version: 1;
  name: string;
  data_origin: 'synthetic';
  products: { id: string; initial_inventory_units: number }[];
  cells: { id: string; crew_operators: number }[];
  processing_times: { product_id: string; cell_id: string; minutes_per_unit: number }[];
  shifts: { id: string; index: number; duration_minutes: number; available_operators: number }[];
  capacity: { cell_id: string; shift_id: string; available_machine_minutes: number }[];
  demand: { product_id: string; shift_id: string; due_units: number }[];
}
export type Algorithm = 'gurobi' | 'greedy';
export type ScenarioKind = 'original' | 'outage';
export interface Result {
  format_version: 1;
  input: Scenario;
  algorithm: Algorithm;
  status: 'optimal' | 'feasible' | 'heuristic_feasible' | 'no_solution' | 'solver_unavailable' | 'solver_error' | 'invalid_result';
  settings: Record<string, number>;
  solver: null | {
    version: string; termination: string; termination_code: number;
    raw_objective: number | null; objective_bound: number | null;
    relative_gap: number | null; solve_runtime_seconds: number;
  };
  solution: null | {
    objective: number;
    production: { product_id: string; cell_id: string; shift_id: string; units: number }[];
    activity: { cell_id: string; shift_id: string; active: number }[];
    stock: { product_id: string; shift_id: string; inventory_units: number; backlog_units: number }[];
    metrics: {
      backlog_unit_shifts: number; final_backlog_units: number; active_cell_shifts: number;
      inventory_unit_shifts: number; produced_units: number;
      machine_minutes_used: number; machine_minutes_available: number; capacity_utilization: number | null;
      cell_usage: { cell_id: string; shift_id: string; used_minutes: number; available_minutes: number; utilization: number | null }[];
      staffing: { shift_id: string; reserved_operators: number; available_operators: number; reservation_share: number | null }[];
    };
  };
  validation: null | { valid: boolean; errors: string[] };
  error: string | null;
}
export interface Outcome { result: Result | null; error: string | null }
export interface Comparison {
  input: Scenario;
  label: string;
  gurobi: Outcome | null;
  greedy: Outcome | null;
}
export interface Example { id: string; scenario: Scenario }
