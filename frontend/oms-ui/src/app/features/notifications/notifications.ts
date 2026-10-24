import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { debounceTime } from 'rxjs';
import { NotificationApi, NotificationFilter } from '../../core/api/apis';
import { AuthStore } from '../../core/auth/auth.store';
import { OmsNotification } from '../../core/models';
import { Toaster } from '../../core/notify/toaster';
import { ListStore } from '../../core/state/list-store';
import { normalizeError } from '../../core/http/api-error';
import { Column, DataTable } from '../../shared/components/data-table';
import { PageHeader } from '../../shared/components/page-header';

@Component({
  selector: 'app-notifications',
  imports: [PageHeader, DataTable, ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule],
  template: `
    <app-page-header title="Notifications" [subtitle]="auth.isStaff() ? 'All simulated notifications' : 'Notifications sent to you (simulated)'" />
    <form class="filters" [formGroup]="form" role="search" aria-label="Filter notifications">
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
      caption="Notifications"
      [columns]="columns"
      [rows]="store.items()"
      [loading]="store.loading()"
      [error]="store.error()"
      [total]="store.total()"
      [page]="store.query().page"
      [size]="store.query().size"
      [actions]="auth.isStaff() ? actionsTpl : null"
      emptyTitle="No notifications"
      (pageChange)="store.setPage($event.page, $event.size)"
      (retry)="store.refresh()"
    />
    <ng-template #actionsTpl let-n>
      @if (n.status === 'FAILED') {
        <button mat-stroked-button type="button" (click)="retry(n)" [attr.aria-label]="'Retry notification ' + n.id.slice(0, 8)">Retry</button>
      }
    </ng-template>
  `,
})
export class Notifications {
  private readonly api = inject(NotificationApi);
  private readonly toaster = inject(Toaster);
  protected readonly auth = inject(AuthStore);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly statuses = ['PENDING', 'SENT', 'FAILED', 'RETRYING'];
  protected readonly form = this.fb.group({ status: [''], orderId: [''] });
  protected readonly store = new ListStore<OmsNotification, NotificationFilter>(
    (q) => this.api.list({ ...q.filters, page: q.page, size: q.size, sort: q.sort }),
    {},
    20,
    'createdAt,desc',
  );

  protected readonly columns: Column<OmsNotification>[] = [
    { key: 'created', header: 'Created', value: (n) => n.createdAt, type: 'date', sortField: 'createdAt' },
    { key: 'order', header: 'Order', value: (n) => n.orderId?.slice(0, 8) ?? '', type: 'link', link: (n) => ['/orders', n.orderId ?? ''] },
    { key: 'channel', header: 'Channel', value: (n) => n.channel },
    { key: 'subject', header: 'Subject', value: (n) => n.subject },
    { key: 'status', header: 'Status', value: (n) => n.status, type: 'status' },
    { key: 'attempts', header: 'Attempts', value: (n) => n.attempts, type: 'number' },
    { key: 'error', header: 'Last error', value: (n) => n.lastError ?? '' },
  ];

  constructor() {
    this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => {
      const v = this.form.getRawValue();
      this.store.setFilters({ status: v.status, orderId: v.orderId.trim() });
    });
  }

  protected retry(n: OmsNotification): void {
    this.api.retry(n.id).subscribe({
      next: () => {
        this.toaster.success('Retry requested.');
        this.store.refresh();
      },
      error: (e: unknown) => this.toaster.error(normalizeError(e).userMessage),
    });
  }
}
