import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Snackbar from '@mui/material/Snackbar';
import { useAppDispatch, useAppSelector } from '../hooks/redux';
import { selectCurrentToast, toastDismissed, toastDuration } from '../store/uiSlice';

/**
 * Renders the head of the toast queue (uiSlice). The Alert uses role="status" (polite live region), not "alert", so a
 * toast never competes with the assertive inline error banners.
 */
export function ToastHost() {
  const dispatch = useAppDispatch();
  const current = useAppSelector(selectCurrentToast);
  return (
    <Snackbar
      key={current?.id}
      open={current !== null}
      autoHideDuration={current ? toastDuration(current.severity) : null}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      onClose={(_e, reason) => {
        if (reason !== 'clickaway' && current) dispatch(toastDismissed(current.id));
      }}
    >
      {current ? (
        <Alert
          role="status"
          aria-live="polite"
          variant="filled"
          severity={current.severity}
          action={
            <Button color="inherit" size="small" onClick={() => dispatch(toastDismissed(current.id))}>
              Dismiss
            </Button>
          }
        >
          {current.message}
        </Alert>
      ) : undefined}
    </Snackbar>
  );
}
