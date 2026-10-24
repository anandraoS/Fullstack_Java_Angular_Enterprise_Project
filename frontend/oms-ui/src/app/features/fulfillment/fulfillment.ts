import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { debounceTime } from 'rxjs';
import { FulfillmentApi, FulfillmentFilter } from '../../core/api/apis';
import { normalizeError } from '../../core/http/api-error';
import { Fulfillment } from '../../core/models';
import { Toaster } from '../../core/notify/toaster';
import { ListStore } from '../../core/state/list-store';
import { Column, DataTable } from '../../shared/components/data-table';
import { PageHeader } from '../../shared/components/page-header';

@Component({
  selector: 'app-fulfillment',
  imports: [PageHeader, DataTable, ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule],
  template: `
    <app-page-header title="Fulfillment" subtitle="Simulated picking, packing and shipping." />
    <form class="filters" [formGroup]="form" role="search" aria-label="Filter fulfillments">
      <mat-form-field appearance="outline">
        <mat-label>Status</mat-label>
        <mat-select formControlName="status">
          <mat-option value="">Any</mat-option>
          @for (s of statuses; track s) { <mat-option [value]="s">{{ s }}</mat-option> }
        </mat-select>
      </mat-form-field>
      <mat-form-field appearance="outline" class="grow">
        <mat-label>Order ID</mat-label>
        <input matInput type="search" formControlName="orderId" />
      </mat-form-field>
    </form>
    <app-data-table
      caption="Fulfillments"
      [columns]="columns"
      [rows]="store.items()"
      [loading]="store.loading()"
      [error]="store.error()"
      [total]="store.total()"
      [page]="store.query().page"
      [size]="store.query().size"
      [actions]="actionsTpl"
      emptyTitle="No fulfillments"
      (pageChange)="store.setPage($event.page, $event.size)"
      (retry)="store.refresh()"
    />
    <ng-template #actionsTpl let-f>
      @if (f.status === 'FAILED') {
        <button mat-stroked-button type="button" (click)="retry(f)" [attr.aria-label]="'Retry fulfillment ' + f.id.slice(0, 8)">Retry</button>
      }
    </ng-template>
  `,
})
export class FulfillmentView {
  private readonly api = inject(FulfillmentApi);
  private readonly toaster = inject(Toaster);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly statuses = ['PENDING', 'PICKING', 'PACKED', 'SHIPPED', 'COMPLETED', 'FAILED'];
  protected readonly form = this.fb.group({ status: [''], orderId: [''] });
  protected readonly store = new ListStore<Fulfillment, FulfillmentFilter>(
    (q) => this.api.list({ ...q.filters, page: q.page, size: q.size, sort: q.sort }),
    {},
    20,
    'createdAt,desc',
  );

  protected readonly columns: Column<Fulfillment>[] = [
    { key: 'order', header: 'Order', value: (f) => f.orderId.slice(0, 8), type: 'link', link: (f) => ['/orders', f.orderId] },
    { key: 'status', header: 'Status', value: (f) => f.status, type: 'status' },
    { key: 'tracking', header: 'Tracking', value: (f) => f.trackingNumber ?? '' },
    { key: 'attempts', header: 'Attempts', value: (f) => f.attempts, type: 'number' },
    { key: 'failure', header: 'Failure reason', value: (f) => f.failureReason ?? '' },
    { key: 'updated', header: 'Updated', value: (f) => f.updatedAt, type: 'date', sortField: 'updatedAt' },
  ];

  constructor() {
    this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => {
      const v = this.form.getRawValue();
      this.store.setFilters({ status: v.status, orderId: v.orderId.trim() });
    });
  }

  protected retry(f: Fulfillment): void {
    this.api.retry(f.id).subscribe({
      next: () => {
        this.toaster.success('Retry requested.');
        this.store.refresh();
      },
      error: (e: unknown) => this.toaster.error(normalizeError(e).userMessage),
    });
  }
}
