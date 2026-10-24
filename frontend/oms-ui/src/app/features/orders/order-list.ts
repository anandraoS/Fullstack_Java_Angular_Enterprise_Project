import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { debounceTime } from 'rxjs';
import { OrderApi, OrderFilter } from '../../core/api/apis';
import { AuthStore } from '../../core/auth/auth.store';
import { ORDER_STATUSES, Order } from '../../core/models';
import { ListStore } from '../../core/state/list-store';
import { Column, DataTable } from '../../shared/components/data-table';
import { PageHeader } from '../../shared/components/page-header';

/** Pure: <input type=date> value (yyyy-mm-dd) to an ISO UTC bound. */
export function dateBound(day: string, end: boolean): string {
  return day ? `${day}T${end ? '23:59:59' : '00:00:00'}Z` : '';
}

@Component({
  selector: 'app-order-list',
  imports: [PageHeader, DataTable, ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, RouterLink],
  template: `
    <app-page-header title="Orders" [subtitle]="auth.isStaff() ? 'All customer orders' : 'Your orders'">
      <a mat-flat-button routerLink="/orders/new">Create order</a>
    </app-page-header>

    <form class="filters" [formGroup]="form" role="search" aria-label="Filter orders">
      <mat-form-field appearance="outline">
        <mat-label>Status</mat-label>
        <mat-select formControlName="status">
          <mat-option value="">Any</mat-option>
          @for (s of statuses; track s) { <mat-option [value]="s">{{ s }}</mat-option> }
        </mat-select>
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Created from</mat-label>
        <input matInput type="date" formControlName="from" />
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Created to</mat-label>
        <input matInput type="date" formControlName="to" />
        @if (form.hasError('dateRange')) { <mat-error>The end date is before the start date.</mat-error> }
      </mat-form-field>
      <mat-form-field appearance="outline" class="grow">
        <mat-label>Search</mat-label>
        <input matInput type="search" formControlName="q" />
      </mat-form-field>
      <div class="btns"><button mat-button type="button" (click)="reset()">Clear filters</button></div>
    </form>

    <app-data-table
      caption="Orders"
      [columns]="columns"
      [rows]="store.items()"
      [loading]="store.loading()"
      [error]="store.error()"
      [total]="store.total()"
      [page]="store.query().page"
      [size]="store.query().size"
      [sort]="store.query().sort"
      emptyTitle="No orders found"
      emptyMessage="Create an order, or adjust the filters."
      (pageChange)="store.setPage($event.page, $event.size)"
      (sortChange)="store.toggleSort($event)"
      (retry)="store.refresh()"
    />
  `,
})
export class OrderList {
  private readonly api = inject(OrderApi);
  protected readonly auth = inject(AuthStore);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly statuses = ORDER_STATUSES;
  protected readonly form = this.fb.group(
    { status: [''], from: [''], to: [''], q: [''] },
    {
      validators: (g) => {
        const { from, to } = g.value as { from: string; to: string };
        return from && to && to < from ? { dateRange: true } : null;
      },
    },
  );

  protected readonly store = new ListStore<Order, OrderFilter>(
    (q) => this.api.list({ ...q.filters, page: q.page, size: q.size, sort: q.sort }),
    {},
    20,
    'createdAt,desc',
  );

  protected readonly columns: Column<Order>[] = [
    { key: 'id', header: 'Order', value: (o) => o.id.slice(0, 8), type: 'link', link: (o) => ['/orders', o.id] },
    { key: 'status', header: 'Status', value: (o) => o.status, type: 'status', sortField: 'status' },
    { key: 'items', header: 'Items', value: (o) => o.items.length, type: 'number' },
    { key: 'total', header: 'Total (USD)', value: (o) => o.totalAmount, type: 'money', sortField: 'totalAmount' },
    { key: 'created', header: 'Created', value: (o) => o.createdAt, type: 'date', sortField: 'createdAt' },
    { key: 'updated', header: 'Updated', value: (o) => o.updatedAt, type: 'date', sortField: 'updatedAt' },
  ];

  constructor() {
    this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => {
      if (this.form.invalid) return;
      const v = this.form.getRawValue();
      this.store.setFilters({ status: v.status, from: dateBound(v.from, false), to: dateBound(v.to, true), q: v.q.trim() });
    });
  }

  protected reset(): void {
    this.form.reset({ status: '', from: '', to: '', q: '' });
  }
}
