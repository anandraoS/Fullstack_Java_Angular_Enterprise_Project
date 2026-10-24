import Button from '@mui/material/Button';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import { useState } from 'react';
import { DataTable, type Column } from '../../components/DataTable';
import { PageHeader } from '../../components/PageHeader';
import { normalizeUnknown } from '../../core/apiError';
import { shortId } from '../../core/format';
import { useListQuery } from '../../hooks/listQuery';
import { useAppDispatch } from '../../hooks/redux';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import type { Fulfillment } from '../../models';
import { useListFulfillmentsQuery, useRetryFulfillmentMutation } from '../../store/api';
import { toast } from '../../store/uiSlice';

const STATUSES = ['PENDING', 'PICKING', 'PACKED', 'SHIPPED', 'COMPLETED', 'FAILED'];

const columns: Column<Fulfillment>[] = [
  { key: 'order', header: 'Order', value: (f) => shortId(f.orderId), type: 'link', link: (f) => `/orders/${f.orderId}` },
  { key: 'status', header: 'Status', value: (f) => f.status, type: 'status' },
  { key: 'tracking', header: 'Tracking', value: (f) => f.trackingNumber ?? '' },
  { key: 'attempts', header: 'Attempts', value: (f) => f.attempts, type: 'number' },
  { key: 'failure', header: 'Failure reason', value: (f) => f.failureReason ?? '' },
  { key: 'updated', header: 'Updated', value: (f) => f.updatedAt, type: 'date', sortField: 'updatedAt' },
];

export default function FulfillmentView() {
  const dispatch = useAppDispatch();
  const [status, setStatus] = useState('');
  const [orderId, setOrderId] = useState('');
  const filters = useDebouncedValue({ status, orderId: orderId.trim() }, 300);
  const list = useListQuery(filters, 20, 'createdAt,desc');
  const { query } = list;
  const res = useListFulfillmentsQuery({ ...query.filters, page: query.page, size: query.size, sort: query.sort });
  const [retry] = useRetryFulfillmentMutation();

  const doRetry = async (f: Fulfillment) => {
    try {
      await retry(f.id).unwrap();
      dispatch(toast.success('Retry requested.'));
    } catch (e) {
      dispatch(toast.error(normalizeUnknown(e).userMessage));
    }
  };

  return (
    <>
      <PageHeader title="Fulfillment" subtitle="Simulated picking, packing and shipping." />
      <form className="filters" role="search" aria-label="Filter fulfillments" onSubmit={(e) => e.preventDefault()}>
        <TextField select label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <MenuItem value="">Any</MenuItem>
          {STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {s}
            </MenuItem>
          ))}
        </TextField>
        <TextField className="grow" label="Order ID" type="search" value={orderId} onChange={(e) => setOrderId(e.target.value)} />
      </form>
      <DataTable
        caption="Fulfillments"
        columns={columns}
        rows={res.data?.content ?? []}
        rowKey={(f) => f.id}
        loading={res.isFetching}
        error={res.error ?? null}
        total={res.data?.totalElements ?? 0}
        page={query.page}
        size={query.size}
        sort={query.sort}
        emptyTitle="No fulfillments"
        onPageChange={list.setPage}
        onSortChange={list.toggleSort}
        onRetry={() => void res.refetch()}
        actions={(f) =>
          f.status === 'FAILED' ? (
            <Button variant="outlined" size="small" onClick={() => void doRetry(f)} aria-label={`Retry fulfillment ${shortId(f.id)}`}>
              Retry
            </Button>
          ) : null
        }
      />
    </>
  );
}
