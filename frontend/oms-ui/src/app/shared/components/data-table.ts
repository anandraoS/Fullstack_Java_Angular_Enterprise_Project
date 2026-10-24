import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, TemplateRef, computed, input, output } from '@angular/core';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTableModule } from '@angular/material/table';
import { RouterLink } from '@angular/router';
import { ApiError } from '../../core/http/api-error';
import { EmptyState, ErrorBanner, LoadingOverlay } from './feedback';
import { StatusChip } from './status-chip';

export interface Column<T> {
  key: string;
  header: string;
  value: (row: T) => string | number | null | undefined;
  type?: 'text' | 'status' | 'date' | 'money' | 'link' | 'number';
  /** server sort field; presence makes the header a sort button */
  sortField?: string;
  /** router link for type 'link' */
  link?: (row: T) => string[];
}

/** Table with loading overlay, error banner, empty state, sortable headers and a paginator. */
@Component({
  selector: 'app-data-table',
  imports: [MatTableModule, MatPaginatorModule, StatusChip, ErrorBanner, EmptyState, LoadingOverlay, RouterLink, NgTemplateOutlet, DatePipe],
  template: `
    <app-error-banner [error]="error()" (retry)="retry.emit()" />
    <div class="wrap" [attr.aria-busy]="loading()">
      <app-loading-overlay [active]="loading()" />
      @if (!error() && !loading() && rows().length === 0) {
        <app-empty-state [title]="emptyTitle()" [message]="emptyMessage()" />
      } @else if (rows().length > 0) {
        <div class="scroll" tabindex="0" role="region" [attr.aria-label]="caption() + ' (scrollable)'">
          <table mat-table [dataSource]="rows()" [attr.aria-label]="caption()">
            @for (col of columns(); track col.key) {
              <ng-container [matColumnDef]="col.key">
                <th mat-header-cell *matHeaderCellDef [attr.aria-sort]="ariaSort(col)" [class.num]="col.type === 'money' || col.type === 'number'">
                  @if (col.sortField) {
                    <button type="button" class="sort" (click)="sortChange.emit(col.sortField)">
                      {{ col.header }}<span aria-hidden="true">{{ arrow(col) }}</span>
                    </button>
                  } @else {
                    {{ col.header }}
                  }
                </th>
                <td mat-cell *matCellDef="let row" [class.num]="col.type === 'money' || col.type === 'number'">
                  @switch (col.type) {
                    @case ('status') {
                      <app-status-chip [status]="'' + col.value(row)" />
                    }
                    @case ('link') {
                      <a [routerLink]="col.link ? col.link(row) : []">{{ col.value(row) }}</a>
                    }
                    @case ('date') {
                      {{ col.value(row) ? (col.value(row) | date: 'MMM d, y, HH:mm:ss') : '' }}
                    }
                    @case ('money') {
                      {{ money(col.value(row)) }}
                    }
                    @default {
                      {{ col.value(row) }}
                    }
                  }
                </td>
              </ng-container>
            }
            @if (actions()) {
              <ng-container matColumnDef="__actions">
                <th mat-header-cell *matHeaderCellDef>Actions</th>
                <td mat-cell *matCellDef="let row">
                  <ng-container *ngTemplateOutlet="actions()!; context: { $implicit: row }" />
                </td>
              </ng-container>
            }
            <tr mat-header-row *matHeaderRowDef="displayed()"></tr>
            <tr mat-row *matRowDef="let row; columns: displayed()"></tr>
          </table>
        </div>
      }
    </div>
    @if (paginate()) {
      <mat-paginator
        [length]="total()"
        [pageIndex]="page()"
        [pageSize]="size()"
        [pageSizeOptions]="[10, 20, 50, 100]"
        [showFirstLastButtons]="true"
        aria-label="Select page"
        (page)="onPage($event)"
      />
    }
  `,
  styles: `
    .wrap { position: relative; min-height: 80px; }
    .scroll { overflow-x: auto; }
    table { width: 100%; }
    .num { text-align: right; font-variant-numeric: tabular-nums; }
    .sort { background: none; border: 0; font: inherit; font-weight: 600; cursor: pointer; padding: 4px 0; color: inherit; }
    .sort:focus-visible { outline: 2px solid #0b3d91; outline-offset: 2px; }
  `,
})
export class DataTable<T> {
  readonly columns = input.required<Column<T>[]>();
  readonly rows = input<T[]>([]);
  readonly loading = input(false);
  readonly error = input<ApiError | null>(null);
  readonly total = input(0);
  readonly page = input(0);
  readonly size = input(20);
  readonly sort = input('');
  readonly caption = input('Data table');
  readonly paginate = input(true);
  readonly emptyTitle = input('No results');
  readonly emptyMessage = input('Try changing the filters.');
  readonly actions = input<TemplateRef<{ $implicit: T }> | null>(null);

  readonly pageChange = output<{ page: number; size: number }>();
  readonly sortChange = output<string>();
  readonly retry = output<void>();

  readonly displayed = computed(() => [...this.columns().map((c) => c.key), ...(this.actions() ? ['__actions'] : [])]);

  ariaSort(col: Column<T>): 'ascending' | 'descending' | 'none' | null {
    if (!col.sortField) return null;
    const [f, dir] = this.sort().split(',');
    if (f !== col.sortField) return 'none';
    return dir === 'desc' ? 'descending' : 'ascending';
  }

  arrow(col: Column<T>): string {
    const s = this.ariaSort(col);
    return s === 'ascending' ? ' ▲' : s === 'descending' ? ' ▼' : '';
  }

  money(v: unknown): string {
    return typeof v === 'number' ? v.toFixed(2) : v == null ? '' : String(v);
  }

  onPage(e: PageEvent): void {
    this.pageChange.emit({ page: e.pageIndex, size: e.pageSize });
  }
}
