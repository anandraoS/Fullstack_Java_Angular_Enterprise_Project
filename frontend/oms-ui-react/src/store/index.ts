import { configureStore, createListenerMiddleware, type Middleware } from '@reduxjs/toolkit';
import { omsApi } from './api';
import authReducer, { loggedOut, readStoredSession, writeStoredSession, type AuthState } from './authSlice';
import cartReducer from './cartSlice';
import uiReducer from './uiSlice';

function browserStorage(): Storage | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage;
  } catch {
    return null;
  }
}

export interface StoreOptions {
  /** start signed in (tests) instead of reading sessionStorage */
  preloadedAuth?: AuthState;
  storage?: Storage | null;
}

/** Store factory: tests build an isolated store per case; the app builds exactly one in main.tsx. */
export function createAppStore(opts: StoreOptions = {}) {
  const storage = opts.storage === undefined ? browserStorage() : opts.storage;
  const restored = opts.preloadedAuth ?? { session: readStoredSession(storage, Date.now()), logoutReason: null };

  // On logout every cached server response is dropped so the next user can never see the previous user's data.
  const listener = createListenerMiddleware();
  listener.startListening({
    actionCreator: loggedOut,
    effect: (_a, api) => {
      api.dispatch(omsApi.util.resetApiState());
    },
  });

  // Mirror the session to sessionStorage (documented trade-off, see authSlice).
  const persist: Middleware<object, { auth: AuthState }> = (store) => (next) => (action) => {
    const before = store.getState().auth.session;
    const result = next(action);
    const after = store.getState().auth.session;
    if (before !== after) writeStoredSession(storage, after);
    return result;
  };

  return configureStore({
    reducer: {
      auth: authReducer,
      cart: cartReducer,
      ui: uiReducer,
      [omsApi.reducerPath]: omsApi.reducer,
    },
    preloadedState: { auth: restored },
    middleware: (getDefault) => getDefault().prepend(listener.middleware).concat(omsApi.middleware, persist),
  });
}

export type AppStore = ReturnType<typeof createAppStore>;
export type RootState = ReturnType<AppStore['getState']>;
export type AppDispatch = AppStore['dispatch'];
