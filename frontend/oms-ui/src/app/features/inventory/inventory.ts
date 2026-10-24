import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { debounceTime } from 'rxjs';
import { InventoryApi, InventoryFilter, ReservationFilter } from '../../core/api/apis';
import { Product, Reservation } from '../../core/models';
import { Toaster } from '../../core/notify/toaster';
import { ListStore } from '../../core/state/list-store';
import { Column, DataTable } from '../../shared/components/data-table';
import { PageHeader } from '../../shared/components/page-header';
import { StockAdjustDialog } from './stock-adjust-dialog';

export const LOW_STOCK_THRESHOLD_HINT = 'Low stock is determined by the server (available below its reorder threshold).';

@Component({
  selector: 'app-inventory',
  imports: [PageHeader, DataTable, ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatCheckboxModule, MatButtonModule, MatTabsModule, MatSelectModule],
  template: `
    <app-page-header title="Inventory" subtitle="Stock levels and reservations (operators and admins)." />

    <mat-tab-group animationDuration="0ms" aria-label="Inventory views">
      <mat-tab label="Stock">
        <form class="filters tab-gap" [formGroup]="stockForm" role="search" aria-label="Filter stock">
          <mat-form-field appearance="outline" class="grow">
            <mat-label>SKU</mat-label>
            <input matInput type="search" formControlName="sku" />
          </mat-form-field>
          <div class="btns"><mat-checkbox formControlName="lowStock">Low stock only</mat-checkbox></div>
        </form>
        <p class="muted small">{{ hint }}</p>
        <app-data-table
          caption="Stock levels"
          [columns]="stockColumns"
          [rows]="stock.items()"
          [loading]="stock.loading()"
          [error]="stock.error()"
          [total]="stock.total()"
          [page]="stock.query().page"
          [size]="stock.query().size"
          [sort]="stock.query().sort"
          [actions]="adjustTpl"
          emptyTitle="No stock records match"
          (pageChange)="stock.setPage($event.page, $event.size)"
          (sortChange)="stock.toggleSort($event)"
          (retry)="stock.refresh()"
        />
      </mat-tab>
      <mat-tab label="Reservations">
        <form class="filters tab-gap" [formGroup]="resForm" role="search" aria-label="Filter reservations">
          <mat-form-field appearance="outline" class="grow">
            <mat-label>Order ID</mat-label>
            <input matInput type="search" formControlName="orderId" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Status</mat-label>
            <mat-select formControlName="status">
              <mat-option value="">Any</mat-option>
              @for (s of resStatuses; track s) { <mat-option [value]="s">{{ s }}</mat-option> }
            </mat-select>
          </mat-form-field>
        </form>
        <app-data-table
          caption="Reservations"
          [columns]="resColumns"
          [rows]="reservations.items()"
          [loading]="reservations.loading()"
          [error]="reservations.error()"
          [total]="reservations.total()"
          [page]="reservations.query().page"
          [size]="reservations.query().size"
          [sort]="reservations.query().sort"
          emptyTitle="No reservations match"
          (pageChange)="reservations.setPage($event.page, $event.size)"
          (retry)="reservations.refresh()"
        />
      </mat-tab>
    </mat-tab-group>

    <ng-template #adjustTpl let-p>
      <button mat-stroked-button type="button" [attr.aria-label]="'Adjust stock for ' + p.sku" (click)="adjust(p)">Adjust stock</button>
    </ng-template>
  `,
  styles: `
    .tab-gap { margin-top: 16px; }
    .small { font-size: 0.85rem; margin: 0 0 8px; }
  `,
})
export class Inventory {
  private readonly api = inject(InventoryApi);
  private readonly dialog = inject(MatDialog);
  private readonly toaster = inject(Toaster);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly hint = LOW_STOCK_THRESHOLD_HINT;
  protected readonly resStatuses = ['RESERVED', 'COMMITTED', 'RELEASED', 'EXPIRED', 'FAILED'];

  protected readonly stockForm = this.fb.group({ sku: [''], lowStock: [false] });
  protected readonly resForm = this.fb.group({ orderId: [''], status: [''] });

  protected readonly stock = new ListStore<Product, InventoryFilter>(
    (q) => this.api.inventory({ sku: q.filters.sku, lowStock: q.filters.lowStock ? true : '', page: q.page, size: q.size, sort: q.sort }),
    { sku: '', lowStock: false },
    20,
    'sku,asc',
  );
  protected readonly reservations = new ListStore<Reservation, ReservationFilter>(
    (q) => this.api.reservations({ ...q.filters, page: q.page, size: q.size, sort: q.sort }),
    { orderId: '', status: '' },
  );

  protected readonly stockColumns: Column<Product>[] = [
    { key: 'sku', header: 'SKU', value: (p) => p.sku, sortField: 'sku' },
    { key: 'name', header: 'Name', value: (p) => p.name },
    { key: 'onHand', header: 'On hand', value: (p) => p.onHand, type: 'number' },
    { key: 'reserved', header: 'Reserved', value: (p) => p.reserved, type: 'number' },
    { key: 'available', header: 'Available', value: (p) => p.available, type: 'number' },
  ];
  protected readonly resColumns: Column<Reservation>[] = [
    { key: 'id', header: 'Reservation', value: (r) => r.id.slice(0, 8) },
    { key: 'order', header: 'Order', value: (r) => r.orderId.slice(0, 8), type: 'link', link: (r) => ['/orders', r.orderId] },
    { key: 'status', header: 'Status', value: (r) => r.status, type: 'status' },
    { key: 'items', header: 'Items', value: (r) => r.items.map((i) => `${i.sku} x${i.quantity}`).join(', ') },
    { key: 'expires', header: 'Expires', value: (r) => r.expiresAt, type: 'date' },
    { key: 'reason', header: 'Release reason', value: (r) => r.releaseReason ?? '' },
  ];

  constructor() {
    this.stockForm.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe((v) =>
      this.stock.setFilters({ sku: (v.sku ?? '').trim(), lowStock: !!v.lowStock }),
    );
    this.resForm.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe((v) =>
      this.reservations.setFilters({ orderId: (v.orderId ?? '').trim(), status: v.status ?? '' }),
    );
  }

  protected adjust(p: Product): void {
    this.dialog
      .open(StockAdjustDialog, {
        data: { product: p },
        width: '480px',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
        restoreFocus: true,
        ariaLabelledBy: 'adjust-title',
      })
      .afterClosed()
      .subscribe((updated) => {
        if (updated) {
          this.toaster.success(`Stock updated for ${p.sku}`);
          this.stock.refresh();
        }
      });
  }
}
