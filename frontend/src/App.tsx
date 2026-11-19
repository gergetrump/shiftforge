import { useEffect, useRef, useState } from 'react';
import { solve } from './api';
import { sameInput } from './snapshot';
import { ComparisonCard, MethodDifference, ResultDetails, methodName, number } from './Results';
import type { Algorithm, Comparison, Example, Scenario, ScenarioKind } from './types';

const copy = (scenario: Scenario): Scenario => structuredClone(scenario);
const same = sameInput;
const title = (kind: ScenarioKind) => kind === 'original' ? 'Original scenario' : 'Outage scenario';

export default function App() {
  const [examples, setExamples] = useState<Example[]>([]);
  const [loadError, setLoadError] = useState('');
  const [loadingExamples, setLoadingExamples] = useState(true);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [exampleId, setExampleId] = useState('');
  const [draft, setDraft] = useState<Scenario | null>(null);
  const [kind, setKind] = useState<ScenarioKind>('original');
  const [outageCell, setOutageCell] = useState('');
  const [start, setStart] = useState(1);
  const [end, setEnd] = useState(1);
  const [outageLabel, setOutageLabel] = useState('');
  const [controlMessage, setControlMessage] = useState('');
  const [runs, setRuns] = useState<Record<ScenarioKind, Comparison | null>>({ original: null, outage: null });
  const [inspected, setInspected] = useState<ScenarioKind>('original');
  const [method, setMethod] = useState<Algorithm>('gurobi');
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState('');
  const busyRef = useRef(false);
  const original = examples.find(example => example.id === exampleId)?.scenario;
  const shifts = draft ? [...draft.shifts].sort((a, b) => a.index - b.index) : [];
  const comparison = runs[inspected];
  const stale = !!comparison && inspected === kind && !!draft && !same(comparison.input, draft);

  function selectExample(example: Example) {
    const scenario = copy(example.scenario);
    const ordered = [...scenario.shifts].sort((a, b) => a.index - b.index);
    setExampleId(example.id); setDraft(scenario); setKind('original'); setInspected('original');
    setOutageCell(scenario.cells.find(cell => cell.id === 'C3')?.id ?? scenario.cells[0].id);
    setStart(ordered.length >= 6 ? 5 : 1); setEnd(ordered.length >= 6 ? 6 : ordered.length);
    setOutageLabel(''); setControlMessage(''); setRuns({ original: null, outage: null }); setProgress('');
  }

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 15_000);
    setLoadingExamples(true); setLoadError('');
    fetch('/api/examples', { signal: controller.signal }).then(async response => {
      if (!response.ok) throw new Error(`Examples request failed (${response.status}).`);
      const data = await response.json() as Example[];
      if (!active) return;
      if (!Array.isArray(data) || !data.length) throw new Error('The local API returned no examples.');
      setLoadError(''); setExamples(data); selectExample(data.find(example => example.id === 'base') ?? data[0]);
    }).catch(error => {
      if (!active) return;
      if (!controller.signal.aborted) setLoadError(error instanceof Error ? error.message : 'Could not load scenarios.');
      else setLoadError('The local API did not respond. Check that it is running, then retry.');
    }).finally(() => { window.clearTimeout(timeout); if (active) setLoadingExamples(false); });
    return () => { active = false; window.clearTimeout(timeout); controller.abort(); };
  }, [loadAttempt]);

  function applyOutage() {
    if (!original || busyRef.current) return;
    if (start > end) { setControlMessage('The first shift must come before or equal the last shift.'); return; }
    const scenario = copy(original);
    const removed = new Set(scenario.shifts.filter(shift => shift.index >= start && shift.index <= end).map(shift => shift.id));
    scenario.capacity = scenario.capacity.map(row => row.cell_id === outageCell && removed.has(row.shift_id) ? { ...row, available_machine_minutes: 0 } : row);
    setDraft(scenario); setKind('outage'); setInspected('outage');
    const label = `${outageCell} unavailable · ${shifts.find(shift => shift.index === start)?.id}–${shifts.find(shift => shift.index === end)?.id}`;
    setOutageLabel(label); setControlMessage(`Applied: ${label}. Run the comparison to evaluate this input.`);
  }

  function reset() {
    if (!original || busyRef.current) return;
    setDraft(copy(original)); setKind('original'); setInspected('original');
    setControlMessage('Original bundled capacity restored. Saved runs are retained for comparison.');
  }

  async function runComparison() {
    if (!draft || busyRef.current) return;
    busyRef.current = true; setBusy(true); setInspected(kind); setMethod('gurobi'); setControlMessage('');
    const snapshot = copy(draft); const runKind = kind;
    const captured: Comparison = { input: snapshot, label: runKind === 'original' ? 'Bundled capacity, unchanged.' : outageLabel, gurobi: null, greedy: null };
    setRuns(previous => ({ ...previous, [runKind]: captured }));
    try {
      for (const algorithm of ['gurobi', 'greedy'] as Algorithm[]) {
        setProgress(`Running ${methodName(algorithm)} on ${title(runKind).toLowerCase()}… ${algorithm === 'gurobi' ? '1' : '2'} of 2`);
        const outcome = await solve(snapshot, algorithm);
        captured[algorithm] = outcome;
        setRuns(previous => ({ ...previous, [runKind]: { ...captured } }));
      }
      setProgress(`Comparison complete for ${title(runKind).toLowerCase()}. Each method’s outcome is shown separately.`);
    } finally {
      busyRef.current = false; setBusy(false);
    }
  }

  return <div className="app-shell">
    <header className="topbar"><a href="#" className="brand" aria-label="ShiftForge home"><span className="brand-mark" aria-hidden="true"><i /><i /><i /></span><span>SHIFTFORGE</span></a><div className="topbar-right"><span className="synthetic"><span />Synthetic data</span><span className="local-label">Local planning prototype</span></div></header>
    <main>
      <div className="page-heading"><div><p className="eyebrow">Operations research · Scenario analysis</p><h1>Plan the next shift<span>.</span></h1><p className="intro">Allocate production across cells and shifts.<br className="desktop-break" /> Explore what changes when capacity goes offline.</p></div><div className="objective-note"><span className="eyebrow">Planning objective</span><strong>Minimize accumulated backlog</strong><span>Whole units · Fixed crews · Ordered shifts</span></div></div>
      {loadingExamples ? <div role="status" className="notice">Loading bundled scenarios…</div> : loadError ? <div role="alert" className="notice error"><strong>Could not load the local planner</strong><p>{loadError}</p><button className="secondary" onClick={() => setLoadAttempt(value => value + 1)}>Retry connection</button></div> : draft && original ? <>
        <section className="panel scenario-panel" aria-labelledby="scenario-title"><div className="panel-heading"><div className="number-heading"><span className="section-number">01</span><div><h2 id="scenario-title">Define the scenario</h2><p>Start with an example. Optionally remove one cell’s capacity over a shift window.</p></div></div><span className={`tag ${kind === 'outage' ? 'warning' : ''}`}>{kind === 'original' ? 'Original capacity' : 'Outage applied'}</span></div>
          <fieldset disabled={busy} className="controls-fieldset"><legend className="sr-only">Scenario and outage controls</legend><div className="scenario-controls"><label className="example-select">Example<select value={exampleId} onChange={event => { const example = examples.find(item => item.id === event.target.value); if (example) selectExample(example); }}>{examples.map(example => <option value={example.id} key={example.id}>{example.scenario.name}</option>)}</select></label><div className="scenario-facts"><span><strong>{draft.products.length}</strong> products</span><span><strong>{draft.cells.length}</strong> cells</span><span><strong>{draft.shifts.length}</strong> shifts</span></div></div>
          <div className="outage-controls"><div className="outage-label"><strong>Capacity outage</strong><small>One window; replaces the previous edit.</small></div><label>Cell<select value={outageCell} onChange={event => setOutageCell(event.target.value)}>{draft.cells.map(cell => <option key={cell.id}>{cell.id}</option>)}</select></label><label>First shift<select value={start} onChange={event => { setStart(Number(event.target.value)); setControlMessage(''); }}>{shifts.map(shift => <option key={shift.id} value={shift.index}>{shift.id}</option>)}</select></label><span className="range-separator" aria-hidden="true">→</span><label>Last shift<select value={end} onChange={event => { setEnd(Number(event.target.value)); setControlMessage(''); }}>{shifts.map(shift => <option key={shift.id} value={shift.index}>{shift.id}</option>)}</select></label><button className="secondary apply-button" onClick={applyOutage}>Apply outage</button><button className="text-button reset-button" onClick={reset}>Reset input</button></div></fieldset>
          <div className="run-row"><div><strong className="current-label">Current input: {kind === 'original' ? 'original capacity' : outageLabel}</strong><p>{kind === 'original' ? 'Run once to save a reference before testing an outage.' : 'Original inputs are preserved. Only the selected capacity entries are zeroed.'}</p></div><button className="primary" disabled={busy} onClick={runComparison}>{busy ? <><span className="spinner" aria-hidden="true" /> Running comparison</> : <>Run comparison <span aria-hidden="true">→</span></>}</button></div>
          <div className="live-status" aria-live="polite" role="status">{busy ? progress : controlMessage || progress || 'Gurobi, then greedy. Both methods use the same captured input.'}</div>
          <details className="scenario-details"><summary>Inspect current inputs &amp; model assumptions</summary><div className="details-body"><div className="assumption-grid"><div><h3>Aggregate allocation</h3><p>Products share eligible cell minutes. An active cell reserves its full crew for the shift. This is not an operation-by-operation schedule.</p></div><div><h3>Single objective</h3><p>Each overdue piece counts once at every shift end. Inventory and active cell-shifts are descriptive; equal-backlog plans can differ on both.</p></div><div><h3>Synthetic examples</h3><p>Invented inputs with no factory or company data. Unlimited materials and storage; no setup losses, yield losses, or named-worker assignments.</p></div></div><div className="table-scroll"><table className="input-table"><caption>Current available machine minutes</caption><thead><tr><th>Cell · crew</th>{shifts.map(shift => <th key={shift.id}>{shift.id}</th>)}</tr></thead><tbody>{draft.cells.map(cell => <tr key={cell.id}><th scope="row">{cell.id} · {cell.crew_operators}</th>{shifts.map(shift => { const capacity = draft.capacity.find(row => row.cell_id === cell.id && row.shift_id === shift.id); return <td key={shift.id} className={capacity?.available_machine_minutes === 0 ? 'has-backlog' : ''}>{number(capacity?.available_machine_minutes)}</td>; })}</tr>)}</tbody></table></div><details className="input-details"><summary>Current scenario JSON</summary><pre>{JSON.stringify(draft, null, 2)}</pre></details></div></details>
        </section>
        <section className="comparison-section" aria-labelledby="comparison-title"><div className="section-heading"><div className="number-heading"><span className="section-number">02</span><div><h2 id="comparison-title">Compare scenarios</h2><p>Keep the method fixed when comparing original and outage outcomes.</p></div></div></div><div className="comparison-grid">{(['original', 'outage'] as ScenarioKind[]).map(runKind => <ComparisonCard key={runKind} kind={runKind} comparison={runs[runKind]} selected={inspected === runKind} stale={runKind === kind && !!runs[runKind] && !same(runs[runKind]!.input, draft)} running={busy && runKind === kind} onSelect={() => setInspected(runKind)} />)}</div></section>
        <section className="panel results-panel" aria-labelledby="results-title"><div className="panel-heading"><div className="number-heading"><span className="section-number">03</span><div><h2 id="results-title">Inspect the plan</h2><p>{title(inspected)}{comparison ? ` · ${comparison.label}` : ' · No saved run'}</p></div></div><div className="method-switch" role="group" aria-label="Result method">{(['gurobi', 'greedy'] as Algorithm[]).map(algorithm => <button key={algorithm} aria-pressed={method === algorithm} onClick={() => setMethod(algorithm)}>{methodName(algorithm)}</button>)}</div></div>
          {stale && <div className="notice warning" role="status"><strong>Saved results are stale.</strong> The current input has changed. Run the comparison to refresh; displayed metrics and downloads still use the saved input.</div>}
          {comparison && inspected !== kind && <div className="notice neutral">Viewing the saved {inspected} reference. The current editable input is the {kind} scenario.</div>}
          {comparison && <MethodDifference comparison={comparison} />}
          <ResultDetails outcome={comparison?.[method] ?? null} method={method} kind={inspected} />
        </section>
      </> : null}
      <footer><strong>ShiftForge</strong><span>Synthetic aggregate planning · Local prototype · Backlog is the only objective</span></footer>
    </main>
  </div>;
}
