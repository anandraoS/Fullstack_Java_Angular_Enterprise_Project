import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { RequestHandler } from 'msw';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { Providers, Root } from '../App';
import type { Session } from '../models';
import { createAppStore } from '../store';
import { defaultHandlers } from './fixtures';
import { server } from './server';

/** Registers overrides in front of the default GET handlers (first match wins in MSW). */
export function mockApi(...overrides: RequestHandler[]): void {
  server.use(...overrides, ...defaultHandlers());
}

/** Renders the WHOLE app (routes, guards, shell, toasts) at `route` with an isolated store. */
export function renderApp(route = '/dashboard', opts: { session?: Session | null } = {}) {
  const store = createAppStore({ preloadedAuth: { session: opts.session ?? null, logoutReason: null }, storage: null });
  const user = userEvent.setup();
  const utils = render(
    <Providers store={store}>
      <MemoryRouter initialEntries={[route]}>
        <Root />
      </MemoryRouter>
    </Providers>,
  );
  return { store, user, ...utils };
}

/** Renders any element inside the providers (component-level tests). */
export function renderWithStore(ui: ReactElement, opts: { session?: Session | null; route?: string } = {}) {
  const store = createAppStore({ preloadedAuth: { session: opts.session ?? null, logoutReason: null }, storage: null });
  const user = userEvent.setup();
  const utils = render(
    <Providers store={store}>
      <MemoryRouter initialEntries={[opts.route ?? '/']}>{ui}</MemoryRouter>
    </Providers>,
  );
  return { store, user, ...utils };
}
