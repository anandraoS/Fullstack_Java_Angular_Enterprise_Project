import { DatePipe } from '@angular/common';
import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { OrderApi } from '../../core/api/apis';
import { AuthStore } from '../../core/auth/auth.store';
import { ApiError, normalizeError } from '../../core/http/api-error';
import { Toaster } from '../../core/notify/toaster';
import { ConfirmDialog, ConfirmDialogResult, confirmDialogConfig } from '../../shared/components/confirm-dialog';
import { ErrorBanner, LoadingOverlay } from '../../shared/components/feedback';
import { PageHeader } from '../../shared/components/page-header';
import { StatusChip } from '../../shared/components/status-chip';
import { StatusTimeline } from '../../shared/components/status-timeline';
import { OrderDetailStore } from './order-detail.store';

@Component({
  selector: 'app-order-detail',
  imports: [DatePipe, PageHeader, StatusChip, StatusTimeline, ErrorBanner, LoadingOverlay, MatButtonModule, RouterLink],
  providers: [OrderDetailStore],
  template: `
    <app-page-header [title]="'Order ' + shortId()" subtitle="Status refreshes automatically until the order reaches a final state.">
      <a mat-button routerLink="/orders">Back to orders</a>
      @if (store.polling()) {
        <button mat-stroked-button type="button" (click)="store.stopPolling()">Stop auto-refresh</button>
      } @else {
        <button mat-stroked-button type="button" (click)="store.refresh(id())">Refresh</button>
      }
      @if (store.cancellable()) {
        <button mat-flat-button color="warn" type="button" (click)="cancel()" [disabled]="cancelling()" data-testid="cancel-order">Cancel order</button>
      }
    </app-page-header>

    <app-error-banner [error]="store.error()" (retry)="store.refresh(id())" />
    <app-error-banner [error]="actionError()" [retryable]="false" />

    <div class="rel" [attr.aria-busy]="store.loading()">
      <app-loading-overlay [active]="store.loading() && !store.order()" />
      @if (store.order(); as o) {
        <div class="sr-only" role="status" aria-live="polite" data-testid="status-announcer">Order status: {{ o.status.replace('_', ' ') }}</div>

        <section class="card" aria-labelledby="progress-h">
          <h2 id="progress-h" class="h2">Progress</h2>
          <div class="row">
            <app-status-chip [status]="o.status" data-testid="order-status" />
            @if (o.statusReason) { <span class="muted" data-testid="status-reason">{{ o.statusReason }}</span> }
            @if (store.polling()) { <span class="muted" data-testid="polling-indicator">Auto-refreshing...</span> }
          </div>
          <app-status-timeline [status]="o.status" [history]="store.history()" />
        </section>

        <section class="section card" aria-labelledby="sum-h">
          <h2 id="sum-h" class="h2">Summary</h2>
          <dl class="kv">
            <dt>Order ID</dt><dd class="mono" data-testid="order-id">{{ o.id }}</dd>
            <dt>Customer</dt><dd class="mono">{{ o.customerId }}</dd>
            <dt>Total</dt><dd>{{ money(o.totalAmount) }} {{ o.currency }}</dd>
            <dt>Created</dt><dd>{{ o.createdAt | date: 'medium' }}</dd>
            <dt>Updated</dt><dd>{{ o.updatedAt | date: 'medium' }}</dd>
            <dt>Ship to</dt><dd>{{ o.shippingAddress.line1 }}, {{ o.shippingAddress.city }} {{ o.shippingAddress.postalCode }}, {{ o.shippingAddress.country }}</dd>
            <dt>Reference</dt><dd class="mono">{{ o.correlationId }}</dd>
          </dl>
        </section>

        <section class="section card" aria-labelledby="items-h">
          <h2 id="items-h" class="h2">Items</h2>
          <div class="scroll">
          <table class="t">
            <caption class="sr-only">Order items</caption>
            <thead><tr><th scope="col">SKU</th><th scope="col">Name</th><th scope="col" class="num">Qty</th><th scope="col" class="num">Unit price</th><th scope="col" class="num">Line total</th></tr></thead>
            <tbody>
              @for (i of o.items; track i.productId) {
                <tr><td>{{ i.sku }}</td><td>{{ i.name }}</td><td class="num">{{ i.quantity }}</td><td class="num">{{ money(i.unitPrice) }}</td><td class="num">{{ money(i.lineTotal) }}</td></tr>
              }
            </tbody>
          </table>
          </div>
        </section>

        <section class="section card" aria-labelledby="hist-h">
          <h2 id="hist-h" class="h2">History</h2>
          @if (store.history().length === 0) {
            <p class="muted">No transitions recorded yet.</p>
          } @else {
            <div class="scroll">
            <table class="t">
              <caption class="sr-only">Status history</caption>
              <thead><tr><th scope="col">When</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Reason</th><th scope="col">Actor</th></tr></thead>
              <tbody>
                @for (h of store.history(); track h.occurredAt + h.toStatus) {
                  <tr>
                    <td>{{ h.occurredAt | date: 'MMM d, HH:mm:ss' }}</td>
                    <td>{{ h.fromStatus ?? '-' }}</td>
                    <td><app-status-chip [status]="h.toStatus" /></td>
                    <td>{{ h.reason ?? '' }}</td>
                    <td>{{ h.actor ?? '' }}</td>
                  </tr>
                }
              </tbody>
            </table>
            </div>
          }
        </section>

        @if (auth.isStaff() && store.saga(); as s) {
          <section class="section card" aria-labelledby="saga-h">
            <h2 id="saga-h" class="h2">Saga (staff view)</h2>
            <dl class="kv">
              <dt>State</dt><dd>{{ s.state }}</dd>
              <dt>Current step</dt><dd>{{ s.currentStep }}</dd>
              <dt>Attempts</dt><dd>{{ s.attempts }}</dd>
              @if (s.lastError) { <dt>Last error</dt><dd>{{ s.lastError }}</dd> }
            </dl>
            <div class="scroll">
            <table class="t">
              <caption class="sr-only">Saga step log</caption>
              <thead><tr><th scope="col">Step</th><th scope="col">Status</th><th scope="col" class="num">Attempt</th><th scope="col">Detail</th><th scope="col">At</th></tr></thead>
              <tbody>
                @for (st of s.steps; track st.at + st.step + st.attempt) {
                  <tr><td>{{ st.step }}</td><td>{{ st.status }}</td><td class="num">{{ st.attempt }}</td><td>{{ st.detail ?? '' }}</td><td>{{ st.at | date: 'HH:mm:ss' }}</td></tr>
                }
              </tbody>
            </table>
            </div>
          </section>
        }
      }
    </div>
  `,
  styles: `
    .rel { position: relative; min-height: 100px; }
    .h2 { font-size: 1.1rem; margin: 0 0 8px; }
    .scroll { overflow-x: auto; }
    .t { width: 100%; border-collapse: collapse; }
    .t th, .t td { text-align: left; padding: 8px; border-bottom: 1px solid #e3e7ee; }
    .t .num { text-align: right; }
  `,
})
export class OrderDetail {
  protected readonly store = inject(OrderDetailStore);
  protected readonly auth = inject(AuthStore);
  private readonly api = inject(OrderApi);
  private readonly dialog = inject(MatDialog);
  private readonly toaster = inject(Toaster);

  /** bound from the route parameter (withComponentInputBinding) */
  readonly id = input.required<string>();

  protected readonly cancelling = signal(false);
  protected readonly actionError = signal<ApiError | null>(null);

  constructor() {
    // useEffect-like: restart when the route id changes (untracked: only `id` is a dependency)
    effect(() => {
      const id = this.id();
      untracked(() => this.store.start(id));
    });
  }

  /**
   * Prices are null until the saga's validation step has priced the order and stay null for orders rejected at validation
   * (contract section 13). Rendering `null.toFixed()` threw and left the whole page blank (found by the live e2e run).
   */
  protected money(v: number | null | undefined): string {
    return typeof v === 'number' ? v.toFixed(2) : '-';
  }

  protected shortId(): string {
    return this.id().slice(0, 8);
  }

  protected cancel(): void {
    this.dialog
      .open<ConfirmDialog, unknown, ConfirmDialogResult>(
        ConfirmDialog,
        confirmDialogConfig({
          title: 'Cancel this order?',
          message: 'Reserved stock will be released. This cannot be undone.',
          confirmLabel: 'Cancel order',
          cancelLabel: 'Keep order',
          reasonLabel: 'Reason for cancellation',
          minReasonLength: 3,
          destructive: true,
        }),
      )
      .afterClosed()
      .subscribe((res) => {
        if (!res) return;
        this.cancelling.set(true);
        this.actionError.set(null);
        this.api.cancel(this.id(), res.reason).subscribe({
          next: () => {
            this.cancelling.set(false);
            this.toaster.success('Cancellation requested.');
            this.store.refresh(this.id());
          },
          error: (e: unknown) => {
            this.cancelling.set(false);
            this.actionError.set(normalizeError(e));
            this.store.refresh(this.id());
          },
        });
      });
  }
}
