import { Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiError } from '../../core/http/api-error';

/** Inline error with role=alert (announced immediately), correlation reference and optional retry. */
@Component({
  selector: 'app-error-banner',
  imports: [MatButtonModule],
  template: `
    @if (error(); as e) {
      <div class="banner" role="alert">
        <div class="body">
          <strong>{{ message() }}</strong>
          @if (fieldErrors().length) {
            <ul>
              @for (f of fieldErrors(); track f.field + f.message) {
                <li>{{ f.field }}: {{ f.message }}</li>
              }
            </ul>
          }
          @if (reference()) {
            <div class="ref">Reference: <code>{{ reference() }}</code></div>
          }
        </div>
        @if (retryable()) {
          <button mat-stroked-button type="button" (click)="retry.emit()">Try again</button>
        }
      </div>
    }
  `,
  styles: `
    .banner { display: flex; gap: 12px; align-items: flex-start; justify-content: space-between; background: #fde7e7; color: #5c0a0a; border: 1px solid #c25555; border-left-width: 6px; border-radius: 4px; padding: 12px 16px; margin: 8px 0; }
    ul { margin: 6px 0 0; padding-left: 18px; }
    .ref { font-size: 0.8rem; margin-top: 4px; }
  `,
})
export class ErrorBanner {
  readonly error = input<ApiError | string | null>(null);
  readonly retryable = input(true);
  readonly retry = output<void>();

  readonly message = computed(() => {
    const e = this.error();
    return typeof e === 'string' ? e : (e?.userMessage ?? '');
  });
  readonly fieldErrors = computed(() => {
    const e = this.error();
    return e instanceof ApiError ? e.fieldErrors : [];
  });
  readonly reference = computed(() => {
    const e = this.error();
    return e instanceof ApiError ? e.correlationId : null;
  });
}

/** Covers its positioned parent while loading; announces "Loading" politely. */
@Component({
  selector: 'app-loading-overlay',
  imports: [MatProgressSpinnerModule],
  template: `
    <div class="live" role="status" aria-live="polite">
      @if (active()) {
        <div class="overlay">
          <mat-progress-spinner mode="indeterminate" diameter="36" aria-label="Loading" />
          <span class="sr-only">{{ label() }}</span>
        </div>
      }
    </div>
  `,
  styles: `
    .overlay { position: absolute; inset: 0; background: rgba(255, 255, 255, 0.6); display: flex; align-items: center; justify-content: center; z-index: 5; }
  `,
})
export class LoadingOverlay {
  readonly active = input(false);
  readonly label = input('Loading');
}

@Component({
  selector: 'app-empty-state',
  template: `
    <div class="empty">
      <p class="title">{{ title() }}</p>
      @if (message()) {
        <p class="msg">{{ message() }}</p>
      }
      <ng-content />
    </div>
  `,
  styles: `
    .empty { text-align: center; padding: 32px 16px; color: var(--oms-text-muted); }
    .title { font-size: 1.1rem; font-weight: 600; margin: 0 0 4px; color: var(--oms-text); }
    .msg { margin: 0 0 12px; }
  `,
})
export class EmptyState {
  readonly title = input('Nothing to show');
  readonly message = input('');
}
