export interface AppConfig {
  apiBaseUrl: string;
  pollIntervalMs: number;
  pollMaxIntervalMs: number;
}

export const DEFAULT_CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  pollIntervalMs: 1500,
  pollMaxIntervalMs: 8000,
};

let current: AppConfig = DEFAULT_CONFIG;

/** Runtime configuration (public/config.json). Module state instead of Redux: it never changes after startup. */
export function getConfig(): AppConfig {
  return current;
}

export function apiBaseUrl(): string {
  return current.apiBaseUrl.replace(/\/+$/, '');
}

export function setConfig(cfg: Partial<AppConfig>): void {
  current = { ...DEFAULT_CONFIG, ...cfg };
}

/** Fetches config.json before the app renders; any failure keeps the defaults (the /api/v1 convention works as is). */
export async function loadConfig(fetchFn: typeof fetch = fetch, url = 'config.json'): Promise<void> {
  try {
    const res = await fetchFn(url, { cache: 'no-store' });
    if (res.ok) setConfig((await res.json()) as Partial<AppConfig>);
  } catch {
    // keep defaults
  }
}
