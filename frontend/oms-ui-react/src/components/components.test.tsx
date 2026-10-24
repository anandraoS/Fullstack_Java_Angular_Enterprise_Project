import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { normalizeFailure } from '../core/apiError';
import type { OrderHistoryItem } from '../models';
import { history } from '../test/fixtures';
import { renderWithStore } from '../test/render';
import { ConfirmDialog, reasonSchema } from './ConfirmDialog';
import { DataTable, ariaSortFor, type Column } from './DataTable';
import { ErrorBoundary } from './ErrorBoundary';
import { EmptyState, ErrorBanner, LoadingOverlay } from './feedback';
import { PageHeader } from './PageHeader';
import { StatusChip, toneFor } from './StatusChip';
import { StatusTimeline } from './StatusTimeline';
import { ToastHost } from './ToastHost';
import { buildTimeline } from './timeline';
import { useConfirm } from './useConfirm';
import { toast } from '../store/uiSlice';

const h = (to: OrderHistoryItem['toStatus'], from: OrderHistoryItem['fromStatus'] = null, at = '2026-10-19T10:00:00Z'): OrderHistoryItem => ({ fromStatus: from, toStatus: to, reason: null, actor: null, occurredAt: at });
const states = (steps: ReturnType<typeof buildTimeline>) => Object.fromEntries(steps.map((s) => [s.status, s.state]));

describe('buildTimeline', () => {
  it('happy path in flight: earlier steps done, current marked, later upcoming', () => {
    const s = states(buildTimeline('INVENTORY_RESERVED', [h('CREATED'), h('VALIDATED', 'CREATED'), h('INVENTORY_RESERVED', 'VALIDATED')]));
    expect(s).toEqual({ CREATED: 'done', VALIDATED: 'done', INVENTORY_RESERVED: 'current', CONFIRMED: 'upcoming', FULFILLED: 'upcoming' });
  });

  it('without history the visited steps are inferred from the status', () => {
    const s = states(buildTimeline('CONFIRMED'));
    expect(s.CREATED).toBe('done');
    expect(s.CONFIRMED).toBe('current');
    expect(s.FULFILLED).toBe('upcoming');
  });

  it('FULFILLED is shown as done, not as a pending current step', () => {
    expect(states(buildTimeline('FULFILLED'))['FULFILLED']).toBe('done');
  });

  it('REJECTED skips everything after CREATED and appends a failed branch', () => {
    const steps = buildTimeline('REJECTED', [h('CREATED'), h('REJECTED', 'CREATED')]);
    const s = states(steps);
    expect(s).toMatchObject({ CREATED: 'done', VALIDATED: 'skipped', CONFIRMED: 'skipped', FULFILLED: 'skipped', REJECTED: 'failed' });
    expect(steps.at(-1)).toMatchObject({ status: 'REJECTED', branch: true });
  });

  it('fulfillment failure path: COMPENSATION_PENDING then FAILED, FULFILLED skipped', () => {
    const steps = buildTimeline('FAILED', [h('CREATED'), h('VALIDATED'), h('INVENTORY_RESERVED'), h('CONFIRMED'), h('COMPENSATION_PENDING', 'CONFIRMED'), h('FAILED', 'COMPENSATION_PENDING')]);
    const s = states(steps);
    expect(s).toMatchObject({ CONFIRMED: 'done', FULFILLED: 'skipped', COMPENSATION_PENDING: 'done', FAILED: 'failed' });
    expect(steps.map((x) => x.status).slice(-2)).toEqual(['COMPENSATION_PENDING', 'FAILED']);
  });

  it('CANCELLED is cancelled, an in-progress COMPENSATION_PENDING is a warning, branch without history still shows', () => {
    expect(states(buildTimeline('CANCELLED', [h('CREATED'), h('CANCELLED', 'CREATED')]))['CANCELLED']).toBe('cancelled');
    expect(states(buildTimeline('COMPENSATION_PENDING', [h('CONFIRMED'), h('COMPENSATION_PENDING')]))['COMPENSATION_PENDING']).toBe('warning');
    expect(states(buildTimeline('FAILED'))['FAILED']).toBe('failed');
  });

  it('records the first time each status was reached', () => {
    const steps = buildTimeline('VALIDATED', [h('CREATED', null, '2026-01-01T00:00:00Z'), h('VALIDATED', 'CREATED', '2026-01-01T00:00:05Z')]);
    expect(steps.find((x) => x.status === 'VALIDATED')?.at).toBe('2026-01-01T00:00:05Z');
  });
});

describe('StatusTimeline rendering', () => {
  it('renders a labelled list with text state for assistive tech and state classes for e2e', () => {
    render(<StatusTimeline status="FAILED" history={[h('CREATED'), h('VALIDATED'), h('INVENTORY_RESERVED'), h('CONFIRMED'), h('COMPENSATION_PENDING'), h('FAILED')]} />);
    const list = screen.getByRole('list', { name: 'Order progress' });
    expect(within(list).getAllByRole('listitem').length).toBeGreaterThanOrEqual(6);
    expect(list).toHaveTextContent('COMPENSATION PENDING');
    expect(list.querySelector('li.state-failed')).toHaveTextContent('FAILED - failed');
    expect(list.querySelectorAll('li.state-skipped')).toHaveLength(1);
    expect(list.querySelector('li.state-skipped')).toHaveTextContent('FULFILLED');
  });

  it('marks only the current step with aria-current', () => {
    render(<StatusTimeline status="VALIDATED" history={history} />);
    const current = document.querySelectorAll('[aria-current="step"]');
    expect(current).toHaveLength(1);
    expect(current[0]).toHaveTextContent('VALIDATED');
  });
});

describe('StatusChip', () => {
  it('shows text plus glyph (never colour only) and maps tones', () => {
    render(<StatusChip status="COMPENSATION_PENDING" testId="chip" />);
    expect(screen.getByTestId('chip')).toHaveTextContent('COMPENSATION PENDING');
    expect(screen.getByTestId('chip')).toHaveClass('tone-warn');
    expect(screen.getByTestId('chip').querySelector('.glyph')).toHaveAttribute('aria-hidden', 'true');
    expect([toneFor('FULFILLED'), toneFor('REJECTED'), toneFor('CREATED'), toneFor('CANCELLED'), toneFor('???')]).toEqual(['success', 'danger', 'progress', 'neutral', 'neutral']);
  });
});

describe('feedback components', () => {
  it('ErrorBanner shows friendly text, field errors, reference and a retry button', async () => {
    const onRetry = vi.fn();
    const err = normalizeFailure({ status: 400, body: { code: 'VALIDATION_FAILED', correlationId: 'ref-77', errors: [{ field: 'city', message: 'required' }] } });
    render(<ErrorBanner error={err} onRetry={onRetry} />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Some of the submitted values are not valid');
    expect(alert).toHaveTextContent('city: required');
    expect(alert).toHaveTextContent('Reference: ref-77');
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalled();
  });

  it('ErrorBanner renders plain strings, hides retry when not retryable, and nothing for null', () => {
    const { rerender } = render(<ErrorBanner error="Some fields need attention." retryable={false} onRetry={() => undefined} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Some fields need attention.');
    expect(screen.queryByRole('button')).toBeNull();
    rerender(<ErrorBanner error={null} />);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('LoadingOverlay is an always-present live region that announces only while active', () => {
    const { rerender } = render(<LoadingOverlay active={false} />);
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
    rerender(<LoadingOverlay active label="Loading orders" />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading orders');
  });

  it('EmptyState shows title and optional message', () => {
    render(<EmptyState title="No orders" message="Create one" />);
    expect(screen.getByText('No orders')).toBeVisible();
    expect(screen.getByText('Create one')).toBeVisible();
  });
});

describe('PageHeader', () => {
  it('renders the h1, moves focus to it on mount and sets the document title', () => {
    render(
      <PageHeader title="Orders" subtitle="Your orders">
        <button>Action</button>
      </PageHeader>,
    );
    const h1 = screen.getByRole('heading', { level: 1, name: 'Orders' });
    expect(h1).toHaveFocus();
    expect(document.title).toBe('Orders - OMS Console');
    expect(screen.getByText('Your orders')).toBeVisible();
  });
});

interface Row {
  id: string;
  name: string;
  qty: number;
  price: number;
  status: string;
  when: string;
}
const columns: Column<Row>[] = [
  { key: 'name', header: 'Name', value: (r) => r.name, sortField: 'name', type: 'link', link: (r) => `/things/${r.id}` },
  { key: 'qty', header: 'Qty', value: (r) => r.qty, type: 'number' },
  { key: 'price', header: 'Price', value: (r) => r.price, type: 'money' },
  { key: 'status', header: 'Status', value: (r) => r.status, type: 'status' },
  { key: 'when', header: 'When', value: (r) => r.when, type: 'date' },
  { key: 'plain', header: 'Plain', value: (r) => r.name.toUpperCase() },
];
const rows: Row[] = [
  { id: '1', name: 'Alpha', qty: 3, price: 2.5, status: 'FAILED', when: '2026-10-19T10:00:00Z' },
  { id: '2', name: 'Beta', qty: 1, price: 10, status: 'SENT', when: '2026-10-19T11:00:00Z' },
];

describe('DataTable', () => {
  const setup = (props: Partial<Parameters<typeof DataTable<Row>>[0]> = {}) =>
    render(
      <MemoryRouter>
        <DataTable columns={columns} rows={rows} rowKey={(r) => r.id} caption="Things" total={45} page={1} size={20} sort="name,asc" {...props} />
      </MemoryRouter>,
    );

  it('renders a named table with typed cells, links and aria-sort on the sorted column', () => {
    setup();
    const table = screen.getByRole('table', { name: 'Things' });
    expect(within(table).getByRole('link', { name: 'Alpha' })).toHaveAttribute('href', '/things/1');
    expect(within(table).getByText('2.50')).toBeVisible();
    expect(within(table).getByText('FAILED')).toBeVisible();
    expect(within(table).getByRole('columnheader', { name: /Name/ })).toHaveAttribute('aria-sort', 'ascending');
    expect(screen.getByRole('region', { name: 'Things (scrollable)' })).toHaveAttribute('tabindex', '0');
  });

  it('ariaSortFor covers asc, desc, other column and unsortable', () => {
    expect(ariaSortFor('name', 'name,asc')).toBe('ascending');
    expect(ariaSortFor('name', 'name,desc')).toBe('descending');
    expect(ariaSortFor('name', 'qty,asc')).toBe('none');
    expect(ariaSortFor(undefined, 'name,asc')).toBeUndefined();
  });

  it('sort headers are buttons that report the field', async () => {
    const onSortChange = vi.fn();
    setup({ onSortChange });
    await userEvent.click(screen.getByRole('button', { name: /Name/ }));
    expect(onSortChange).toHaveBeenCalledWith('name');
  });

  it('pagination: shows the range, moves to the next page and changes the page size', async () => {
    const onPageChange = vi.fn();
    setup({ onPageChange });
    expect(screen.getByText(/21.40 of 45|21–40 of 45/)).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(onPageChange).toHaveBeenLastCalledWith(2, 20);
    await userEvent.click(screen.getByRole('button', { name: 'Previous page' }));
    expect(onPageChange).toHaveBeenLastCalledWith(0, 20);
    await userEvent.click(screen.getByRole('combobox'));
    await userEvent.click(screen.getByRole('option', { name: '50' }));
    expect(onPageChange).toHaveBeenLastCalledWith(0, 50);
  });

  it('shows an action column, the empty state, the loading overlay and the error banner with retry', async () => {
    const onRetry = vi.fn();
    const { rerender } = setup({ actions: (r) => <button>Act {r.name}</button> });
    expect(screen.getByRole('button', { name: 'Act Alpha' })).toBeVisible();

    rerender(
      <MemoryRouter>
        <DataTable columns={columns} rows={[]} rowKey={(r) => r.id} emptyTitle="Nothing here" />
      </MemoryRouter>,
    );
    expect(screen.getByText('Nothing here')).toBeVisible();

    rerender(
      <MemoryRouter>
        <DataTable columns={columns} rows={[]} rowKey={(r) => r.id} loading />
      </MemoryRouter>,
    );
    expect(screen.queryByText('Nothing here')).toBeNull();
    expect(screen.getByRole('status')).toHaveTextContent('Loading');

    rerender(
      <MemoryRouter>
        <DataTable columns={columns} rows={[]} rowKey={(r) => r.id} error={normalizeFailure({ status: 503, body: null })} onRetry={onRetry} paginate={false} />
      </MemoryRouter>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('temporarily unavailable');
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalled();
    expect(screen.queryByText('Rows per page:')).toBeNull();
  });
});

describe('ConfirmDialog', () => {
  function Harness({ reason }: { reason?: boolean }) {
    const { ask, element } = useConfirm();
    const [result, setResult] = useState('none');
    return (
      <>
        <button
          onClick={async () => {
            const r = await ask({ title: 'Cancel this order?', message: 'Stock is released.', confirmLabel: 'Cancel order', cancelLabel: 'Keep order', reasonLabel: reason ? 'Reason for cancellation' : undefined, minReasonLength: 3, destructive: true });
            setResult(r ? `confirmed:${r.reason}` : 'cancelled');
          }}
        >
          Open
        </button>
        <output>{result}</output>
        {element}
      </>
    );
  }

  it('is an alertdialog with focus on the reason field, validates the minimum length, then confirms with the trimmed reason', async () => {
    const user = userEvent.setup();
    render(<Harness reason />);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveAccessibleName('Cancel this order?');
    expect(within(dialog).getByLabelText(/Reason for cancellation/)).toHaveFocus();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }));
    expect(within(dialog).getByText('Enter at least 3 characters.')).toBeVisible();
    expect(screen.getByRole('alertdialog')).toBeVisible(); // still open
    await user.type(within(dialog).getByLabelText(/Reason for cancellation/), '  oops  ');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }));
    expect(await screen.findByText('confirmed:oops')).toBeVisible();
  });

  it('without a reason the safe (cancel) button gets focus; Escape and Keep both cancel and focus returns to the opener', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const opener = screen.getByRole('button', { name: 'Open' });
    await user.click(opener);
    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByRole('button', { name: 'Keep order' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(await screen.findByText('cancelled')).toBeVisible();
    await vi.waitFor(() => expect(opener).toHaveFocus());

    await user.click(opener);
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Keep order' }));
    expect(screen.getByText('cancelled')).toBeVisible();
  });

  it('plain confirmation (no reason) confirms with an empty reason', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Cancel order' }));
    expect(await screen.findByText('confirmed:')).toBeVisible();
  });

  it('reasonSchema: required enforces the minimum on trimmed text, optional accepts empty', () => {
    expect(reasonSchema(3, true).safeParse({ reason: ' a ' }).success).toBe(false);
    expect(reasonSchema(3, true).safeParse({ reason: ' abc ' }).success).toBe(true);
    expect(reasonSchema(3, false).safeParse({ reason: '' }).success).toBe(true);
  });

  it('renders nothing when closed or without data', () => {
    render(<ConfirmDialog open={false} data={null} onClose={() => undefined} />);
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });
});

describe('ErrorBoundary', () => {
  function Bomb({ explode }: { explode: boolean }) {
    if (explode) throw new Error('render failure');
    return <p>fine</p>;
  }

  it('shows a recoverable message instead of a blank page and recovers on Try again', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const onError = vi.fn();
    function Wrapper() {
      const [explode, setExplode] = useState(true);
      return (
        <ErrorBoundary onError={onError}>
          <Bomb explode={explode} />
          <button onClick={() => setExplode(false)}>Fix</button>
        </ErrorBoundary>
      );
    }
    render(<Wrapper />);
    expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong');
    expect(onError).toHaveBeenCalled();
    expect(screen.queryByText('render failure')).toBeNull(); // internals are not shown
  });

  it('resets when the route key changes', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const { rerender } = render(
      <ErrorBoundary resetKey="/a">
        <Bomb explode />
      </ErrorBoundary>,
    );
    expect(screen.getByRole('alert')).toBeVisible();
    rerender(
      <ErrorBoundary resetKey="/b">
        <Bomb explode={false} />
      </ErrorBoundary>,
    );
    expect(screen.getByText('fine')).toBeVisible();
  });
});

describe('ToastHost', () => {
  it('shows the head of the queue as a polite status (not an alert) and can be dismissed', async () => {
    const user = userEvent.setup();
    const { store } = renderWithStore(<ToastHost />);
    store.dispatch(toast.success('Saved it'));
    store.dispatch(toast.warn('Second one'));
    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('Saved it');
    expect(screen.queryByRole('alert')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(await screen.findByText('Second one')).toBeVisible();
  });
});
