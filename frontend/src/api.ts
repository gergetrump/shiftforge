import { sameInput } from './snapshot';
import type { Algorithm, Outcome, Result, Scenario } from './types';

export async function solve(scenario: Scenario, algorithm: Algorithm): Promise<Outcome> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 45_000);
  try {
    const response = await fetch('/api/solve', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ scenario, algorithm, solver_time_limit_seconds: 10 }),
      signal: controller.signal,
    });
    const body: unknown = await response.json();
    if (body && typeof body === 'object' && 'format_version' in body && 'algorithm' in body && 'input' in body) {
      const result = body as Result;
      if (result.algorithm !== algorithm || !sameInput(result.input, scenario)) {
        return { result: null, error: 'The response did not match the requested method and input. It cannot be compared.' };
      }
      return { result, error: null };
    }
    const detail = body && typeof body === 'object' && 'detail' in body ? body.detail : null;
    const message = typeof detail === 'string' ? detail : detail ? JSON.stringify(detail) : response.statusText;
    return { result: null, error: `Request failed (${response.status}): ${message}` };
  } catch (error) {
    return { result: null, error: error instanceof DOMException && error.name === 'AbortError'
      ? 'Request timed out after 45 seconds. Backend work is not cancelled and may still be busy.'
      : `Could not reach the local planner: ${error instanceof Error ? error.message : 'network error'}` };
  } finally {
    window.clearTimeout(timeout);
  }
}

export function downloadResult(result: Result, kind: string) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(result, null, 2) + '\n'], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `capacity-plan-${kind}-${result.algorithm}.json`;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
