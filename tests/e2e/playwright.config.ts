import { defineConfig } from '@playwright/test';
import * as crypto from 'node:crypto';
import * as path from 'node:path';

/**
 * Modes
 *  - mock (default): starts mock-backend/server.js which serves the built UI (frontend/oms-ui/dist) AND the API
 *    on http://localhost:4300. Build the UI first:  cd frontend/oms-ui && npx ng build
 *  - E2E_UI=angular (default) | react selects WHICH front end the same specs run against. For the mock this picks the
 *    dist folder served by mock-backend (Angular: frontend/oms-ui/dist/oms-ui/browser, React: frontend/oms-ui-react/dist;
 *    override with UI_DIR). For live runs E2E_BASE_URL points at the matching UI (Angular :4200, React :4201).
 *  - live: set E2E_BASE_URL to the UI origin (ng serve http://localhost:4200 with the /api proxy, or the nginx image)
 *    and E2E_DEMO_PASSWORD to the password printed by scripts/run-local.sh. Optionally E2E_API_URL to call the
 *    gateway directly (default: same origin as the UI, through its /api proxy).
 */
const ui = (process.env['E2E_UI'] ?? 'angular').toLowerCase();
if (ui !== 'angular' && ui !== 'react') throw new Error(`E2E_UI must be 'angular' or 'react' (got '${ui}')`);
const DEFAULT_UI_DIR =
  ui === 'react'
    ? path.resolve(__dirname, '../../frontend/oms-ui-react/dist')
    : path.resolve(__dirname, '../../frontend/oms-ui/dist/oms-ui/browser');
const live = !!process.env['E2E_BASE_URL'];
const mockPort = process.env['E2E_MOCK_PORT'] ?? '4300';
const baseURL = process.env['E2E_BASE_URL'] ?? `http://localhost:${mockPort}`;

if (!live) {
  // Random per run, inherited by workers. Never a fixed password.
  process.env['E2E_DEMO_PASSWORD'] ??= 'mock-' + crypto.randomBytes(9).toString('hex');
} else if (!process.env['E2E_DEMO_PASSWORD']) {
  throw new Error('E2E_DEMO_PASSWORD is required when E2E_BASE_URL is set (see scripts/run-local.sh output).');
}
process.env['E2E_API_URL'] ??= baseURL;

export default defineConfig({
  testDir: './specs',
  timeout: 90_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1, // specs share backend state (orders created by one step are inspected by the next)
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: `playwright-report-${ui}` }]],
  use: {
    baseURL,
    channel: 'chrome', // system Chrome: no browser download needed
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: live
    ? undefined
    : {
        command: 'node mock-backend/server.js',
        cwd: __dirname,
        url: `http://localhost:${mockPort}/__mock/health`,
        reuseExistingServer: false,
        timeout: 30_000,
        env: {
          PORT: mockPort,
          MOCK_STEP_MS: process.env['MOCK_STEP_MS'] ?? '700',
          OMS_DEMO_PASSWORD: process.env['E2E_DEMO_PASSWORD'] as string,
          UI_DIR: process.env['UI_DIR'] ?? DEFAULT_UI_DIR,
        },
      },
});
