import Button from '@mui/material/Button';
import { Link } from 'react-router-dom';
import { ErrorBanner, LoadingOverlay } from '../../components/feedback';
import { PageHeader } from '../../components/PageHeader';
import { StatusChip } from '../../components/StatusChip';
import { fmtDate, money, shortId } from '../../core/format';
import { useAppSelector } from '../../hooks/redux';
import type { Order, OrderStatus } from '../../models';
import { useFailedOrdersQuery, useGetHealthQuery, useListInventoryQuery, useListOrdersQuery, useListProductsQuery } from '../../store/api';
import { selectIsStaff, selectUsername } from '../../store/authSlice';

export type StatusCounts = Partial<Record<OrderStatus, number>>;

/** Pure: histogram of orders by status. */
export function countByStatus(orders: readonly Order[]): StatusCounts {
  const out: StatusCounts = {};
  for (const o of orders) out[o.status] = (out[o.status] ?? 0) + 1;
  return out;
}

export function inFlight(c: StatusCounts): number {
  return (c.CREATED ?? 0) + (c.VALIDATED ?? 0) + (c.INVENTORY_RESERVED ?? 0) + (c.CONFIRMED ?? 0) + (c.COMPENSATION_PENDING ?? 0);
}

const fmt = (n: number | null | undefined): string => (n === null || n === undefined ? 'n/a' : String(n));

/**
 * KPI screen: each source is its own RTK Query subscription, so a failing source only blanks its own tile (never the
 * whole page). Staff-only sources are skipped for customers.
 */
export default function Dashboard() {
  const staff = useAppSelector(selectIsStaff);
  const username = useAppSelector(selectUsername);
  const orders = useListOrdersQuery({ page: 0, size: 100, sort: 'createdAt,desc' });
  const products = useListProductsQuery({ page: 0, size: 1, inStock: true });
  const low = useListInventoryQuery({ page: 0, size: 1, lowStock: true }, { skip: !staff });
  const failed = useFailedOrdersQuery({ page: 0, size: 1 }, { skip: !staff });
  const health = useGetHealthQuery(undefined, { skip: !staff });

  const loading = orders.isFetching || products.isFetching || low.isFetching || failed.isFetching || health.isFetching;
  const refresh = () => {
    void orders.refetch();
    void products.refetch();
    if (staff) {
      void low.refetch();
      void failed.refetch();
      void health.refetch();
    }
  };

  const content = orders.data?.content ?? [];
  const counts = countByStatus(content);
  const recent = content.slice(0, 5);
  const ready = !orders.isLoading;

  return (
    <>
      <PageHeader title="Dashboard" subtitle={`Signed in as ${username}`}>
        <Button variant="contained" component={Link} to="/orders/new">
          Create order
        </Button>
        <Button variant="outlined" onClick={refresh}>
          Refresh
        </Button>
      </PageHeader>

      {orders.isError && <ErrorBanner error="The dashboard figures could not be loaded." onRetry={refresh} />}

      <div className="rel" aria-busy={loading}>
        <LoadingOverlay active={loading} />
        {ready && (
          <>
            <section className="grid kpis" aria-label="Key figures">
              <div className="card kpi">
                <div className="value" data-testid="kpi-total">
                  {fmt(orders.data?.totalElements)}
                </div>
                <div className="label">Orders</div>
              </div>
              <div className="card kpi">
                <div className="value">{fmt(counts.FULFILLED ?? 0)}</div>
                <div className="label">Fulfilled (latest 100)</div>
              </div>
              <div className="card kpi">
                <div className="value">{fmt(inFlight(counts))}</div>
                <div className="label">In flight (latest 100)</div>
              </div>
              <div className="card kpi">
                <div className="value">{fmt((counts.REJECTED ?? 0) + (counts.CANCELLED ?? 0) + (counts.FAILED ?? 0))}</div>
                <div className="label">Rejected, cancelled or failed (latest 100)</div>
              </div>
              <div className="card kpi">
                <div className="value">{fmt(products.data?.totalElements)}</div>
                <div className="label">Products in stock</div>
              </div>
              {staff && (
                <>
                  <div className="card kpi">
                    <div className="value" data-testid="kpi-low">
                      {fmt(low.data?.totalElements)}
                    </div>
                    <div className="label">Low-stock products</div>
                  </div>
                  <div className="card kpi">
                    <div className="value" data-testid="kpi-failed">
                      {fmt(failed.data?.totalElements)}
                    </div>
                    <div className="label">Orders needing attention</div>
                  </div>
                  <div className="card kpi">
                    <div className="value">{health.data ? <StatusChip status={health.data.status} /> : <span className="muted">n/a</span>}</div>
                    <div className="label">Platform health</div>
                  </div>
                </>
              )}
            </section>

            <section className="section card" aria-labelledby="recent-h">
              <h2 id="recent-h">Recent orders</h2>
              {recent.length === 0 ? (
                <p className="muted">No orders yet.</p>
              ) : (
                <table className="plain-table">
                  <caption className="sr-only">Most recent orders</caption>
                  <thead>
                    <tr>
                      <th scope="col">Order</th>
                      <th scope="col">Status</th>
                      <th scope="col">Total</th>
                      <th scope="col">Created</th>
                    </tr>
                  </thead>
                  <tbody>
                    {recent.map((o) => (
                      <tr key={o.id}>
                        <td>
                          <Link to={`/orders/${o.id}`} className="mono">
                            {shortId(o.id)}
                          </Link>
                        </td>
                        <td>
                          <StatusChip status={o.status} />
                        </td>
                        <td>
                          {money(o.totalAmount, '-')} {o.currency}
                        </td>
                        <td>{fmtDate(o.createdAt, 'short')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
          </>
        )}
      </div>
    </>
  );
}
