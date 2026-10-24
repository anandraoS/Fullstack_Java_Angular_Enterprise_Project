import Button from '@mui/material/Button';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { DataTable, type Column } from '../../components/DataTable';
import { PageHeader } from '../../components/PageHeader';
import { shortId } from '../../core/format';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { useListQuery } from '../../hooks/listQuery';
import { useAppSelector } from '../../hooks/redux';
import { ORDER_STATUSES, type Order } from '../../models';
import { useListOrdersQuery } from '../../store/api';
import { selectIsStaff } from '../../store/authSlice';

/** Pure: <input type=date> value (yyyy-mm-dd) to an ISO UTC bound. */
export function dateBound(day: string, end: boolean): string {
  return day ? `${day}T${end ? '23:59:59' : '00:00:00'}Z` : '';
}

/** Pure: the end date must not be before the start date. */
export function dateRangeInvalid(from: string, to: string): boolean {
  return !!from && !!to && to < from;
}

const columns: Column<Order>[] = [
  { key: 'id', header: 'Order', value: (o) => shortId(o.id), type: 'link', link: (o) => `/orders/${o.id}` },
  { key: 'status', header: 'Status', value: (o) => o.status, type: 'status', sortField: 'status' },
  { key: 'items', header: 'Items', value: (o) => o.items.length, type: 'number' },
  { key: 'total', header: 'Total (USD)', value: (o) => o.totalAmount, type: 'money', sortField: 'totalAmount' },
  { key: 'created', header: 'Created', value: (o) => o.createdAt, type: 'date', sortField: 'createdAt' },
  { key: 'updated', header: 'Updated', value: (o) => o.updatedAt, type: 'date', sortField: 'updatedAt' },
];

export default function OrderList() {
  const staff = useAppSelector(selectIsStaff);
  const [status, setStatus] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [q, setQ] = useState('');
  const invalidRange = dateRangeInvalid(from, to);
  const debounced = useDebouncedValue({ status, from, to, q: q.trim() }, 300);
  // an invalid range is never sent to the server
  const filters = dateRangeInvalid(debounced.from, debounced.to)
    ? { status: debounced.status, from: '', to: '', q: debounced.q }
    : { status: debounced.status, from: dateBound(debounced.from, false), to: dateBound(debounced.to, true), q: debounced.q };
  const list = useListQuery(filters, 20, 'createdAt,desc');
  const { query } = list;
  const res = useListOrdersQuery({ ...query.filters, page: query.page, size: query.size, sort: query.sort });

  return (
    <>
      <PageHeader title="Orders" subtitle={staff ? 'All customer orders' : 'Your orders'}>
        <Button variant="contained" component={Link} to="/orders/new">
          Create order
        </Button>
      </PageHeader>

      <form className="filters" role="search" aria-label="Filter orders" onSubmit={(e) => e.preventDefault()}>
        <TextField select label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <MenuItem value="">Any</MenuItem>
          {ORDER_STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {s}
            </MenuItem>
          ))}
        </TextField>
        <TextField label="Created from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <TextField
          label="Created to"
          type="date"
          value={to}
          onChange={(e) => setTo(e.target.value)}
          error={invalidRange}
          helperText={invalidRange ? 'The end date is before the start date.' : undefined}
          slotProps={{ inputLabel: { shrink: true } }}
        />
        <TextField className="grow" label="Search" type="search" value={q} onChange={(e) => setQ(e.target.value)} />
        <div className="btns">
          <Button
            onClick={() => {
              setStatus('');
              setFrom('');
              setTo('');
              setQ('');
            }}
          >
            Clear filters
          </Button>
        </div>
      </form>

      <DataTable
        caption="Orders"
        columns={columns}
        rows={res.data?.content ?? []}
        rowKey={(o) => o.id}
        loading={res.isFetching}
        error={res.error ?? null}
        total={res.data?.totalElements ?? 0}
        page={query.page}
        size={query.size}
        sort={query.sort}
        emptyTitle="No orders found"
        emptyMessage="Create an order, or adjust the filters."
        onPageChange={list.setPage}
        onSortChange={list.toggleSort}
        onRetry={() => void res.refetch()}
      />
    </>
  );
}
