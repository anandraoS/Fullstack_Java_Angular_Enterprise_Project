import { zodResolver } from '@hookform/resolvers/zod';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';
import TextField from '@mui/material/TextField';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

export interface ConfirmDialogData {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** when set, a mandatory reason textarea is shown */
  reasonLabel?: string;
  minReasonLength?: number;
  destructive?: boolean;
}

export interface ConfirmDialogResult {
  reason: string;
}

/** Zod schema for the optional mandatory reason. */
export function reasonSchema(min: number, required: boolean) {
  const base = z.string().trim().max(500);
  return z.object({ reason: required ? base.min(min, `Enter at least ${min} characters.`) : base });
}

interface Props {
  open: boolean;
  data: ConfirmDialogData | null;
  /** null = cancelled */
  onClose: (result: ConfirmDialogResult | null) => void;
}

function ConfirmForm({ data, onClose }: { data: ConfirmDialogData; onClose: Props['onClose'] }) {
  const min = data.minReasonLength ?? 3;
  const withReason = !!data.reasonLabel;
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<{ reason: string }>({ resolver: zodResolver(reasonSchema(min, withReason)), defaultValues: { reason: '' } });
  const { ref, ...reasonProps } = register('reason');
  return (
    <form noValidate onSubmit={handleSubmit((v) => onClose({ reason: v.reason.trim() }))}>
      <DialogTitle id="confirm-title">{data.title}</DialogTitle>
      <DialogContent>
        <DialogContentText id="confirm-message" component="p" sx={{ color: 'text.primary' }}>
          {data.message}
        </DialogContentText>
        {withReason && (
          <TextField
            {...reasonProps}
            inputRef={ref}
            label={data.reasonLabel}
            multiline
            rows={3}
            fullWidth
            required
            // eslint-disable-next-line jsx-a11y/no-autofocus -- intentional: focus must move into the dialog
            autoFocus
            margin="normal"
            error={!!errors.reason}
            helperText={errors.reason?.message}
            slotProps={{ htmlInput: { maxLength: 500 } }}
          />
        )}
      </DialogContent>
      <DialogActions>
        {/* the safe choice gets the initial focus (first tabbable control) when there is no reason field */}
        {/* eslint-disable-next-line jsx-a11y/no-autofocus -- intentional: focus must move into the dialog */}
        <Button type="button" autoFocus={!withReason} onClick={() => onClose(null)}>
          {data.cancelLabel ?? 'Cancel'}
        </Button>
        <Button type="submit" variant="contained" color={data.destructive ? 'error' : 'primary'}>
          {data.confirmLabel ?? 'Confirm'}
        </Button>
      </DialogActions>
    </form>
  );
}

/**
 * Accessible confirmation (role=alertdialog): MUI traps focus inside, closes on Escape, and restores focus to the control
 * that opened it. The form remounts per opening so state never leaks between uses.
 */
export function ConfirmDialog({ open, data, onClose }: Props) {
  return (
    <Dialog
      open={open && !!data}
      onClose={() => onClose(null)}
      maxWidth="xs"
      fullWidth
      aria-labelledby="confirm-title"
      aria-describedby="confirm-message"
      slotProps={{ paper: { role: 'alertdialog' } }}
    >
      {data && <ConfirmForm key={data.title} data={data} onClose={onClose} />}
    </Dialog>
  );
}
