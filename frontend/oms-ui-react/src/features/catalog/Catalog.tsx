import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import TextField from '@mui/material/TextField';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { DataTable, type Column } from '../../components/DataTable';
import { PageHeader } from '../../components/PageHeader';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { useListQuery } from '../../hooks/listQuery';
import { useAppDispatch, useAppSelector } from '../../hooks/redux';
import type { Product } from '../../models';
import { useListProductsQuery } from '../../store/api';
import { cartAdd, selectCartCount } from '../../store/cartSlice';
import { toast } from '../../store/uiSlice';

const columns: Column<Product>[] = [
  { key: 'sku', header: 'SKU', value: (p) => p.sku, sortField: 'sku' },
  { key: 'name', header: 'Name', value: (p) => p.name, sortField: 'name' },
  { key: 'category', header: 'Category', value: (p) => p.category, sortField: 'category' },
  { key: 'price', header: 'Price (USD)', value: (p) => p.price, type: 'money', sortField: 'price' },
  { key: 'available', header: 'Available', value: (p) => p.available, type: 'number' },
];

export default function Catalog() {
  const dispatch = useAppDispatch();
  const cartCount = useAppSelector(selectCartCount);
  const [q, setQ] = useState('');
  const [category, setCategory] = useState('');
  const [inStock, setInStock] = useState(false);
  const filters = useDebouncedValue({ q: q.trim(), category: category.trim(), inStock }, 300);
  const list = useListQuery(filters, 20, 'sku,asc');
  const { query } = list;
  const res = useListProductsQuery({ q: query.filters.q, category: query.filters.category, inStock: query.filters.inStock ? true : '', page: query.page, size: query.size, sort: query.sort });

  return (
    <>
      <PageHeader title="Product catalog" subtitle="Browse products and add them to your cart.">
        <Button variant="outlined" component={Link} to="/orders/new?fromCart=1">
          Go to order form ({cartCount} in cart)
        </Button>
      </PageHeader>

      <form className="filters" role="search" aria-label="Filter products" onSubmit={(e) => e.preventDefault()}>
        <TextField className="grow" label="Search name or SKU" type="search" value={q} onChange={(e) => setQ(e.target.value)} />
        <TextField label="Category" value={category} onChange={(e) => setCategory(e.target.value)} />
        <div className="btns">
          <FormControlLabel control={<Checkbox checked={inStock} onChange={(e) => setInStock(e.target.checked)} />} label="In stock only" />
        </div>
      </form>

      <DataTable
        caption="Products"
        columns={columns}
        rows={res.data?.content ?? []}
        rowKey={(p) => p.id}
        loading={res.isFetching}
        error={res.error ?? null}
        total={res.data?.totalElements ?? 0}
        page={query.page}
        size={query.size}
        sort={query.sort}
        emptyTitle="No products match"
        onPageChange={list.setPage}
        onSortChange={list.toggleSort}
        onRetry={() => void res.refetch()}
        actions={(p) => (
          <Button
            variant="outlined"
            size="small"
            aria-label={`Add ${p.name} to cart`}
            disabled={p.available < 1}
            onClick={() => {
              dispatch(cartAdd({ product: p }));
              dispatch(toast.success(`${p.sku} added to cart`));
            }}
          >
            Add to cart
          </Button>
        )}
      />
    </>
  );
}
