import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { loadConfig } from './core/config';
import './styles.css';
import { createAppStore } from './store';

// config.json is fetched BEFORE the first render so every API call already uses the right base URL.
void loadConfig(fetch, `${import.meta.env.BASE_URL}config.json`).then(() => {
  const store = createAppStore();
  createRoot(document.getElementById('root') as HTMLElement).render(
    <StrictMode>
      <App store={store} />
    </StrictMode>,
  );
});
