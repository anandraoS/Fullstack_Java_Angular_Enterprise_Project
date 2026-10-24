import CssBaseline from '@mui/material/CssBaseline';
import { ThemeProvider } from '@mui/material/styles';
import type { ReactNode } from 'react';
import { Provider } from 'react-redux';
import { BrowserRouter } from 'react-router-dom';
import { ToastHost } from './components/ToastHost';
import { useSessionExpiry } from './hooks/useSessionExpiry';
import { AppRoutes } from './routes';
import type { AppStore } from './store';
import { theme } from './theme';

function Root() {
  useSessionExpiry();
  return (
    <>
      <AppRoutes />
      <ToastHost />
    </>
  );
}

/** Providers shared by the app and the tests (tests pass their own store and router). */
export function Providers({ store, children }: { store: AppStore; children: ReactNode }) {
  return (
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </Provider>
  );
}

export function App({ store }: { store: AppStore }) {
  return (
    <Providers store={store}>
      <BrowserRouter>
        <Root />
      </BrowserRouter>
    </Providers>
  );
}

export { Root };
