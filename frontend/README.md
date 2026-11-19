# ShiftForge dashboard

React/TypeScript dashboard for the Java planning API. From the repository root, build/run the backend as described in `../README.md`, then:

```bash
npm ci --ignore-scripts
npm run dev
```

Vite listens on http://127.0.0.1:5173 and proxies `/api` to http://127.0.0.1:8012. `npm run build` performs TypeScript checks and a production build. For Java-only static hosting, use `java -jar target/shiftforge.jar serve --ui frontend/dist` from the repository root after building both components.

Every comparison captures the complete input and calls Gurobi followed by greedy on the same snapshot. A Gurobi license error is displayed separately; the greedy result still works. Editing inputs marks previous output stale. Downloads preserve the exact solved snapshot.

The client aborts an HTTP wait after 45 seconds; this does not cancel native backend work. The Gurobi optimization time limit is separate from total model-build/licensing/request time.

Browser regression tests replay checked historical Python results in `tests/fixtures`. They verify UI behavior, not Java native solver execution:

```bash
npx playwright install --with-deps chromium
npm run test:e2e
```
