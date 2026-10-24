import { createSlice, type PayloadAction } from '@reduxjs/toolkit';

export type ToastSeverity = 'success' | 'info' | 'warning' | 'error';

export interface Toast {
  id: number;
  severity: ToastSeverity;
  message: string;
}

export interface UiState {
  toasts: Toast[];
  mobileNavOpen: boolean;
}

const AUTO_HIDE_MS: Record<ToastSeverity, number> = { success: 4000, info: 4000, warning: 6000, error: 8000 };
export const toastDuration = (s: ToastSeverity): number => AUTO_HIDE_MS[s];

let nextId = 1;
const MAX_QUEUE = 5;

export const uiSlice = createSlice({
  name: 'ui',
  initialState: { toasts: [], mobileNavOpen: false } as UiState,
  reducers: {
    toastPushed: {
      reducer(state, a: PayloadAction<Toast>) {
        state.toasts.push(a.payload);
        if (state.toasts.length > MAX_QUEUE) state.toasts.shift();
      },
      prepare(severity: ToastSeverity, message: string) {
        return { payload: { id: nextId++, severity, message } };
      },
    },
    toastDismissed(state, a: PayloadAction<number>) {
      state.toasts = state.toasts.filter((t) => t.id !== a.payload);
    },
    mobileNavToggled(state, a: PayloadAction<boolean | undefined>) {
      state.mobileNavOpen = a.payload ?? !state.mobileNavOpen;
    },
  },
});

export const { toastPushed, toastDismissed, mobileNavToggled } = uiSlice.actions;

export const toast = {
  success: (m: string) => toastPushed('success', m),
  info: (m: string) => toastPushed('info', m),
  warn: (m: string) => toastPushed('warning', m),
  error: (m: string) => toastPushed('error', m),
};

interface WithUi {
  ui: UiState;
}
export const selectCurrentToast = (s: WithUi): Toast | null => s.ui.toasts[0] ?? null;
export const selectMobileNavOpen = (s: WithUi) => s.ui.mobileNavOpen;

export default uiSlice.reducer;
