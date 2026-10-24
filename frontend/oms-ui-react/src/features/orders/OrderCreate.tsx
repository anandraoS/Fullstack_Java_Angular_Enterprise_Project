import { zodResolver } from '@hookform/resolvers/zod';
import Button from '@mui/material/Button';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import { useEffect, useRef, useState } from 'react';
import { Controller, useFieldArray, useForm, useWatch } from 'react-hook-form';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ErrorBanner } from '../../components/feedback';
import { PageHeader } from '../../components/PageHeader';
import { rhf } from '../../components/rhf';
import { normalizeUnknown, type ApiError } from '../../core/apiError';
import { useAppDispatch, useAppSelector } from '../../hooks/redux';
import { useCreateOrderMutation, useListCustomersQuery, useListProductsQuery } from '../../store/api';
import { selectCustomerId, selectIsCustomer, selectIsStaff, selectUsername } from '../../store/authSlice';
import { cartClear, selectCartLines } from '../../store/cartSlice';
import { toast } from '../../store/uiSlice';
import {
  IdempotencyKeyHolder,
  MAX_ITEMS,
  MAX_QTY,
  MIN_QTY,
  QTY_MESSAGE,
  buildRequest,
  duplicateProductIds,
  duplicateProductMessage,
  orderFormSchema,
  splitServerErrors,
  type OrderFormValues,
} from './orderForm';

export default function OrderCreate() {
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const staff = useAppSelector(selectIsStaff);
  const isCustomer = useAppSelector(selectIsCustomer);
  const username = useAppSelector(selectUsername);
  const accountCustomerId = useAppSelector(selectCustomerId);
  const cartLines = useAppSelector(selectCartLines);

  const customers = useListCustomersQuery({ page: 0, size: 100, sort: 'name,asc' }, { skip: !staff });
  const productsRes = useListProductsQuery({ page: 0, size: 100, sort: 'sku,asc' });
  const products = (productsRes.data?.content ?? []).filter((p) => p.active !== false);
  const [createOrder, { isLoading: submitting }] = useCreateOrderMutation();

  const [holder] = useState(() => new IdempotencyKeyHolder());
  const [shownKey, setShownKey] = useState<string | null>(null);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState<ApiError | string | null>(null);
  const addButton = useRef<HTMLButtonElement>(null);
  const focusRow = useRef<number | null>(null);

  const [initial] = useState<OrderFormValues>(() => ({
    customerId: isCustomer ? (accountCustomerId ?? '') : '',
    items: params.get('fromCart') && cartLines.length > 0 ? cartLines.map((l) => ({ productId: l.productId, quantity: String(l.quantity) })) : [{ productId: '', quantity: '1' }],
    shippingAddress: { line1: '', city: '', postalCode: '', country: 'US' },
  }));

  const {
    register,
    control,
    handleSubmit,
    setError: setFieldError,
    formState: { errors, submitCount },
  } = useForm<OrderFormValues>({ resolver: zodResolver(orderFormSchema), defaultValues: initial });
  const { fields, append, remove } = useFieldArray({ control, name: 'items' });
  const watched = useWatch({ control, name: 'items' });
  const dupes = duplicateProductIds(watched ?? []);

  // keyboard focus management after adding a row (focus the new product select)
  useEffect(() => {
    if (focusRow.current !== null) {
      document.querySelector<HTMLElement>(`[data-testid="product-${focusRow.current}"]`)?.focus();
      focusRow.current = null;
    }
  });

  const lineAmount = (i: number): number => {
    const row = watched?.[i];
    const p = products.find((x) => x.id === row?.productId);
    return p ? p.price * Number(row?.quantity ?? 0) || 0 : 0;
  };
  const total = (watched ?? []).reduce((sum, _r, i) => sum + lineAmount(i), 0);

  const addRow = () => {
    if (fields.length >= MAX_ITEMS) return;
    focusRow.current = fields.length;
    append({ productId: '', quantity: '1' });
  };
  const removeRow = (i: number) => {
    if (fields.length <= 1) return;
    remove(i);
    setTimeout(() => addButton.current?.focus(), 0); // keep keyboard focus on a stable control
  };

  const onSubmit = handleSubmit(async (values) => {
    if (submitting) return; // guards double-click / Enter-key repeats
    const body = buildRequest(values);
    const key = holder.keyFor(body);
    setShownKey(key);
    setError(null);
    try {
      const res = await createOrder({ body, key }).unwrap();
      holder.settle();
      dispatch(cartClear());
      dispatch(toast.success(res.replay ? 'This order was already accepted; showing the original.' : 'Order accepted. Processing has started.'));
      navigate(`/orders/${res.order.id}`);
    } catch (e) {
      const err = normalizeUnknown(e);
      if (err.status === 0 || err.status >= 500) {
        setUncertain(true); // outcome unknown: keep the key so a retry is idempotent
        setError(err);
      } else {
        setUncertain(false);
        holder.settle();
        setShownKey(null);
        const { mapped, unmatched } = splitServerErrors(err.fieldErrors);
        mapped.forEach((m) => setFieldError(m.path as never, { type: 'server', message: m.message }));
        setError(unmatched.length || !err.fieldErrors.length ? err : 'Some fields need attention.');
      }
    }
  });

  const addr = errors.shippingAddress;
  const hasErrors = submitCount > 0 && Object.keys(errors).length > 0;

  return (
    <>
      <PageHeader title="Create order" subtitle="Orders are accepted asynchronously: you will be taken to the order to watch its progress." />

      <form onSubmit={(e) => void onSubmit(e)} noValidate aria-label="New order" className="order-form">
        <ErrorBanner error={error} retryable={false} />
        {hasErrors && (
          <p className="summary" role="alert">
            Please fix the highlighted fields before submitting.
          </p>
        )}
        {uncertain && (
          <p className="notice" role="status">
            The server did not confirm the order. It is safe to submit again: the same request key is reused, so no duplicate order can be created.
          </p>
        )}

        <section className="card" aria-labelledby="cust-h">
          <h2 id="cust-h">Customer</h2>
          {staff ? (
            <Controller
              name="customerId"
              control={control}
              render={({ field, fieldState }) => (
                <TextField
                  select
                  label="Customer"
                  required
                  fullWidth
                  value={field.value}
                  onChange={field.onChange}
                  onBlur={field.onBlur}
                  inputRef={field.ref}
                  error={!!fieldState.error}
                  helperText={fieldState.error?.message}
                  slotProps={{ select: { SelectDisplayProps: { 'data-testid': 'customer-select' } as React.HTMLAttributes<HTMLDivElement> } }}
                >
                  {(customers.data?.content ?? []).map((c) => (
                    <MenuItem key={c.id} value={c.id}>
                      {c.name} ({c.email})
                    </MenuItem>
                  ))}
                </TextField>
              )}
            />
          ) : (
            <p>
              Ordering as <strong>{username}</strong> (your account customer).
            </p>
          )}
        </section>

        <section className="card" aria-labelledby="items-h">
          <h2 id="items-h">Items</h2>
          {productsRes.isError && <ErrorBanner error={productsRes.error} onRetry={() => void productsRes.refetch()} />}
          {fields.map((f, i) => (
            <div className="item-row" key={f.id} role="group" aria-label={`Item ${i + 1}`}>
              <Controller
                name={`items.${i}.productId`}
                control={control}
                render={({ field, fieldState }) => (
                  <TextField
                    select
                    className="prod"
                    label="Product"
                    required
                    value={products.some((p) => p.id === field.value) ? field.value : ''}
                    onChange={field.onChange}
                    onBlur={field.onBlur}
                    inputRef={field.ref}
                    error={!!fieldState.error}
                    helperText={fieldState.error?.message}
                    slotProps={{ select: { SelectDisplayProps: { 'data-testid': `product-${i}` } as React.HTMLAttributes<HTMLDivElement> } }}
                  >
                    {products.map((p) => (
                      <MenuItem key={p.id} value={p.id}>
                        {p.sku} - {p.name} ({p.available} available)
                      </MenuItem>
                    ))}
                  </TextField>
                )}
              />
              <Controller
                name={`items.${i}.quantity`}
                control={control}
                render={({ field, fieldState }) => (
                  <TextField
                    className="qty"
                    label="Quantity"
                    type="number"
                    required
                    value={field.value}
                    onChange={field.onChange}
                    onBlur={field.onBlur}
                    inputRef={field.ref}
                    error={!!fieldState.error}
                    helperText={fieldState.error ? QTY_MESSAGE : undefined}
                    slotProps={{ htmlInput: { min: MIN_QTY, max: MAX_QTY, step: 1, 'data-testid': `qty-${i}` } }}
                  />
                )}
              />
              <div className="line-total" aria-live="polite">
                {lineAmount(i) ? lineAmount(i).toFixed(2) : ''}
              </div>
              <Button type="button" onClick={() => removeRow(i)} disabled={fields.length === 1} aria-label={`Remove item ${i + 1}`}>
                Remove
              </Button>
            </div>
          ))}
          {dupes.length > 0 && (
            <p className="field-error" role="alert">
              {duplicateProductMessage}
            </p>
          )}
          {fields.length > MAX_ITEMS && (
            <p className="field-error" role="alert">
              An order can have at most {MAX_ITEMS} lines.
            </p>
          )}
          <Button type="button" variant="outlined" ref={addButton} onClick={addRow} disabled={fields.length >= MAX_ITEMS}>
            Add item
          </Button>
          <p className="total" aria-live="polite">
            Estimated total: <strong data-testid="est-total">{total.toFixed(2)} USD</strong>
          </p>
        </section>

        <section className="card" aria-labelledby="ship-h">
          <h2 id="ship-h">Shipping address</h2>
          <div className="addr">
            <TextField className="wide" label="Address line" required autoComplete="address-line1" {...rhf(register('shippingAddress.line1'))} slotProps={{ htmlInput: { maxLength: 200 } }} error={!!addr?.line1} helperText={addr?.line1?.message} />
            <TextField label="City" required autoComplete="address-level2" {...rhf(register('shippingAddress.city'))} slotProps={{ htmlInput: { maxLength: 100 } }} error={!!addr?.city} helperText={addr?.city?.message} />
            <TextField label="Postal code" required autoComplete="postal-code" {...rhf(register('shippingAddress.postalCode'))} slotProps={{ htmlInput: { maxLength: 10 } }} error={!!addr?.postalCode} helperText={addr?.postalCode?.message} />
            <TextField label="Country (2-letter code)" required autoComplete="country" {...rhf(register('shippingAddress.country'))} slotProps={{ htmlInput: { maxLength: 2 } }} error={!!addr?.country} helperText={addr?.country?.message} />
          </div>
        </section>

        <div className="row actions">
          <Button type="submit" variant="contained" disabled={submitting}>
            {submitting ? 'Submitting...' : 'Place order'}
          </Button>
          <Button component={Link} to="/orders">
            Cancel
          </Button>
          {shownKey && (
            <span className="muted mono" data-testid="idempotency-key">
              Request key: {shownKey}
            </span>
          )}
        </div>
      </form>
    </>
  );
}
