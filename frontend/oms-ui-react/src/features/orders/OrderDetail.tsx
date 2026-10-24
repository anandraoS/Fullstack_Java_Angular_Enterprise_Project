import Button from '@mui/material/Button';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ErrorBanner, LoadingOverlay } from '../../components/feedback';
import { PageHeader } from '../../components/PageHeader';
import { StatusChip } from '../../components/StatusChip';
import { StatusTimeline } from '../../components/StatusTimeline';
import { useConfirm } from '../../components/useConfirm';
import { normalizeUnknown, type ApiError } from '../../core/apiError';
import { fmtDate, humanize, money, shortId } from '../../core/format';
import { useOrderPolling } from '../../hooks/polling';
import { useAppDispatch, useAppSelector } from '../../hooks/redux';
import { CANCELLABLE_STATUSES, type Order } from '../../models';
import { useCancelOrderMutation } from '../../store/api';
import { selectIsStaff } from '../../store/authSlice';
import { toast } from '../../store/uiSlice';

export function canCancel(order: Order | null | undefined): boolean {
  return !!order && CANCELLABLE_STATUSES.includes(order.status);
}

export default function OrderDetail() {
  const { id = '' } = useParams();
  const dispatch = useAppDispatch();
  const staff = useAppSelector(selectIsStaff);
  const poll = useOrderPolling(id, staff);
  const [cancelOrder, { isLoading: cancelling }] = useCancelOrderMutation();
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const { ask, element } = useConfirm();

  const o = poll.snapshot?.order ?? null;
  const history = poll.snapshot?.history ?? [];
  const saga = poll.snapshot?.saga ?? null;

  const cancel = async () => {
    const res = await ask({
      title: 'Cancel this order?',
      message: 'Reserved stock will be released. This cannot be undone.',
      confirmLabel: 'Cancel order',
      cancelLabel: 'Keep order',
      reasonLabel: 'Reason for cancellation',
      minReasonLength: 3,
      destructive: true,
    });
    if (!res) return;
    setActionError(null);
    try {
      await cancelOrder({ id, reason: res.reason }).unwrap();
      dispatch(toast.success('Cancellation requested.'));
    } catch (e) {
      setActionError(normalizeUnknown(e));
    }
    poll.refresh();
  };

  return (
    <>
      <PageHeader title={`Order ${shortId(id)}`} subtitle="Status refreshes automatically until the order reaches a final state.">
        <Button component={Link} to="/orders">
          Back to orders
        </Button>
        {poll.polling ? (
          <Button variant="outlined" onClick={poll.stopPolling}>
            Stop auto-refresh
          </Button>
        ) : (
          <Button variant="outlined" onClick={poll.refresh}>
            Refresh
          </Button>
        )}
        {canCancel(o) && (
          <Button variant="contained" color="error" onClick={() => void cancel()} disabled={cancelling} data-testid="cancel-order">
            Cancel order
          </Button>
        )}
      </PageHeader>

      <ErrorBanner error={poll.error} onRetry={poll.refresh} />
      <ErrorBanner error={actionError} retryable={false} />

      <div className="rel" aria-busy={poll.loading}>
        <LoadingOverlay active={poll.loading && !o} />
        {o && (
          <>
            <div className="sr-only" role="status" aria-live="polite" data-testid="status-announcer">
              Order status: {humanize(o.status)}
            </div>

            <section className="card" aria-labelledby="progress-h">
              <h2 id="progress-h" className="h2">
                Progress
              </h2>
              <div className="row">
                <StatusChip status={o.status} testId="order-status" />
                {o.statusReason && (
                  <span className="muted" data-testid="status-reason">
                    {o.statusReason}
                  </span>
                )}
                {poll.polling && (
                  <span className="muted" data-testid="polling-indicator">
                    Auto-refreshing...
                  </span>
                )}
              </div>
              <StatusTimeline status={o.status} history={history} />
            </section>

            <section className="section card" aria-labelledby="sum-h">
              <h2 id="sum-h" className="h2">
                Summary
              </h2>
              <dl className="kv">
                <dt>Order ID</dt>
                <dd className="mono" data-testid="order-id">
                  {o.id}
                </dd>
                <dt>Customer</dt>
                <dd className="mono">{o.customerId}</dd>
                <dt>Total</dt>
                <dd>
                  {money(o.totalAmount, '-')} {o.currency}
                </dd>
                <dt>Created</dt>
                <dd>{fmtDate(o.createdAt, 'medium')}</dd>
                <dt>Updated</dt>
                <dd>{fmtDate(o.updatedAt, 'medium')}</dd>
                <dt>Ship to</dt>
                <dd>
                  {o.shippingAddress.line1}, {o.shippingAddress.city} {o.shippingAddress.postalCode}, {o.shippingAddress.country}
                </dd>
                <dt>Reference</dt>
                <dd className="mono">{o.correlationId}</dd>
              </dl>
            </section>

            <section className="section card" aria-labelledby="items-h">
              <h2 id="items-h" className="h2">
                Items
              </h2>
              <div className="scroll">
                <table className="plain-table">
                  <caption className="sr-only">Order items</caption>
                  <thead>
                    <tr>
                      <th scope="col">SKU</th>
                      <th scope="col">Name</th>
                      <th scope="col" className="num">
                        Qty
                      </th>
                      <th scope="col" className="num">
                        Unit price
                      </th>
                      <th scope="col" className="num">
                        Line total
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {o.items.map((i) => (
                      <tr key={i.productId}>
                        <td>{i.sku}</td>
                        <td>{i.name}</td>
                        <td className="num">{i.quantity}</td>
                        {/* prices are null until validation priced the order and stay null if it was rejected (defect D-03) */}
                        <td className="num">{money(i.unitPrice, '-')}</td>
                        <td className="num">{money(i.lineTotal, '-')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>

            <section className="section card" aria-labelledby="hist-h">
              <h2 id="hist-h" className="h2">
                History
              </h2>
              {history.length === 0 ? (
                <p className="muted">No transitions recorded yet.</p>
              ) : (
                <div className="scroll">
                  <table className="plain-table">
                    <caption className="sr-only">Status history</caption>
                    <thead>
                      <tr>
                        <th scope="col">When</th>
                        <th scope="col">From</th>
                        <th scope="col">To</th>
                        <th scope="col">Reason</th>
                        <th scope="col">Actor</th>
                      </tr>
                    </thead>
                    <tbody>
                      {history.map((h) => (
                        <tr key={h.occurredAt + h.toStatus}>
                          <td>{fmtDate(h.occurredAt)}</td>
                          <td>{h.fromStatus ?? '-'}</td>
                          <td>
                            <StatusChip status={h.toStatus} />
                          </td>
                          <td>{h.reason ?? ''}</td>
                          <td>{h.actor ?? ''}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>

            {staff && saga && (
              <section className="section card" aria-labelledby="saga-h">
                <h2 id="saga-h" className="h2">
                  Saga (staff view)
                </h2>
                <dl className="kv">
                  <dt>State</dt>
                  <dd>{saga.state}</dd>
                  <dt>Current step</dt>
                  <dd>{saga.currentStep}</dd>
                  <dt>Attempts</dt>
                  <dd>{saga.attempts}</dd>
                  {saga.lastError && (
                    <>
                      <dt>Last error</dt>
                      <dd>{saga.lastError}</dd>
                    </>
                  )}
                </dl>
                <div className="scroll">
                  <table className="plain-table">
                    <caption className="sr-only">Saga step log</caption>
                    <thead>
                      <tr>
                        <th scope="col">Step</th>
                        <th scope="col">Status</th>
                        <th scope="col" className="num">
                          Attempt
                        </th>
                        <th scope="col">Detail</th>
                        <th scope="col">At</th>
                      </tr>
                    </thead>
                    <tbody>
                      {saga.steps.map((st) => (
                        <tr key={st.at + st.step + st.attempt}>
                          <td>{st.step}</td>
                          <td>{st.status}</td>
                          <td className="num">{st.attempt}</td>
                          <td>{st.detail ?? ''}</td>
                          <td>{fmtDate(st.at, 'time')}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            )}
          </>
        )}
      </div>
      {element}
    </>
  );
}
