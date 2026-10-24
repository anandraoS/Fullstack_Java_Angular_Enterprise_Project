import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import type { ReactNode } from 'react';
import { asApiError } from '../core/apiError';

/** Inline error with role=alert (announced immediately), correlation reference and optional retry. */
export function ErrorBanner({
  error,
  retryable = true,
  onRetry,
}: {
  /** an ApiError, a plain message, or anything a query hook returns (normalized) */
  error: unknown;
  retryable?: boolean;
  onRetry?: () => void;
}) {
  if (!error) return null;
  const api = typeof error === 'string' ? null : asApiError(error);
  const message = typeof error === 'string' ? error : (api?.userMessage ?? '');
  const fields = api?.fieldErrors ?? [];
  const reference = api?.correlationId ?? null;
  return (
    <div className="banner" role="alert">
      <div className="body">
        <strong>{message}</strong>
        {fields.length > 0 && (
          <ul>
            {fields.map((f) => (
              <li key={f.field + f.message}>
                {f.field}: {f.message}
              </li>
            ))}
          </ul>
        )}
        {reference && (
          <div className="ref">
            Reference: <code>{reference}</code>
          </div>
        )}
      </div>
      {retryable && onRetry && (
        <Button variant="outlined" size="small" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}

/** Covers its positioned parent while loading; announces "Loading" politely. */
export function LoadingOverlay({ active, label = 'Loading' }: { active: boolean; label?: string }) {
  return (
    <div role="status" aria-live="polite">
      {active && (
        <div className="overlay">
          <CircularProgress size={36} aria-label="Loading" />
          <span className="sr-only">{label}</span>
        </div>
      )}
    </div>
  );
}

export function EmptyState({ title = 'Nothing to show', message = '', children }: { title?: string; message?: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <p className="title">{title}</p>
      {message ? <p className="msg">{message}</p> : null}
      {children}
    </div>
  );
}
