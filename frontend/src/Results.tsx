import type { Algorithm, Comparison, Outcome, Result, ScenarioKind } from './types';
import { downloadResult } from './api';
import { sameInput } from './snapshot';

export const number = (value: number | null | undefined) => value == null ? '—' : value.toLocaleString('en-GB', { maximumFractionDigits: 3 });
const percent = (value: number | null) => value == null ? 'N/A' : `${(value * 100).toFixed(1)}%`;
export const methodName = (method: Algorithm) => method === 'gurobi' ? 'Gurobi MILP' : 'Greedy heuristic';
const statuses: Record<Result['status'], string> = {
  optimal: 'Optimal', feasible: 'Feasible · unproven', heuristic_feasible: 'Heuristic · feasible',
  no_solution: 'No incumbent', solver_unavailable: 'Solver unavailable', solver_error: 'Solver error', invalid_result: 'Invalid result',
};
const accepted = (result?: Result | null) => result?.solution && result.validation?.valid ? result.solution : null;

export function ComparisonCard({ kind, comparison, selected, stale, running, onSelect }: {
  kind: ScenarioKind; comparison: Comparison | null; selected: boolean; stale: boolean; running: boolean; onSelect: () => void;
}) {
  return <article className={`comparison-card ${selected ? 'selected' : ''}`}>
    <div className="card-title"><h3>{kind === 'original' ? 'Original scenario' : 'Outage scenario'}</h3><span className={`tag ${stale ? 'warning' : ''}`}>{running ? 'Running' : stale ? 'Stale' : comparison ? 'Saved run' : 'Not run'}</span></div>
    <p className="comparison-caption">{comparison?.label ?? (kind === 'original' ? 'Bundled capacity, unchanged.' : 'Apply a capacity outage, then run.')}</p>
    <table className="compact"><caption className="sr-only">{kind} accumulated backlog comparison, unit-shifts</caption><thead><tr><th>Method</th><th>Backlog <span className="unit">unit-shifts</span></th></tr></thead><tbody>
      {(['gurobi', 'greedy'] as Algorithm[]).map(method => {
        const outcome = comparison?.[method]; const plan = accepted(outcome?.result);
        return <tr key={method}><th scope="row">{methodName(method)}</th><td>{plan ? number(plan.metrics.backlog_unit_shifts) : <span className="muted small">{outcome ? outcome.result ? statuses[outcome.result.status] : 'Request failed' : running ? 'Pending' : '—'}</span>}</td></tr>;
      })}
    </tbody></table>
    <button className="text-button" disabled={!comparison} aria-pressed={selected} onClick={onSelect}>Inspect {kind} results <span aria-hidden="true">↗</span></button>
  </article>;
}

export function MethodDifference({ comparison }: { comparison: Comparison }) {
  const gurobi = comparison.gurobi?.result; const greedy = comparison.greedy?.result;
  const g = accepted(gurobi); const h = accepted(greedy);
  if (!g || !h || !sameInput(gurobi!.input, greedy!.input)) return null;
  const difference = h.metrics.backlog_unit_shifts - g.metrics.backlog_unit_shifts;
  return <div className="difference"><span className="eyebrow">Same input · method comparison</span><p><strong>{number(difference)}</strong> unit-shifts of backlog difference <span className="muted">(greedy − Gurobi)</span></p><small>{gurobi!.status === 'optimal' ? 'Gurobi established the minimum backlog under solver tolerances.' : 'Gurobi has a checked feasible plan; minimum backlog is not established.'} Activity and inventory are not optimized.</small></div>;
}

export function ResultDetails({ outcome, method, kind }: { outcome: Outcome | null; method: Algorithm; kind: ScenarioKind }) {
  if (!outcome) return <div className="empty-state"><span className="empty-symbol" aria-hidden="true">⋯</span><h3>No {methodName(method)} result yet</h3><p>Run a comparison to evaluate both methods against the same captured input.</p></div>;
  if (!outcome.result) return <div role="alert" className="notice error"><strong>{methodName(method)} request failed</strong><p>{outcome.error}</p><p>The other method’s outcome is kept separately.</p></div>;
  const result = outcome.result; const solution = accepted(result); const metrics = solution?.metrics;
  const shifts = [...result.input.shifts].sort((a, b) => a.index - b.index);
  return <>
    <div className="result-meta"><div className="result-state"><span className={`status-dot ${solution ? 'good' : 'bad'}`} /><strong>{statuses[result.status]}</strong><span className="muted">{result.validation?.valid ? 'Independently checked' : result.validation ? 'Validation failed' : 'No candidate checked'}</span></div><button className="secondary small-button" onClick={() => downloadResult(result, kind)}>Download result JSON <span aria-hidden="true">↓</span></button></div>
    {result.error && <div role="alert" className="notice error">{result.error}</div>}
    {result.validation && !result.validation.valid && <ul className="notice error">{result.validation.errors.map((error, i) => <li key={i}>{error}</li>)}</ul>}
    {solution && metrics ? <>
      <div className="kpi-grid">
        <div className="kpi primary-kpi"><span className="eyebrow">Only objective</span><strong>{number(metrics.backlog_unit_shifts)}</strong><span>Accumulated backlog</span><small>unit-shifts overdue</small></div>
        <div className="kpi"><span className="eyebrow">At horizon end</span><strong>{number(metrics.final_backlog_units)}</strong><span>Final backlog</span><small>units overdue</small></div>
        <div className="kpi"><span className="eyebrow">Reported · unoptimized</span><strong>{number(metrics.active_cell_shifts)}</strong><span>Active cell-shifts</span><small>whole-shift crew reservations</small></div>
        <div className="kpi"><span className="eyebrow">Reported · unoptimized</span><strong>{number(metrics.inventory_unit_shifts)}</strong><span>Inventory</span><small>unit-shifts held</small></div>
      </div>
      <div className="section-heading"><div><h3>Production allocation</h3><p>Whole units by cell and shift. Open an allocation to see product quantities.</p></div><span className="tag">{number(metrics.produced_units)} units produced</span></div>
      <div className="table-scroll"><table className="allocation-table"><caption className="sr-only">Production allocation in units</caption><thead><tr><th scope="col">Cell / crew</th>{shifts.map(shift => <th scope="col" key={shift.id}>{shift.id}<small>Shift {shift.index}</small></th>)}</tr></thead><tbody>{result.input.cells.map(cell => <tr key={cell.id}><th scope="row">{cell.id}<small>{cell.crew_operators} operators</small></th>{shifts.map(shift => {
        const rows = solution.production.filter(row => row.cell_id === cell.id && row.shift_id === shift.id && row.units > 0);
        const active = solution.activity.find(row => row.cell_id === cell.id && row.shift_id === shift.id)?.active;
        const usage = metrics.cell_usage.find(row => row.cell_id === cell.id && row.shift_id === shift.id);
        return <td className={usage?.available_minutes === 0 ? 'outage-cell' : active ? 'active-cell' : ''} key={shift.id}>{rows.length ? <details><summary aria-label={`${cell.id}, ${shift.id}: ${rows.reduce((sum, row) => sum + row.units, 0)} units; expand product quantities`}>{number(rows.reduce((sum, row) => sum + row.units, 0))}<span className="unit"> units</span></summary><div className="product-list">{rows.map(row => <div key={row.product_id}><span>{row.product_id}</span><strong>{number(row.units)}</strong></div>)}</div></details> : <span className="muted">{usage?.available_minutes === 0 ? 'Outage' : 'Idle'}</span>}</td>;
      })}</tr>)}</tbody></table></div>
      <div className="section-heading"><div><h3>Resource use</h3><p>Used / available capacity. Staffing measures reserved headcount, not worker busyness.</p></div><span className="tag">Machine use {percent(metrics.capacity_utilization)}</span></div>
      <div className="resource-grid"><div className="table-scroll"><table className="resource-table"><caption>Machine minutes by cell and shift</caption><thead><tr><th>Shift</th>{result.input.cells.map(cell => <th key={cell.id}>{cell.id}</th>)}</tr></thead><tbody>{shifts.map(shift => <tr key={shift.id}><th scope="row">{shift.id}</th>{result.input.cells.map(cell => {
        const usage = metrics.cell_usage.find(row => row.cell_id === cell.id && row.shift_id === shift.id);
        return <td key={cell.id}>{usage ? <ResourceBar used={usage.used_minutes} available={usage.available_minutes} share={usage.utilization} label={`${cell.id}, ${shift.id}, machine minutes`} /> : '—'}</td>;
      })}</tr>)}</tbody><tfoot><tr><th>Total minutes</th><td colSpan={result.input.cells.length}>{number(metrics.machine_minutes_used)} / {number(metrics.machine_minutes_available)}</td></tr></tfoot></table></div>
      <div className="table-scroll"><table className="resource-table"><caption>Whole-shift staffing</caption><thead><tr><th>Shift</th><th>Operators reserved / available</th></tr></thead><tbody>{shifts.map(shift => {
        const staffing = metrics.staffing.find(row => row.shift_id === shift.id);
        return <tr key={shift.id}><th scope="row">{shift.id}</th><td>{staffing ? <ResourceBar used={staffing.reserved_operators} available={staffing.available_operators} share={staffing.reservation_share} label={`${shift.id}, operators reserved`} /> : '—'}</td></tr>;
      })}</tbody></table></div></div>
      <div className="section-heading"><div><h3>Backlog by product</h3><p>Units overdue at each shift end. A piece overdue for two shifts counts twice in the objective.</p></div></div>
      <div className="table-scroll"><table className="backlog-table"><caption className="sr-only">End-of-shift backlog in units</caption><thead><tr><th>Product</th>{shifts.map(shift => <th key={shift.id}>{shift.id}</th>)}</tr></thead><tbody>{result.input.products.map(product => <tr key={product.id}><th scope="row">{product.id}</th>{shifts.map(shift => {
        const stock = solution.stock.find(row => row.product_id === product.id && row.shift_id === shift.id);
        return <td className={stock?.backlog_units ? 'has-backlog' : 'muted'} key={shift.id}>{number(stock?.backlog_units)}</td>;
      })}</tr>)}</tbody></table></div>
    </> : <div className="empty-state compact-empty"><h3>No accepted plan to display</h3><p>{result.status === 'no_solution' ? 'The solver finished without an incumbent. A bound alone is not a production plan.' : 'Inspect the outcome above. The result JSON preserves this method’s exact input and diagnostic status.'}</p></div>}
    <details className="solver-details"><summary>Method details, solver termination &amp; exact input</summary><div className="details-body"><dl className="details-grid"><div><dt>Algorithm</dt><dd>{methodName(result.algorithm)}</dd></div><div><dt>Checked status</dt><dd>{result.status}</dd></div><div><dt>Scenario snapshot</dt><dd>{result.input.name}</dd></div><div><dt>Objective</dt><dd>{number(solution?.objective)} unit-shifts</dd></div>{result.solver ? <><div><dt>Gurobi version</dt><dd>{result.solver.version}</dd></div><div><dt>Termination</dt><dd>{result.solver.termination} ({result.solver.termination_code})</dd></div><div><dt>Raw objective / bound</dt><dd>{number(result.solver.raw_objective)} / {number(result.solver.objective_bound)}</dd></div><div><dt>Relative gap</dt><dd>{percent(result.solver.relative_gap)}</dd></div><div><dt>Solver runtime</dt><dd>{number(result.solver.solve_runtime_seconds)} s</dd></div></> : <div><dt>Solver</dt><dd>No Gurobi solver information for this result.</dd></div>}</dl><p className="small muted">The 10-second Gurobi limit applies to optimization, not the full HTTP request. A 45-second client timeout does not cancel backend work. Inventory and activity have no secondary objective.</p>{Object.keys(result.settings).length > 0 && <pre>{JSON.stringify(result.settings, null, 2)}</pre>}<details className="input-details"><summary>Exact input snapshot</summary><pre>{JSON.stringify(result.input, null, 2)}</pre></details></div></details>
  </>;
}

function ResourceBar({ used, available, share, label }: { used: number; available: number; share: number | null; label: string }) {
  return <div className="resource-bar"><div><span>{number(used)} <span className="muted">/ {number(available)}</span></span><small>{percent(share)}</small></div><div className={`bar-track ${share == null ? 'no-capacity' : ''}`} role="img" aria-label={`${label}: ${used} of ${available}, ${percent(share)}`}><span style={{ width: `${Math.max(0, Math.min(100, (share ?? 0) * 100))}%` }} /></div></div>;
}
