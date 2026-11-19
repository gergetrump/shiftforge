import type { Scenario } from './types';

// JSON object key order does not change an input snapshot. Array order is
// retained: requests and responses use the same validated scenario tables.
function stable(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stable);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => [key, stable(item)]));
  }
  return value;
}

export function sameInput(a: Scenario, b: Scenario): boolean {
  return JSON.stringify(stable(a)) === JSON.stringify(stable(b));
}
