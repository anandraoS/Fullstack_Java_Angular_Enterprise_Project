import { DatePipe } from '@angular/common';
import { Component, Injectable, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { Observable, catchError, forkJoin, of } from 'rxjs';
import { InventoryApi, OpsApi, OrderApi } from '../../core/api/apis';
import { AuthStore } from '../../core/auth/auth.store';
import { HealthAggregate, Order, OrderStatus } from '../../core/models';
import { ErrorBanner, LoadingOverlay } from '../../shared/components/feedback';
import { PageHeader } from '../../shared/components/page-header';
import { StatusChip } from '../../shared/components/status-chip';

export type StatusCounts = Partial<Record<OrderStatus, number>>;

/** Pure: histogram of orders by status. */
export function countByStatus(orders: readonly Order[]): StatusCounts {
  const out: StatusCounts = {};
  for (const o of orders) out[o.status] = (out[o.status] ?? 0) + 1;
  return out;
}

export interface DashboardData {
  totalOrders: number | null;
  recent: Order[];
  counts: StatusCounts;
  inStockProducts: number | null;
  lowStock: number | null;
  failedOrders: number | null;
  health: HealthAggregate | null;
}

const safe = <T>(o: Observable<T>): Observable<T | null> => o.pipe(catchError(() => of(null)));

/** KPI store: each source is fetched independently and a failure only blanks its own tile (never the whole page). */
@Injectable()
export class DashboardStore {
  private readonly orders = inject(OrderApi);
  private readonly inventory = inject(InventoryApi);
  private readonly ops = inject(OpsApi);
  private readonly auth = inject(AuthStore);

  readonly data = signal<DashboardData | null>(null);
  readonly loading = signal(false);
  readonly failed = computed(() => {
    const d = this.data();
    return d !== null && d.totalOrders === null;
  });

  load(): void {
    this.loading.set(true);
    const staff = this.auth.isStaff();
    forkJoin({
      orders: safe(this.orders.list({ page: 0, size: 100, sort: 'createdAt,desc' })),
      products: safe(this.inventory.products({ page: 0, size: 1, inStock: true })),
      low: staff ? safe(this.inventory.inventory({ page: 0, size: 1, lowStock: true })) : of(null),
      failed: staff ? safe(this.orders.failed({ page: 0, size: 1 })) : of(null),
      health: staff ? safe(this.ops.health()) : of(null),
    }).subscribe((r) => {
      this.data.set({
        totalOrders: r.orders?.totalElements ?? null,
        recent: r.orders?.content.slice(0, 5) ?? [],
        counts: countByStatus(r.orders?.content ?? []),
        inStockProducts: r.products?.totalElements ?? null,
        lowStock: r.low?.totalElements ?? null,
        failedOrders: r.failed?.totalElements ?? null,
        health: r.health,
      });
      this.loading.set(false);
    });
  }
}

@Component({
  selector: 'app-dashboard',
  imports: [PageHeader, StatusChip, ErrorBanner, LoadingOverlay, RouterLink, MatButtonModule, DatePipe],
  providers: [DashboardStore],
  template: `
    <app-page-header title="Dashboard" [subtitle]="'Signed in as ' + auth.username()">
      <a mat-flat-button routerLink="/orders/new">Create order</a>
      <button mat-stroked-button type="button" (click)="store.load()">Refresh</button>
    </app-page-header>

    @if (store.failed()) {
      <app-error-banner error="The dashboard figures could not be loaded." (retry)="store.load()" />
    }

    <div class="rel" [attr.aria-busy]="store.loading()">
      <app-loading-overlay [active]="store.loading()" />
      @if (store.data(); as d) {
        <section class="grid kpis" aria-label="Key figures">
          <div class="card kpi"><div class="value" data-testid="kpi-total">{{ fmt(d.totalOrders) }}</div><div class="label">Orders</div></div>
          <div class="card kpi"><div class="value">{{ fmt(d.counts.FULFILLED ?? 0) }}</div><div class="label">Fulfilled (latest 100)</div></div>
          <div class="card kpi"><div class="value">{{ fmt(inFlight(d)) }}</div><div class="label">In flight (latest 100)</div></div>
          <div class="card kpi"><div class="value">{{ fmt((d.counts.REJECTED ?? 0) + (d.counts.CANCELLED ?? 0) + (d.counts.FAILED ?? 0)) }}</div><div class="label">Rejected, cancelled or failed (latest 100)</div></div>
          <div class="card kpi"><div class="value">{{ fmt(d.inStockProducts) }}</div><div class="label">Products in stock</div></div>
          @if (auth.isStaff()) {
            <div class="card kpi"><div class="value" data-testid="kpi-low">{{ fmt(d.lowStock) }}</div><div class="label">Low-stock products</div></div>
            <div class="card kpi"><div class="value" data-testid="kpi-failed">{{ fmt(d.failedOrders) }}</div><div class="label">Orders needing attention</div></div>
            <div class="card kpi">
              <div class="value">
                @if (d.health) { <app-status-chip [status]="d.health.status" /> } @else { <span class="muted">n/a</span> }
              </div>
              <div class="label">Platform health</div>
            </div>
          }
        </section>

        <section class="section card" aria-labelledby="recent-h">
          <h2 id="recent-h">Recent orders</h2>
          @if (d.recent.length === 0) {
            <p class="muted">No orders yet.</p>
          } @else {
            <table class="recent">
              <caption class="sr-only">Most recent orders</caption>
              <thead><tr><th scope="col">Order</th><th scope="col">Status</th><th scope="col">Total</th><th scope="col">Created</th></tr></thead>
              <tbody>
                @for (o of d.recent; track o.id) {
                  <tr>
                    <td><a [routerLink]="['/orders', o.id]" class="mono">{{ o.id.slice(0, 8) }}</a></td>
                    <td><app-status-chip [status]="o.status" /></td>
                    <td>{{ o.totalAmount.toFixed(2) }} {{ o.currency }}</td>
                    <td>{{ o.createdAt | date: 'MMM d, HH:mm' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </section>
      }
    </div>
  `,
  styles: `
    .rel { position: relative; min-height: 120px; }
    .recent { width: 100%; border-collapse: collapse; }
    .recent th, .recent td { text-align: left; padding: 8px; border-bottom: 1px solid #e3e7ee; }
  `,
})
export class Dashboard {
  protected readonly store = inject(DashboardStore);
  protected readonly auth = inject(AuthStore);

  constructor() {
    this.store.load();
  }

  protected fmt(n: number | null): string {
    return n === null ? 'n/a' : String(n);
  }

  protected inFlight(d: DashboardData): number {
    const c = d.counts;
    return (c.CREATED ?? 0) + (c.VALIDATED ?? 0) + (c.INVENTORY_RESERVED ?? 0) + (c.CONFIRMED ?? 0) + (c.COMPENSATION_PENDING ?? 0);
  }
}
