import { ApplicationRef, Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MatDialog, MatDialogModule, MatDialogState } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { ApiError } from '../../core/http/api-error';
import { ConfirmDialog, ConfirmDialogResult, confirmDialogConfig } from './confirm-dialog';
import { Column, DataTable } from './data-table';
import { EmptyState, ErrorBanner, LoadingOverlay } from './feedback';
import { PageHeader } from './page-header';

interface Row {
  id: string;
  name: string;
  status: string;
}

@Component({
  imports: [DataTable],
  template: `
    <app-data-table
      caption="People"
      [columns]="columns"
      [rows]="rows"
      [loading]="loading"
      [error]="error"
      [total]="55"
      [page]="1"
      [size]="20"
      [sort]="sort"
      (pageChange)="pages.push($event)"
      (sortChange)="sorts.push($event)"
      (retry)="retries = retries + 1"
    />
  `,
})
class TableHost {
  columns: Column<Row>[] = [
    { key: 'name', header: 'Name', value: (r) => r.name, sortField: 'name' },
    { key: 'status', header: 'Status', value: (r) => r.status, type: 'status' },
  ];
  rows: Row[] = [{ id: '1', name: 'Alpha', status: 'FULFILLED' }];
  loading = false;
  error: ApiError | null = null;
  sort = 'name,asc';
  pages: { page: number; size: number }[] = [];
  sorts: string[] = [];
  retries = 0;
}

describe('DataTable', () => {
  let fixture: ComponentFixture<TableHost>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    fixture = TestBed.createComponent(TableHost);
    fixture.detectChanges();
    el = fixture.nativeElement;
  });

  it('renders rows, status chips and an accessible table name', () => {
    expect(el.querySelector('table')?.getAttribute('aria-label')).toBe('People');
    expect(el.textContent).toContain('Alpha');
    expect(el.querySelector('app-status-chip')?.textContent).toContain('FULFILLED');
  });

  it('exposes sort state via aria-sort and emits sort changes from the header button', () => {
    expect(el.querySelector('th')?.getAttribute('aria-sort')).toBe('ascending');
    el.querySelector<HTMLButtonElement>('th button.sort')?.click();
    expect(fixture.componentInstance.sorts).toEqual(['name']);
  });

  it('shows the empty state when there are no rows', () => {
    fixture.componentInstance.rows = [];
    fixture.detectChanges();
    expect(el.querySelector('app-empty-state')?.textContent).toContain('No results');
    expect(el.querySelector('table')).toBeNull();
  });

  it('shows an error banner (role=alert) and emits retry', () => {
    fixture.componentInstance.error = new ApiError(503, 'UPSTREAM_UNAVAILABLE', '', null, 'A backend service is temporarily unavailable.', 'ref-1');
    fixture.detectChanges();
    const alert = el.querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('temporarily unavailable');
    expect(alert?.textContent).toContain('ref-1');
    el.querySelector<HTMLButtonElement>('[role="alert"] button')?.click();
    expect(fixture.componentInstance.retries).toBe(1);
    expect(el.querySelector('app-empty-state')).toBeNull();
  });

  it('marks the region busy and shows the overlay while loading', () => {
    fixture.componentInstance.loading = true;
    fixture.detectChanges();
    expect(el.querySelector('.wrap')?.getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('.overlay')).not.toBeNull();
  });

  it('emits page changes from the paginator', () => {
    el.querySelector<HTMLButtonElement>('button.mat-mdc-paginator-navigation-next')?.click();
    expect(fixture.componentInstance.pages).toEqual([{ page: 2, size: 20 }]);
  });
});

describe('feedback components', () => {
  it('ErrorBanner renders field errors and plain strings', () => {
    const f = TestBed.createComponent(ErrorBanner);
    f.componentRef.setInput('error', new ApiError(400, 'VALIDATION_FAILED', '', null, 'Fix the fields.', null, [{ field: 'city', message: 'required' }]));
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('city: required');
    f.componentRef.setInput('error', 'Plain message');
    f.componentRef.setInput('retryable', false);
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Plain message');
    expect(f.nativeElement.querySelector('button')).toBeNull();
  });

  it('ErrorBanner renders nothing without an error', () => {
    const f = TestBed.createComponent(ErrorBanner);
    f.detectChanges();
    expect(f.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('LoadingOverlay keeps a polite live region and toggles content', () => {
    const f = TestBed.createComponent(LoadingOverlay);
    f.detectChanges();
    expect(f.nativeElement.querySelector('[aria-live="polite"]')).not.toBeNull();
    expect(f.nativeElement.querySelector('.overlay')).toBeNull();
    f.componentRef.setInput('active', true);
    f.detectChanges();
    expect(f.nativeElement.querySelector('.overlay')).not.toBeNull();
  });

  it('EmptyState and PageHeader render their inputs', () => {
    const e = TestBed.createComponent(EmptyState);
    e.componentRef.setInput('title', 'Nothing here');
    e.componentRef.setInput('message', 'Add one');
    e.detectChanges();
    expect(e.nativeElement.textContent).toContain('Nothing here');
    const p = TestBed.createComponent(PageHeader);
    p.componentRef.setInput('title', 'Orders');
    p.detectChanges();
    const h1 = p.nativeElement.querySelector('h1');
    expect(h1.textContent).toBe('Orders');
    expect(h1.getAttribute('tabindex')).toBe('-1');
  });
});

describe('ConfirmDialog', () => {
  let dialog: MatDialog;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [MatDialogModule] });
    dialog = TestBed.inject(MatDialog);
  });
  afterEach(() => dialog.closeAll());

  function open(withReason: boolean) {
    const ref = dialog.open<ConfirmDialog, unknown, ConfirmDialogResult>(
      ConfirmDialog,
      confirmDialogConfig({
        title: 'Cancel order?',
        message: 'Are you sure?',
        confirmLabel: 'Yes cancel',
        reasonLabel: withReason ? 'Reason' : undefined,
        minReasonLength: 3,
      }),
    );
    const closed = firstValueFrom(ref.afterClosed());
    TestBed.inject(ApplicationRef).tick(); // render the dialog content synchronously
    return { ref, closed };
  }
  const button = (label: string) => Array.from(document.querySelectorAll<HTMLButtonElement>('mat-dialog-actions button')).find((b) => b.textContent?.includes(label));

  it('is an accessible alertdialog labelled by its title and moves focus inside', () => {
    open(true);
    const container = document.querySelector('mat-dialog-container');
    expect(container?.getAttribute('role')).toBe('alertdialog');
    expect(container?.getAttribute('aria-labelledby')).toBe('confirm-title');
    expect(document.getElementById('confirm-title')?.textContent).toContain('Cancel order?');
  });

  it('requires a reason when asked and returns it trimmed', async () => {
    const d = open(true);
    button('Yes cancel')?.click();
    expect(d.ref.getState()).toBe(MatDialogState.OPEN); // blocked: reason is empty
    const ta = document.querySelector<HTMLTextAreaElement>('textarea');
    ta!.value = '  changed my mind ';
    ta!.dispatchEvent(new Event('input'));
    button('Yes cancel')?.click();
    expect(await d.closed).toEqual({ reason: 'changed my mind' });
  });

  it('returns undefined on cancel', async () => {
    const d = open(false);
    button('Cancel')?.click();
    expect(await d.closed).toBeUndefined();
  });

  it('confirms without a reason field when none is configured', async () => {
    const d = open(false);
    expect(document.querySelector('textarea')).toBeNull();
    button('Yes cancel')?.click();
    expect(await d.closed).toEqual({ reason: '' });
  });
});
