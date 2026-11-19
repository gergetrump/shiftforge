import { readFileSync } from 'node:fs';
import { isDeepStrictEqual } from 'node:util';
import { expect, test, type Page } from '@playwright/test';
import type { Algorithm, Result, Scenario } from '../src/types';

// Replay checked real solve results, rather than manufacturing simplified UI
// metrics. The UI preserves the base name when applying an outage, while the
// Python example helper appends an outage label. Normalize that label only.
function fixture(name: string): Result {
  return JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));
}
const base = fixture('base-gurobi').input;
const results = ['base-gurobi', 'base-greedy', 'short_outage-gurobi', 'short_outage-greedy'].map(name => {
  const result = fixture(name);
  result.input.name = base.name;
  return result;
});
type SolveRequest = { scenario: Scenario; algorithm: Algorithm; solver_time_limit_seconds: number };

async function replayApi(page: Page, mode: 'normal' | 'unavailable' | 'mismatched' = 'normal') {
  const requests: SolveRequest[] = [];
  const unmatched: SolveRequest[] = [];
  await page.route('**/api/health', route => route.fulfill({ json: { history_enabled: false } }));
  await page.route('**/api/examples', route => route.fulfill({ json: [{ id: 'base', scenario: base }] }));
  await page.route('**/api/solve', async route => {
    const request = route.request().postDataJSON() as SolveRequest;
    requests.push(request);
    const saved = results.find(result => result.algorithm === request.algorithm && isDeepStrictEqual(result.input, request.scenario));
    if (!saved) {
      unmatched.push(request);
      await route.fulfill({ status: 500, json: { detail: 'No recorded fixture matches this exact method and input.' } });
      return;
    }
    const response = structuredClone(saved);
    if (mode === 'unavailable' && request.algorithm === 'gurobi') {
      Object.assign(response, { status: 'solver_unavailable', solver: null, solution: null, validation: null,
        error: 'Gurobi is unavailable in this replayed failure case.' });
      await route.fulfill({ status: 503, json: response });
    } else {
      if (mode === 'mismatched' && request.algorithm === 'gurobi') {
        // Deliberately wrong response snapshot: the app must reject it even
        // though the response otherwise resembles a successful solve.
        response.input.capacity[0].available_machine_minutes -= 1;
      }
      await route.fulfill({ json: response });
    }
  });
  return { requests, unmatched };
}

async function runComparison(page: Page, kind: 'original' | 'outage') {
  await page.getByRole('button', { name: /Run comparison/ }).click();
  await expect(page.getByRole('status').filter({ hasText: `Comparison complete for ${kind} scenario.` })).toBeVisible();
}

async function download(page: Page) {
  const pending = page.waitForEvent('download');
  await page.getByRole('button', { name: /Download result JSON/ }).click();
  const file = await pending;
  const path = await file.path();
  if (!path) throw new Error('Browser did not save the result download');
  return { filename: file.suggestedFilename(), result: JSON.parse(readFileSync(path, 'utf8')) as Result };
}

test('unsolved outage edits mark saved results stale and cannot rewrite exports or references', async ({ page }) => {
  const api = await replayApi(page);
  await page.goto('/');
  await runComparison(page, 'original');
  const original = await download(page);
  expect(original.result).toEqual(results.find(r => r.algorithm === 'gurobi' && isDeepStrictEqual(r.input, base)));

  await page.getByRole('combobox', { name: 'Cell', exact: true }).selectOption('C3');
  await page.getByRole('combobox', { name: 'First shift', exact: true }).selectOption('5');
  await page.getByRole('combobox', { name: 'Last shift', exact: true }).selectOption('6');
  await page.getByRole('button', { name: 'Apply outage', exact: true }).click();
  await runComparison(page, 'outage');
  const solvedOutage = api.requests[2].scenario;
  expect(solvedOutage.capacity.filter(r => r.available_machine_minutes === 0).map(r => `${r.cell_id}/${r.shift_id}`)).toEqual(['C3/T5', 'C3/T6']);
  expect(api.requests.map(r => r.algorithm)).toEqual(['gurobi', 'greedy', 'gurobi', 'greedy']);
  expect(api.requests[0].scenario).toEqual(api.requests[1].scenario);
  expect(api.requests[2].scenario).toEqual(api.requests[3].scenario);

  await page.getByRole('combobox', { name: 'Last shift', exact: true }).selectOption('10');
  // Changing an outage control alone does not change the applied input.
  await expect(page.getByText('Saved results are stale.', { exact: true })).toBeHidden();
  expect(api.requests).toHaveLength(4);
  await page.getByRole('button', { name: 'Apply outage', exact: true }).click();
  await expect(page.getByText('Saved results are stale.', { exact: true })).toBeVisible();
  const outageCard = page.getByRole('article').filter({ has: page.getByRole('heading', { name: 'Outage scenario', exact: true }) });
  await expect(outageCard.getByText('Stale', { exact: true })).toBeVisible();
  expect(api.requests).toHaveLength(4); // Editing is not a hidden solve.
  await page.getByText('Inspect current inputs & model assumptions', { exact: true }).click();
  await page.getByText('Current scenario JSON', { exact: true }).click();
  const current = JSON.parse(await page.getByRole('region', { name: 'Define the scenario' }).locator('pre').innerText()) as Scenario;
  expect(current.capacity.filter(r => r.available_machine_minutes === 0).map(r => `${r.cell_id}/${r.shift_id}`)).toEqual([
    'C3/T5', 'C3/T6', 'C3/T7', 'C3/T8', 'C3/T9', 'C3/T10',
  ]);

  const gurobi = await download(page);
  expect(gurobi.filename).toBe('capacity-plan-outage-gurobi.json');
  expect(gurobi.result).toEqual(results.find(r => r.algorithm === 'gurobi' && isDeepStrictEqual(r.input, solvedOutage)));
  await page.getByRole('button', { name: 'Greedy heuristic', exact: true }).click();
  const greedy = await download(page);
  expect(greedy.filename).toBe('capacity-plan-outage-greedy.json');
  expect(greedy.result).toEqual(results.find(r => r.algorithm === 'greedy' && isDeepStrictEqual(r.input, solvedOutage)));
  expect(greedy.result.solution?.objective).toBe(128);

  await page.getByRole('button', { name: 'Reset input', exact: true }).click();
  await expect(page.getByText('Saved results are stale.', { exact: true })).toBeHidden();
  await page.getByRole('button', { name: 'Gurobi MILP', exact: true }).click();
  expect((await download(page)).result).toEqual(original.result);
  await page.getByRole('button', { name: /Inspect outage results/ }).click();
  await expect(page.getByText('Viewing the saved outage reference.', { exact: false })).toBeVisible();
  expect((await download(page)).result).toEqual(gurobi.result);
  expect(api.requests).toHaveLength(4);
  expect(api.unmatched).toEqual([]);
});

test('unavailable Gurobi keeps its diagnostic separate while greedy remains usable', async ({ page }) => {
  const api = await replayApi(page, 'unavailable');
  await page.goto('/');
  await runComparison(page, 'original');
  const details = page.getByRole('region', { name: 'Inspect the plan' });
  await expect(details.getByText('Solver unavailable', { exact: true })).toBeVisible();
  await expect(details.getByText('No accepted plan to display', { exact: true })).toBeVisible();
  await expect(page.getByText('Same input · method comparison', { exact: true })).toBeHidden();
  const failure = await download(page);
  expect(failure.result.status).toBe('solver_unavailable');
  expect(failure.result.solution).toBeNull();
  expect(failure.result.input).toEqual(base);
  await page.getByRole('button', { name: 'Greedy heuristic', exact: true }).click();
  await expect(details.getByText('Heuristic · feasible', { exact: true })).toBeVisible();
  expect((await download(page)).result).toEqual(results.find(r => r.algorithm === 'greedy' && isDeepStrictEqual(r.input, base)));
  expect(api.requests.map(r => r.algorithm)).toEqual(['gurobi', 'greedy']);
  expect(api.unmatched).toEqual([]);
});

test('a response for a different input is rejected without discarding the other method', async ({ page }) => {
  const api = await replayApi(page, 'mismatched');
  await page.goto('/');
  await runComparison(page, 'original');
  const details = page.getByRole('region', { name: 'Inspect the plan' });
  await expect(details.getByRole('alert')).toContainText('The response did not match the requested method and input. It cannot be compared.');
  await expect(page.getByRole('button', { name: /Download result JSON/ })).toBeHidden();
  await expect(page.getByText('Same input · method comparison', { exact: true })).toBeHidden();
  await page.getByRole('button', { name: 'Greedy heuristic', exact: true }).click();
  expect((await download(page)).result).toEqual(results.find(r => r.algorithm === 'greedy' && isDeepStrictEqual(r.input, base)));
  expect(api.requests.map(r => r.algorithm)).toEqual(['gurobi', 'greedy']);
  expect(api.unmatched).toEqual([]);
});

test('saved scenarios and runs retain their snapshots and serialize planner actions', async ({ page }) => {
  const scenarios: {id: string; name: string; scenario: Scenario; created_at: string}[] = [];
  const runs: {id: string; scenario_id: string; scenario_name: string; result: Result; created_at: string}[] = [];
  let runEntered: () => void = () => {};
  let finishRun: () => void = () => {};
  const entered = new Promise<void>(resolve => { runEntered = resolve; });
  const finish = new Promise<void>(resolve => { finishRun = resolve; });
  await page.route('**/api/health', route => route.fulfill({json: {history_enabled: true}}));
  await page.route('**/api/examples', route => route.fulfill({json: [{id:'base', scenario:base}]}));
  await page.route(/\/api\/scenarios(?:\?.*)?$/, async route => {
    if (route.request().method() === 'POST') {
      const scenario = route.request().postDataJSON() as Scenario;
      expect(scenario).toEqual(base);
      const saved = {id:'11111111-1111-1111-1111-111111111111',name:scenario.name,scenario,created_at:'2026-11-19T12:00:00Z'};
      scenarios.push(saved); await route.fulfill({status:201,json:saved});
    } else await route.fulfill({json:{items:scenarios,limit:20,offset:0,has_more:false}});
  });
  await page.route('**/api/scenarios/*', async route => route.fulfill({json:scenarios[0]}));
  await page.route(/\/api\/runs(?:\?.*)?$/, async route => {
    if(route.request().method() === 'POST') {
      expect(route.request().postDataJSON()).toEqual({scenario_id:scenarios[0].id,algorithm:'greedy',solver_time_limit_seconds:10});
      runEntered(); await finish;
      const result = results.find(r => r.algorithm === 'greedy' && isDeepStrictEqual(r.input,base))!;
      const saved={id:'22222222-2222-2222-2222-222222222222',scenario_id:scenarios[0].id,scenario_name:base.name,result,created_at:'2026-11-19T12:01:00Z'};
      runs.push(saved); await route.fulfill({status:201,json:saved});
    } else await route.fulfill({json:{items:runs.map(r=>({...r,algorithm:r.result.algorithm,status:r.result.status})),limit:20,offset:0,has_more:false}});
  });
  await page.route('**/api/runs/*', async route => route.fulfill({json:runs[0]}));
  await page.goto('/');
  await page.getByText('Saved scenarios & run history',{exact:true}).click();
  await expect(page.getByRole('button',{name:'Save current scenario'})).toBeEnabled();
  await page.getByRole('button',{name:'Save current scenario'}).click();
  await expect(page.getByRole('button',{name:'Plan and save run'})).toBeEnabled();
  await page.getByRole('button',{name:'Plan and save run'}).click();await entered;
  await expect(page.getByRole('button',{name:'Run comparison',exact:true})).toBeDisabled();
  await expect(page.getByRole('button',{name:'Apply outage',exact:true})).toBeDisabled();
  await expect(page.getByRole('button',{name:'Save current scenario'})).toBeDisabled();
  finishRun();
  await expect(page.getByText('Run saved with status: Heuristic · feasible.',{exact:true})).toBeVisible();
  await page.getByRole('combobox',{name:'Last shift',exact:true}).selectOption('10');
  await page.getByRole('button',{name:'Apply outage',exact:true}).click();
  await page.getByRole('button',{name:'Open run',exact:true}).click();
  await expect(page.getByText('Opened saved run with status: Heuristic · feasible.',{exact:true})).toBeVisible();
  expect((await download(page)).result).toEqual(runs[0].result);
  await page.getByRole('button',{name:'Open scenario',exact:true}).click();
  await expect(page.getByText('Opened saved scenario:',{exact:false})).toBeVisible();
  expect(scenarios[0].scenario).toEqual(base);
});
