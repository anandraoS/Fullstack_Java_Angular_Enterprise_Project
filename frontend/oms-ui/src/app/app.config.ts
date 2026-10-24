import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { ConfigService } from './core/config.service';
import { authInterceptor, correlationInterceptor, errorInterceptor, retryInterceptor } from './core/http/interceptors';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
    // Order matters: request flows left->right, errors flow right->left. retry sees normalized ApiErrors.
    provideHttpClient(withInterceptors([correlationInterceptor, authInterceptor, retryInterceptor, errorInterceptor])),
    provideAppInitializer(() => inject(ConfigService).load()),
  ],
};
