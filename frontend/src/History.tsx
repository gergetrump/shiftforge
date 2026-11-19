import { useEffect, useRef, useState } from 'react';
import { methodName, statusName } from './Results';
import type { Algorithm, Result, Scenario } from './types';

type SavedScenario = { id: string; name: string; scenario: Scenario; created_at: string };
type ScenarioSummary = Pick<SavedScenario, 'id' | 'name' | 'created_at'>;
type SavedRun = { id: string; scenario_id: string; scenario_name: string; result: Result; created_at: string };
type RunSummary = Omit<SavedRun, 'result'> & { algorithm: Algorithm; status: Result['status'] };
type Page<T> = { items: T[]; limit: number; offset: number; has_more: boolean };

async function request<T>(path: string, body?: unknown): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 45_000);
  try {
    const response = await fetch(path, { signal: controller.signal,
      ...(body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) });
    const value = await response.json();
    if (!response.ok) throw new Error(typeof value.detail === 'string' ? value.detail : `History request failed (${response.status}).`);
    return value as T;
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw new Error('History request timed out. A save may have completed; refresh history before retrying.');
    throw error;
  } finally { window.clearTimeout(timeout); }
}

export function History({ draft, disabled, onScenario, onRun, onBusyChange }: {
  draft: Scenario; disabled: boolean; onScenario: (scenario: Scenario, id: string) => void; onRun: (result: Result, id: string) => void; onBusyChange: (busy: boolean) => void;
}) {
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [scenarios, setScenarios] = useState<Page<ScenarioSummary> | null>(null);
  const [runs, setRuns] = useState<Page<RunSummary> | null>(null);
  const [scenarioId, setScenarioId] = useState('');
  const [runId, setRunId] = useState('');
  const [algorithm, setAlgorithm] = useState<Algorithm>('greedy');
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  async function refresh(scenarioOffset = 0, runOffset = 0) {
    const [nextScenarios, nextRuns] = await Promise.all([
      request<Page<ScenarioSummary>>(`/api/scenarios?limit=20&offset=${scenarioOffset}`),
      request<Page<RunSummary>>(`/api/runs?limit=20&offset=${runOffset}`),
    ]);
    setScenarios(nextScenarios); setRuns(nextRuns);
    setScenarioId(current => nextScenarios.items.some(item => item.id === current) ? current : nextScenarios.items[0]?.id ?? '');
    setRunId(current => nextRuns.items.some(item => item.id === current) ? current : nextRuns.items[0]?.id ?? '');
  }
  useEffect(() => {
    let active = true;
    request<{ history_enabled: boolean }>('/api/health').then(async health => {
      if (!active) return;
      setEnabled(health.history_enabled === true);
      if (health.history_enabled) await refresh();
    }).catch(() => { if (active) setError('History could not be loaded. The planner remains usable.'); });
    return () => { active = false; };
  }, []);

  async function perform(action: () => Promise<void>) {
    if (disabled || busyRef.current) return;
    busyRef.current = true; onBusyChange(true); setBusy(true); setError(''); setMessage('');
    try { await action(); }
    catch (failure) { setError(failure instanceof Error ? failure.message : 'History request failed.'); }
    finally { busyRef.current = false; onBusyChange(false); setBusy(false); }
  }
  return <details className="panel history-panel"><summary>Saved scenarios &amp; run history</summary>
    <div className="history-content">
      <p>Save an immutable input, run it, and reopen the exact result later. Editing the planner never rewrites saved history.</p>
      {enabled === false ? <p className="notice neutral">History is disabled. Start the app with PostgreSQL to save and reopen scenarios.</p> : <>
        <fieldset disabled={disabled || busy || enabled !== true} className="controls-fieldset">
          <div className="history-actions"><button className="secondary" onClick={() => perform(async () => {
            const saved = await request<SavedScenario>('/api/scenarios', draft);
            setScenarioId(saved.id); setMessage(`Scenario saved: ${saved.name}.`); await refresh();
          })}>Save current scenario</button><button className="text-button" onClick={() => perform(async () => { await refresh(); setMessage('History refreshed.'); })}>Refresh history</button></div>
          <div className="history-columns"><div>
            <label>Saved scenario<select value={scenarioId} onChange={event => setScenarioId(event.target.value)}><option value="">Select a saved scenario</option>{scenarios?.items.map(item => <option value={item.id} key={item.id}>{item.name} · {new Date(item.created_at).toLocaleString()}</option>)}</select></label>
            <div className="history-actions"><button className="secondary" disabled={!scenarioId} onClick={() => perform(async () => {
              const saved = await request<SavedScenario>(`/api/scenarios/${scenarioId}`); onScenario(saved.scenario, saved.id); setMessage(`Opened saved scenario: ${saved.name}.`);
            })}>Open scenario</button><label>Planning method<select value={algorithm} onChange={event => setAlgorithm(event.target.value as Algorithm)}><option value="greedy">Greedy heuristic</option><option value="gurobi">Gurobi MILP</option></select></label>
            <button className="secondary" disabled={!scenarioId} onClick={() => perform(async () => {
              const saved = await request<SavedRun>('/api/runs', { scenario_id: scenarioId, algorithm, solver_time_limit_seconds: 10 });
              onRun(saved.result, saved.id); setRunId(saved.id);
              setMessage(`Run saved with status: ${statusName(saved.result.status)}.`); await refresh(scenarios?.offset ?? 0);
            })}>Plan and save run</button></div>
            <div className="history-actions"><button className="text-button" disabled={!scenarios?.offset} onClick={() => perform(() => refresh(Math.max(0, (scenarios?.offset ?? 0) - 20), runs?.offset ?? 0))}>Newer scenarios</button><button className="text-button" disabled={!scenarios?.has_more} onClick={() => perform(() => refresh((scenarios?.offset ?? 0) + 20, runs?.offset ?? 0))}>Older scenarios</button></div>
          </div><div>
            <label>Saved run<select value={runId} onChange={event => setRunId(event.target.value)}><option value="">Select a saved run</option>{runs?.items.map(item => <option value={item.id} key={item.id}>{item.scenario_name} · {methodName(item.algorithm)} · {statusName(item.status)} · {new Date(item.created_at).toLocaleString()}</option>)}</select></label>
            <button className="secondary" disabled={!runId} onClick={() => perform(async () => { const saved = await request<SavedRun>(`/api/runs/${runId}`); onRun(saved.result, saved.id); setMessage(`Opened saved run with status: ${statusName(saved.result.status)}.`); })}>Open run</button>
            <div className="history-actions"><button className="text-button" disabled={!runs?.offset} onClick={() => perform(() => refresh(scenarios?.offset ?? 0, Math.max(0, (runs?.offset ?? 0) - 20)))}>Newer runs</button><button className="text-button" disabled={!runs?.has_more} onClick={() => perform(() => refresh(scenarios?.offset ?? 0, (runs?.offset ?? 0) + 20))}>Older runs</button></div>
          </div></div>
        </fieldset>
      </>}
      {busy && <p role="status">Updating saved history…</p>}
      {message && <p role="status">{message}</p>}
      {error && <p role="alert" className="notice error">{error}</p>}
    </div>
  </details>;
}
