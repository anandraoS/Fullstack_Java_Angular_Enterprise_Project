import { ConfigService, DEFAULT_CONFIG } from './config.service';

describe('ConfigService', () => {
  it('loads runtime config.json and strips a trailing slash from apiBaseUrl', async () => {
    const svc = new ConfigService();
    const fetchFn = (async () => new Response(JSON.stringify({ apiBaseUrl: 'https://gw.example.test/api/v1/' }))) as typeof fetch;
    await svc.load(fetchFn);
    expect(svc.apiBaseUrl).toBe('https://gw.example.test/api/v1');
    expect(svc.config().pollIntervalMs).toBe(DEFAULT_CONFIG.pollIntervalMs);
  });

  it('keeps defaults when the file is missing or the request fails', async () => {
    const svc = new ConfigService();
    await svc.load((async () => new Response('', { status: 404 })) as typeof fetch);
    expect(svc.apiBaseUrl).toBe('/api/v1');
    await svc.load((async () => {
      throw new Error('offline');
    }) as typeof fetch);
    expect(svc.apiBaseUrl).toBe('/api/v1');
  });
});
