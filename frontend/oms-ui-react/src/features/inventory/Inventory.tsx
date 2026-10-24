import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import MenuItem from '@mui/material/MenuItem';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import TextField from '@mui/material/TextField';
import { useState } from 'react';
import { DataTable, type Column } from '../../components/DataTable';
import { PageHeader } from '../../components/PageHeader';
import { shortId } from '../../core/format';
import { useListQuery } from '../../hooks/listQuery';
import { useAppDispatch } from '../../hooks/redux';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import type { Product, Reservation } from '../../models';
import { useListInventoryQuery, useListReservationsQuery } from '../../store/api';
import { toast } from '../../store/uiSlice';
import { StockAdjustDialog } from './StockAdjustDialog';

export const LOW_STOCK_THRESHOLD_HINT = 'Low stock is determined by the server (available below its reorder threshold).';
const RES_STATUSES = ['RESERVED', 'COMMITTED', 'RELEASED', 'EXPIRED', 'FAILED'];

const stockColumns: Column<Product>[] = [
  { key: 'sku', header: 'SKU', value: (p) => p.sku, sortField: 'sku' },
  { key: 'name', header: 'Name', value: (p) => p.name },
  { key: 'onHand', header: 'On hand', value: (p) => p.onHand, type: 'number' },
  { key: 'reserved', header: 'Reserved', value: (p) => p.reserved, type: 'number' },
  { key: 'available', header: 'Available', value: (p) => p.available, type: 'number' },
];
const resColumns: Column<Reservation>[] = [
  { key: 'id', header: 'Reservation', value: (r) => shortId(r.id) },
  { key: 'order', header: 'Order', value: (r) => shortId(r.orderId), type: 'link', link: (r) => `/orders/${r.orderId}` },
  { key: 'status', header: 'Status', value: (r) => r.status, type: 'status' },
  { key: 'items', header: 'Items', value: (r) => r.items.map((i) => `${i.sku} x${i.quantity}`).join(', ') },
  { key: 'expires', header: 'Expires', value: (r) => r.expiresAt, type: 'date' },
  { key: 'reason', header: 'Release reason', value: (r) => r.releaseReason ?? '' },
];

function StockTab() {
  const dispatch = useAppDispatch();
  const [sku, setSku] = useState('');
  const [low, setLow] = useState(false);
  const [adjusting, setAdjusting] = useState<Product | null>(null);
  const filters = useDebouncedValue({ sku: sku.trim(), lowStock: low }, 300);
  const list = useListQuery(filters, 20, 'sku,asc');
  const { query } = list;
  const res = useListInventoryQuery({ sku: query.filters.sku, lowStock: query.filters.lowStock ? true : '', page: query.page, size: query.size, sort: query.sort });

  return (
    <>
      <form className="filters" role="search" aria-label="Filter stock" onSubmit={(e) => e.preventDefault()} style={{ marginTop: 16 }}>
        <TextField className="grow" label="SKU" type="search" value={sku} onChange={(e) => setSku(e.target.value)} />
        <div className="btns">
          <FormControlLabel control={<Checkbox checked={low} onChange={(e) => setLow(e.target.checked)} />} label="Low stock only" />
        </div>
      </form>
      <p className="muted small">{LOW_STOCK_THRESHOLD_HINT}</p>
      <DataTable
        caption="Stock levels"
        columns={stockColumns}
        rows={res.data?.content ?? []}
        rowKey={(p) => p.id}
        loading={res.isFetching}
        error={res.error ?? null}
        total={res.data?.totalElements ?? 0}
        page={query.page}
        size={query.size}
        sort={query.sort}
        emptyTitle="No stock records match"
        onPageChange={list.setPage}
        onSortChange={list.toggleSort}
        onRetry={() => void res.refetch()}
        actions={(p) => (
          <Button variant="outlined" size="small" aria-label={`Adjust stock for ${p.sku}`} onClick={() => setAdjusting(p)}>
            Adjust stock
          </Button>
        )}
      />
      <StockAdjustDialog
        product={adjusting}
        onClose={(updated) => {
          const p = adjusting;
          setAdjusting(null);
          if (updated && p) dispatch(toast.success(`Stock updated for ${p.sku}`)); // the mutation invalidated the list
        }}
      />
    </>
  );
}

function ReservationsTab() {
  const [orderId, setOrderId] = useState('');
  const [status, setStatus] = useState('');
  const filters = useDebouncedValue({ orderId: orderId.trim(), status }, 300);
  const list = useListQuery(filters, 20);
  const { query } = list;
  const res = useListReservationsQuery({ ...query.filters, page: query.page, size: query.size, sort: query.sort });
  return (
    <>
      <form className="filters" role="search" aria-label="Filter reservations" onSubmit={(e) => e.preventDefault()} style={{ marginTop: 16 }}>
        <TextField className="grow" label="Order ID" type="search" value={orderId} onChange={(e) => setOrderId(e.target.value)} />
        <TextField select label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <MenuItem value="">Any</MenuItem>
          {RES_STATUSES.map((s) => (
            <MenuItem key={s} value={s}>
              {s}
            </MenuItem>
          ))}
        </TextField>
      </form>
      <DataTable
        caption="Reservations"
        columns={resColumns}
        rows={res.data?.content ?? []}
        rowKey={(r) => r.id}
        loading={res.isFetching}
        error={res.error ?? null}
        total={res.data?.totalElements ?? 0}
        page={query.page}
        size={query.size}
        sort={query.sort}
        emptyTitle="No reservations match"
        onPageChange={list.setPage}
        onRetry={() => void res.refetch()}
      />
    </>
  );
}

export default function Inventory() {
  const [tab, setTab] = useState<'stock' | 'reservations'>('stock');
  return (
    <>
      <PageHeader title="Inventory" subtitle="Stock levels and reservations (operators and admins)." />
      <Tabs value={tab} onChange={(_e, v: 'stock' | 'reservations') => setTab(v)} aria-label="Inventory views">
        <Tab label="Stock" value="stock" id="tab-stock" aria-controls="panel-stock" />
        <Tab label="Reservations" value="reservations" id="tab-reservations" aria-controls="panel-reservations" />
      </Tabs>
      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {tab === 'stock' ? <StockTab /> : <ReservationsTab />}
      </div>
    </>
  );
}
