import { useCallback, useState, type ReactElement } from 'react';
import { ConfirmDialog, type ConfirmDialogData, type ConfirmDialogResult } from './ConfirmDialog';

/**
 * Promise-style confirmation: `const res = await ask({...})` resolves with `{reason}` on confirm and `null` on cancel,
 * so call sites read like the Angular `dialog.afterClosed()` flow. Render `element` once in the component.
 */
export function useConfirm(): { ask: (data: ConfirmDialogData) => Promise<ConfirmDialogResult | null>; element: ReactElement } {
  const [state, setState] = useState<{ data: ConfirmDialogData; resolve: (r: ConfirmDialogResult | null) => void } | null>(null);
  const ask = useCallback(
    (data: ConfirmDialogData) => new Promise<ConfirmDialogResult | null>((resolve) => setState({ data, resolve })),
    [],
  );
  const element = (
    <ConfirmDialog
      open={state !== null}
      data={state?.data ?? null}
      onClose={(r) => {
        state?.resolve(r);
        setState(null);
      }}
    />
  );
  return { ask, element };
}
