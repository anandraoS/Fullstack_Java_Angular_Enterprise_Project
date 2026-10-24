import Button from '@mui/material/Button';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import { useState } from 'react';
import { DataTable, type Column } from '../../components/DataTable';
import { PageHeader } from '../../components/PageHeader';
import { normalizeUnknown } from '../../core/apiError';
import { shortId } from '../../core/format';
import { useListQuery } from '../../hooks/listQuery';
import { useAppDispatch, useAppSelector } from '../../hooks/redux';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import type { OmsNotification } from '../../models';
import { useListNotificationsQuery, useRetryNotificationMutation } from '../../store/api';
import { selectIsStaff } from '../../store/authSlice';
import { toast } from '../../store/uiSlice';

const STATUSES = ['PENDING', 'SENT', 'FAILED', 'RETRYING'];

const columns: Column<OmsNotification>[] = [
  { key: 'created', header: 'Created', value: (n) => n.createdAt, type: 'date', sortField: 'createdAt' },
  { key: 'order', header: 'Order', value: (n) => shortId(n.orderId), type: 'link', link: (n) => `/orders/${n.orderId ?? ''}` },
  { key: 'channel', header: 'Channel', value: (n) => n.channel },
  { key: 'subject', header: 'Subject', value: (n) => n.subject },
  { key: 'status', header: 'Status', value: (n) => n.status, type: 'status' },
  { key: 'attempts', header: 'Attempts', value: (n) => n.attempts, type: 'number' },
  { key: 'error', header: 'Last error', value: (n) => n.lastError ?? '' },
];

export default function Notifications() {
  const dispatch = useAppDispatch();
  const staff = useAppSelector(selectIsStaff);
  const [status, setStatus] = useState('');
  const [orderId, setOrderId] = useState('');
  const filters = useDebouncedValue({ status, orderId: orderId.trim() }, 300);
  const list = useListQuery(filters, 20, 'createdAt,desc');
  const { query } = list;
  const res = useListNotificationsQuery({ ...query.filters, page: query.page, size: query.size, sort: query.sort });
  const [retry] = useRetryNotificationMutation();

  const doRetry = async (n: OmsNotification) => {
    try {
      await retry(n.id).unwrap();
      dispatch(toast.success('Retry requested.'));
    } catch (e) {
      dispatch(toast.error(normalizeUnknown(e).userMessage));
    }
  };

  return (
    <>
      <PageHeader title="Notifications" subtitle={staff ? 'All simulated notifications' : 'Notifications sent to you (simulated)'} />
      <form className="filters" role="search" aria-label="Filter notifications" onSubmit={(e) => e.preventDefault()}>
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
        caption="Notifications"
        columns={columns}
        rows={res.data?.content ?? []}
        rowKey={(n) => n.id}
        loading={res.isFetching}
        error={res.error ?? null}
        total={res.data?.totalElements ?? 0}
        page={query.page}
        size={query.size}
        sort={query.sort}
        emptyTitle="No notifications"
        onPageChange={list.setPage}
        onSortChange={list.toggleSort}
        onRetry={() => void res.refetch()}
        actions={
          staff
            ? (n) =>
                n.status === 'FAILED' ? (
                  <Button variant="outlined" size="small" onClick={() => void doRetry(n)} aria-label={`Retry notification ${shortId(n.id)}`}>
                    Retry
                  </Button>
                ) : null
            : undefined
        }
      />
    </>
  );
}
