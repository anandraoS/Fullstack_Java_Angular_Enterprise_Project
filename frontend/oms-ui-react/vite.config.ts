/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Dev server on 4201 (Angular uses 4200); /api is proxied to the gateway exactly like frontend/oms-ui/proxy.conf.json.
const GATEWAY = process.env.OMS_GATEWAY_URL ?? 'http://localhost:18080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 4201,
    strictPort: true,
    proxy: { '/api': { target: GATEWAY, changeOrigin: true, secure: false } },
  },
  preview: { port: 4201, strictPort: true, proxy: { '/api': { target: GATEWAY, changeOrigin: true, secure: false } } },
  build: {
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
    testTimeout: 20000,
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'html', 'json-summary'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/test/**', 'src/**/*.test.{ts,tsx}', 'src/main.tsx', 'src/vite-env.d.ts'],
    },
  },
});
