import { Injectable, signal } from '@angular/core';

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

/**
 * Runtime configuration (public/config.json, fetched before bootstrap completes). Lets one build be deployed to any
 * environment by replacing a static file, without rebuilding and without any .env file.
 */
@Injectable({ providedIn: 'root' })
export class ConfigService {
  private readonly _config = signal<AppConfig>(DEFAULT_CONFIG);
  readonly config = this._config.asReadonly();

  get apiBaseUrl(): string {
    return this._config().apiBaseUrl.replace(/\/+$/, '');
  }

  set(cfg: Partial<AppConfig>): void {
    this._config.set({ ...DEFAULT_CONFIG, ...cfg });
  }

  async load(fetchFn: typeof fetch = fetch): Promise<void> {
    try {
      const res = await fetchFn('config.json', { cache: 'no-store' });
      if (res.ok) {
        this.set((await res.json()) as Partial<AppConfig>);
      }
    } catch {
      // keep defaults: the proxy/nginx convention /api/v1 works without config.json
    }
  }
}
