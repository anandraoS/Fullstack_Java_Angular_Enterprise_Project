import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { OpsApi, OrderApi } from '../../core/api/apis';
import { AuthStore } from '../../core/auth/auth.store';
import { ApiError, normalizeError } from '../../core/http/api-error';
import { AuditEvent, Order, OrderHistoryItem, ReconciliationResult, Saga } from '../../core/models';
import { Toaster } from '../../core/notify/toaster';
import { ListStore } from '../../core/state/list-store';
import { ConfirmDialog, confirmDialogConfig } from '../../shared/components/confirm-dialog';
import { Column, DataTable } from '../../shared/components/data-table';
import { ErrorBanner } from '../../shared/components/feedback';
import { PageHeader } from '../../shared/components/page-header';
import { StatusChip } from '../../shared/components/status-chip';
import { StatusTimeline } from '../../shared/components/status-timeline';

interface Investigation {
  order: Order;
  saga: Saga | null;
  history: OrderHistoryItem[];
  audit: AuditEvent[];
}

@Component({
  selector: 'app-failed-orders',
  imports: [PageHeader, DataTable, StatusChip, StatusTimeline, ErrorBanner, MatButtonModule, DatePipe, RouterLink],
  template: `
    <app-page-header title="Failed order investigation" subtitle="Orders that were rejected, failed, or are stuck compensating.">
      @if (auth.isAdmin()) {
        <button mat-stroked-button type="button" (click)="reconcile()" [disabled]="reconciling()" data-testid="run-reconciliation">Run reconciliation</button>
      }
    </app-page-header>

    <app-error-banner [error]="actionError()" [retryable]="false" />

    @if (recon(); as r) {
      <section class="card recon" aria-labelledby="recon-h" role="status" data-testid="recon-result">
        <h2 id="recon-h" class="h2">Reconciliation result</h2>
        <p>Examined <strong>{{ r.examined }}</strong>, repaired <strong>{{ r.repaired }}</strong>.</p>
        @if (r.details.length) {
          <ul>@for (d of r.details; track $index) { <li>{{ d }}</li> }</ul>
        }
      </section>
    }

    <app-data-table
      caption="Failed orders"
      [columns]="columns"
      [rows]="store.items()"
      [loading]="store.loading()"
      [error]="store.error()"
      [total]="store.total()"
      [page]="store.query().page"
      [size]="store.query().size"
      [actions]="actionsTpl"
      emptyTitle="No failed orders"
      emptyMessage="Everything is flowing."
      (pageChange)="store.setPage($event.page, $event.size)"
      (retry)="store.refresh()"
    />

    <ng-template #actionsTpl let-o>
      <button mat-stroked-button type="button" (click)="investigate(o)" [attr.aria-label]="'Investigate order ' + o.id.slice(0, 8)">Investigate</button>
    </ng-template>

    <section class="section card" aria-labelledby="inv-h" tabindex="-1">
      <h2 id="inv-h" class="h2">Investigation</h2>
      <app-error-banner [error]="invError()" (retry)="reload()" />
      @if (loadingInv()) { <p role="status">Loading investigation...</p> }
      @if (inv(); as i) {
        <div class="row">
          <strong>Order <a [routerLink]="['/orders', i.order.id]" class="mono">{{ i.order.id }}</a></strong>
          <app-status-chip [status]="i.order.status" />
          @if (i.order.statusReason) { <span class="muted">{{ i.order.statusReason }}</span> }
          <button mat-flat-button type="button" (click)="retryOrder(i.order)" [disabled]="retrying()" data-testid="retry-order">Retry current step</button>
        </div>
        <app-status-timeline [status]="i.order.status" [history]="i.history" />

        @if (i.saga; as s) {
          <h3 class="h3">Saga</h3>
          <p>State <strong>{{ s.state }}</strong>, step <strong>{{ s.currentStep }}</strong>, attempts {{ s.attempts }}.@if (s.lastError) { Last error: {{ s.lastError }} }</p>
          <ol class="steps">
            @for (st of s.steps; track st.at + st.step + st.attempt) {
              <li>{{ st.at | date: 'HH:mm:ss' }} - {{ st.step }} - {{ st.status }} (attempt {{ st.attempt }}) {{ st.detail ?? '' }}</li>
            }
          </ol>
        }

        <h3 class="h3">Audit trail</h3>
        @if (i.audit.length === 0) {
          <p class="muted">No audit events found for this order.</p>
        } @else {
          <ul class="audit">
            @for (a of i.audit; track a.id) {
              <li>{{ a.occurredAt | date: 'MMM d, HH:mm:ss' }} - <strong>{{ a.action }}</strong> by {{ a.actor ?? 'system' }} {{ a.details ?? '' }}</li>
            }
          </ul>
        }
      } @else if (!loadingInv() && !invError()) {
        <p class="muted">Select an order above to see its saga, history and audit trail.</p>
      }
    </section>
  `,
  styles: `
    .h2 { font-size: 1.1rem; margin: 0 0 8px; }
    .h3 { font-size: 1rem; margin: 16px 0 4px; }
    .recon { margin-bottom: 16px; }
    .steps, .audit { margin: 0; padding-left: 20px; }
  `,
})
export class FailedOrders {
  private readonly orders = inject(OrderApi);
  private readonly ops = inject(OpsApi);
  private readonly dialog = inject(MatDialog);
  private readonly toaster = inject(Toaster);
  protected readonly auth = inject(AuthStore);


  protected readonly store = new ListStore<Order, object>((q) => this.orders.failed({ page: q.page, size: q.size }), {}, 20);

  protected readonly inv = signal<Investigation | null>(null);
  protected readonly invError = signal<ApiError | null>(null);
  protected readonly loadingInv = signal(false);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly retrying = signal(false);
  protected readonly reconciling = signal(false);
  protected readonly recon = signal<ReconciliationResult | null>(null);
  private selectedId: string | null = null;

  protected readonly columns: Column<Order>[] = [
    { key: 'id', header: 'Order', value: (o) => o.id.slice(0, 8), type: 'link', link: (o) => ['/orders', o.id] },
    { key: 'status', header: 'Status', value: (o) => o.status, type: 'status' },
    { key: 'reason', header: 'Reason', value: (o) => o.statusReason ?? '' },
    { key: 'total', header: 'Total (USD)', value: (o) => o.totalAmount, type: 'money' },
    { key: 'updated', header: 'Updated', value: (o) => o.updatedAt, type: 'date' },
  ];

  protected investigate(o: Order): void {
    this.selectedId = o.id;
    this.reload();
    setTimeout(() => document.getElementById('inv-h')?.closest('section')?.focus(), 0);
  }

  protected reload(): void {
    const id = this.selectedId;
    if (!id) return;
    this.loadingInv.set(true);
    this.invError.set(null);
    forkJoin({
      order: this.orders.get(id),
      history: this.orders.history(id),
      saga: this.orders.saga(id).pipe(catchError(() => of(null))),
      audit: this.ops.audit('order', { entityType: 'Order', entityId: id, page: 0, size: 50 }).pipe(catchError(() => of(null))),
    }).subscribe({
      next: (r) => {
        this.inv.set({ order: r.order, history: r.history, saga: r.saga, audit: r.audit?.content ?? [] });
        this.loadingInv.set(false);
      },
      error: (e: unknown) => {
        this.invError.set(normalizeError(e));
        this.loadingInv.set(false);
      },
    });
  }

  protected retryOrder(o: Order): void {
    this.dialog
      .open(
        ConfirmDialog,
        confirmDialogConfig({
          title: 'Retry this order?',
          message: 'The current saga step is re-driven. The operation is idempotent.',
          confirmLabel: 'Retry',
        }),
      )
      .afterClosed()
      .subscribe((res) => {
        if (!res) return;
        this.retrying.set(true);
        this.actionError.set(null);
        this.orders.retry(o.id).subscribe({
          next: () => {
            this.retrying.set(false);
            this.toaster.success('Retry requested.');
            this.reload();
            this.store.refresh();
          },
          error: (e: unknown) => {
            this.retrying.set(false);
            this.actionError.set(normalizeError(e));
          },
        });
      });
  }

  protected reconcile(): void {
    this.dialog
      .open(
        ConfirmDialog,
        confirmDialogConfig({
          title: 'Run reconciliation now?',
          message: 'Looks for inconsistent orders and reservations and repairs them with compensating commands.',
          confirmLabel: 'Run',
        }),
      )
      .afterClosed()
      .subscribe((res) => {
        if (!res) return;
        this.reconciling.set(true);
        this.actionError.set(null);
        this.orders.reconcile().subscribe({
          next: (r) => {
            this.reconciling.set(false);
            this.recon.set(r);
            this.store.refresh();
          },
          error: (e: unknown) => {
            this.reconciling.set(false);
            this.actionError.set(normalizeError(e));
          },
        });
      });
  }
}
