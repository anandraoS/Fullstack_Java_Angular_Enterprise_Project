import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { debounceTime } from 'rxjs';
import { OpsApi } from '../../core/api/apis';
import { AuditEvent, SERVICES, ServiceName } from '../../core/models';
import { ListStore } from '../../core/state/list-store';
import { Column, DataTable } from '../../shared/components/data-table';
import { PageHeader } from '../../shared/components/page-header';

interface AuditFilters {
  service: ServiceName;
  entityType: string;
  entityId: string;
}

@Component({
  selector: 'app-audit',
  imports: [PageHeader, DataTable, ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <app-page-header title="Audit history" subtitle="Who changed what, per service." />
    <form class="filters" [formGroup]="form" role="search" aria-label="Filter audit events">
      <mat-form-field appearance="outline">
        <mat-label>Service</mat-label>
        <mat-select formControlName="service" data-testid="audit-service">
          @for (s of services; track s) { <mat-option [value]="s">{{ s }}</mat-option> }
        </mat-select>
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Entity type</mat-label>
        <input matInput formControlName="entityType" placeholder="e.g. Order, Product" />
      </mat-form-field>
      <mat-form-field appearance="outline" class="grow">
        <mat-label>Entity ID</mat-label>
        <input matInput formControlName="entityId" />
      </mat-form-field>
    </form>
    <app-data-table
      caption="Audit events"
      [columns]="columns"
      [rows]="store.items()"
      [loading]="store.loading()"
      [error]="store.error()"
      [total]="store.total()"
      [page]="store.query().page"
      [size]="store.query().size"
      emptyTitle="No audit events"
      (pageChange)="store.setPage($event.page, $event.size)"
      (retry)="store.refresh()"
    />
  `,
})
export class Audit {
  private readonly api = inject(OpsApi);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly services = SERVICES;

  protected readonly form = this.fb.group({ service: ['order' as ServiceName], entityType: [''], entityId: [''] });
  protected readonly store = new ListStore<AuditEvent, AuditFilters>(
    (q) =>
      this.api.audit(q.filters.service, {
        entityType: q.filters.entityType,
        entityId: q.filters.entityId,
        page: q.page,
        size: q.size,
      }),
    { service: 'order', entityType: '', entityId: '' },
    20,
  );

  protected readonly columns: Column<AuditEvent>[] = [
    { key: 'at', header: 'When', value: (a) => a.occurredAt, type: 'date' },
    { key: 'type', header: 'Entity', value: (a) => a.entityType },
    { key: 'id', header: 'Entity ID', value: (a) => a.entityId },
    { key: 'action', header: 'Action', value: (a) => a.action },
    { key: 'actor', header: 'Actor', value: (a) => a.actor ?? 'system' },
    { key: 'details', header: 'Details', value: (a) => a.details ?? '' },
  ];

  constructor() {
    this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => {
      const v = this.form.getRawValue();
      this.store.setFilters({ service: v.service, entityType: v.entityType.trim(), entityId: v.entityId.trim() });
    });
  }
}
