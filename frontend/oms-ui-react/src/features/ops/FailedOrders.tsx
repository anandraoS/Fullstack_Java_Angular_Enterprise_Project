import Button from '@mui/material/Button';
import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { DataTable, type Column } from '../../components/DataTable';
import { ErrorBanner } from '../../components/feedback';
import { PageHeader } from '../../components/PageHeader';
import { StatusChip } from '../../components/StatusChip';
import { StatusTimeline } from '../../components/StatusTimeline';
import { useConfirm } from '../../components/useConfirm';
import { normalizeUnknown, type ApiError } from '../../core/apiError';
import { fmtDate, shortId } from '../../core/format';
import { useListQuery } from '../../hooks/listQuery';
import { useAppDispatch, useAppSelector } from '../../hooks/redux';
import type { Order, ReconciliationResult } from '../../models';
import { useFailedOrdersQuery, useGetInvestigationQuery, useReconcileMutation, useRetryOrderMutation } from '../../store/api';
import { selectIsAdmin } from '../../store/authSlice';
import { toast } from '../../store/uiSlice';

const columns: Column<Order>[] = [
  { key: 'id', header: 'Order', value: (o) => shortId(o.id), type: 'link', link: (o) => `/orders/${o.id}` },
  { key: 'status', header: 'Status', value: (o) => o.status, type: 'status' },
  { key: 'reason', header: 'Reason', value: (o) => o.statusReason ?? '' },
  { key: 'total', header: 'Total (USD)', value: (o) => o.totalAmount, type: 'money' },
  { key: 'updated', header: 'Updated', value: (o) => o.updatedAt, type: 'date' },
];

const NO_FILTERS = {};

export default function FailedOrders() {
  const dispatch = useAppDispatch();
  const admin = useAppSelector(selectIsAdmin);
  const list = useListQuery(NO_FILTERS, 20);
  const { query } = list;
  const res = useFailedOrdersQuery({ page: query.page, size: query.size });

  const [selected, setSelected] = useState<string | null>(null);
  const inv = useGetInvestigationQuery(selected ?? '', { skip: !selected });
  const [retryOrder, { isLoading: retrying }] = useRetryOrderMutation();
  const [reconcile, { isLoading: reconciling }] = useReconcileMutation();
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [recon, setRecon] = useState<ReconciliationResult | null>(null);
  const { ask, element } = useConfirm();
  const panel = useRef<HTMLElement>(null);

  const investigate = (o: Order) => {
    setSelected(o.id);
    setTimeout(() => panel.current?.focus(), 0);
  };

  const onRetry = async (o: Order) => {
    const ok = await ask({ title: 'Retry this order?', message: 'The current saga step is re-driven. The operation is idempotent.', confirmLabel: 'Retry' });
    if (!ok) return;
    setActionError(null);
    try {
      await retryOrder(o.id).unwrap(); // invalidates the order + failed list: both refetch
      dispatch(toast.success('Retry requested.'));
    } catch (e) {
      setActionError(normalizeUnknown(e));
    }
  };

  const onReconcile = async () => {
    const ok = await ask({
      title: 'Run reconciliation now?',
      message: 'Looks for inconsistent orders and reservations and repairs them with compensating commands.',
      confirmLabel: 'Run',
    });
    if (!ok) return;
    setActionError(null);
    try {
      setRecon(await reconcile().unwrap());
    } catch (e) {
      setActionError(normalizeUnknown(e));
    }
  };

  const i = inv.data;
  return (
    <>
      <PageHeader title="Failed order investigation" subtitle="Orders that were rejected, failed, or are stuck compensating.">
        {admin && (
          <Button variant="outlined" onClick={() => void onReconcile()} disabled={reconciling} data-testid="run-reconciliation">
            Run reconciliation
          </Button>
        )}
      </PageHeader>

      <ErrorBanner error={actionError} retryable={false} />

      {recon && (
        <section className="card" style={{ marginBottom: 16 }} aria-labelledby="recon-h" role="status" data-testid="recon-result">
          <h2 id="recon-h" className="h2">
            Reconciliation result
          </h2>
          <p>
            Examined <strong>{recon.examined}</strong>, repaired <strong>{recon.repaired}</strong>.
          </p>
          {recon.details.length > 0 && (
            <ul>
              {recon.details.map((d, idx) => (
                <li key={idx}>{d}</li>
              ))}
            </ul>
          )}
        </section>
      )}

      <DataTable
        caption="Failed orders"
        columns={columns}
        rows={res.data?.content ?? []}
        rowKey={(o) => o.id}
        loading={res.isFetching}
        error={res.error ?? null}
        total={res.data?.totalElements ?? 0}
        page={query.page}
        size={query.size}
        emptyTitle="No failed orders"
        emptyMessage="Everything is flowing."
        onPageChange={list.setPage}
        onRetry={() => void res.refetch()}
        actions={(o) => (
          <Button variant="outlined" size="small" onClick={() => investigate(o)} aria-label={`Investigate order ${shortId(o.id)}`}>
            Investigate
          </Button>
        )}
      />

      <section className="section card" aria-labelledby="inv-h" tabIndex={-1} ref={panel}>
        <h2 id="inv-h" className="h2">
          Investigation
        </h2>
        <ErrorBanner error={inv.error} onRetry={() => void inv.refetch()} />
        {inv.isFetching && !i && <p role="status">Loading investigation...</p>}
        {i ? (
          <>
            <div className="row">
              <strong>
                Order{' '}
                <Link to={`/orders/${i.order.id}`} className="mono">
                  {i.order.id}
                </Link>
              </strong>
              <StatusChip status={i.order.status} />
              {i.order.statusReason && <span className="muted">{i.order.statusReason}</span>}
              <Button variant="contained" onClick={() => void onRetry(i.order)} disabled={retrying} data-testid="retry-order">
                Retry current step
              </Button>
            </div>
            <StatusTimeline status={i.order.status} history={i.history} />

            {i.saga && (
              <>
                <h3 className="h3">Saga</h3>
                <p>
                  State <strong>{i.saga.state}</strong>, step <strong>{i.saga.currentStep}</strong>, attempts {i.saga.attempts}.
                  {i.saga.lastError ? ` Last error: ${i.saga.lastError}` : ''}
                </p>
                <ol style={{ margin: 0, paddingLeft: 20 }}>
                  {i.saga.steps.map((st) => (
                    <li key={st.at + st.step + st.attempt}>
                      {fmtDate(st.at, 'time')} - {st.step} - {st.status} (attempt {st.attempt}) {st.detail ?? ''}
                    </li>
                  ))}
                </ol>
              </>
            )}

            <h3 className="h3">Audit trail</h3>
            {i.audit.length === 0 ? (
              <p className="muted">No audit events found for this order.</p>
            ) : (
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                {i.audit.map((a) => (
                  <li key={a.id}>
                    {fmtDate(a.occurredAt, 'short')} - <strong>{a.action}</strong> by {a.actor ?? 'system'} {a.details ?? ''}
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : !inv.isFetching && !inv.isError ? (
          <p className="muted">Select an order above to see its saga, history and audit trail.</p>
        ) : null}
      </section>
      {element}
    </>
  );
}
