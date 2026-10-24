import { zodResolver } from '@hookform/resolvers/zod';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import TextField from '@mui/material/TextField';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { ErrorBanner } from '../../components/feedback';
import { rhf } from '../../components/rhf';
import { normalizeUnknown, type ApiError } from '../../core/apiError';
import type { Product } from '../../models';
import { useAdjustStockMutation } from '../../store/api';
import { stockAdjustSchema, type StockAdjustValues } from './stockAdjust';

function AdjustForm({ product, onClose }: { product: Product; onClose: (updated: Product | null) => void }) {
  const [adjust, { isLoading }] = useAdjustStockMutation();
  const [error, setError] = useState<ApiError | null>(null);
  const onHand = product.onHand ?? 0;
  const reserved = product.reserved ?? 0;
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<StockAdjustValues>({ resolver: zodResolver(stockAdjustSchema(onHand, reserved)), defaultValues: { delta: '', reason: '' } });

  const submit = handleSubmit(async (v) => {
    if (isLoading) return;
    setError(null);
    try {
      onClose(await adjust({ productId: product.id, delta: Number(v.delta), reason: v.reason.trim() }).unwrap());
    } catch (e) {
      setError(normalizeUnknown(e));
    }
  });

  return (
    <form onSubmit={(e) => void submit(e)} noValidate>
      <DialogTitle id="adjust-title">Adjust stock: {product.sku}</DialogTitle>
      <DialogContent>
        <p className="muted" style={{ marginTop: 0 }}>
          {product.name}. On hand {onHand}, reserved {reserved}.
        </p>
        <ErrorBanner error={error} retryable={false} />
        <TextField
          label="Change (use a negative number to remove)"
          type="number"
          required
          fullWidth
          // eslint-disable-next-line jsx-a11y/no-autofocus -- intentional: focus must move into the dialog
          autoFocus
          {...rhf(register('delta'))}
          slotProps={{ htmlInput: { step: 1 } }}
          error={!!errors.delta}
          helperText={errors.delta?.message}
        />
        <TextField
          label="Reason"
          multiline
          rows={2}
          required
          fullWidth
          {...rhf(register('reason'))}
          slotProps={{ htmlInput: { maxLength: 200 } }}
          error={!!errors.reason}
          helperText={errors.reason?.message}
        />
      </DialogContent>
      <DialogActions>
        <Button type="button" onClick={() => onClose(null)}>
          Cancel
        </Button>
        <Button type="submit" variant="contained" disabled={isLoading}>
          {isLoading ? 'Saving...' : 'Apply change'}
        </Button>
      </DialogActions>
    </form>
  );
}

/** Modal (role=dialog): focus moves to the first field, is trapped, and returns to the opener on close. */
export function StockAdjustDialog({ product, onClose }: { product: Product | null; onClose: (updated: Product | null) => void }) {
  return (
    <Dialog open={product !== null} onClose={() => onClose(null)} maxWidth="xs" fullWidth aria-labelledby="adjust-title">
      {product && <AdjustForm key={product.id} product={product} onClose={onClose} />}
    </Dialog>
  );
}
