import { HttpTestingController } from '@angular/common/http/testing';
import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogModule, MatDialogRef, MatDialogState } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { Product } from '../../core/models';
import { API, httpTestProviders, makeProduct } from '../../testing/test-utils';
import { StockAdjustDialog } from './stock-adjust-dialog';

describe('StockAdjustDialog', () => {
  let mock: HttpTestingController;
  let dialog: MatDialog;
  let closed: Promise<Product | undefined>;
  let ref: MatDialogRef<StockAdjustDialog, Product>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [MatDialogModule], providers: httpTestProviders() });
    mock = TestBed.inject(HttpTestingController);
    dialog = TestBed.inject(MatDialog);
    ref = dialog.open<StockAdjustDialog, { product: Product }, Product>(StockAdjustDialog, {
      data: { product: makeProduct({ onHand: 100, reserved: 30 }) },
    });
    closed = firstValueFrom(ref.afterClosed());
    TestBed.inject(ApplicationRef).tick();
  });

  afterEach(() => {
    dialog.closeAll();
    mock.verify();
  });

  function fill(delta: string, reason: string): void {
    const d = document.querySelector<HTMLInputElement>('input[formcontrolname="delta"]')!;
    d.value = delta;
    d.dispatchEvent(new Event('input'));
    const r = document.querySelector<HTMLTextAreaElement>('textarea[formcontrolname="reason"]')!;
    r.value = reason;
    r.dispatchEvent(new Event('input'));
  }
  const submit = () => {
    document.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    TestBed.inject(ApplicationRef).tick();
  };

  it('requires a reason of at least 5 characters and a non-zero integer change', () => {
    fill('0', 'abc');
    submit();
    mock.expectNone(`${API}/inventory/p1/stock`);
    expect(document.body.textContent).toContain('The change must not be zero.');
    expect(document.body.textContent).toContain('at least 5 characters');
  });

  it('blocks a change that would drop stock below the reserved quantity', () => {
    fill('-71', 'cycle count correction');
    submit();
    mock.expectNone(`${API}/inventory/p1/stock`);
    expect(document.body.textContent).toContain('cannot fall below the reserved quantity');
  });

  it('PUTs delta and reason and closes with the updated product', async () => {
    fill('25', 'received shipment');
    submit();
    const req = mock.expectOne(`${API}/inventory/p1/stock`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ delta: 25, reason: 'received shipment' });
    req.flush(makeProduct({ onHand: 125 }));
    expect((await closed)?.onHand).toBe(125);
  });

  it('stays open and shows the server error', () => {
    fill('5', 'received shipment');
    submit();
    mock.expectOne(`${API}/inventory/p1/stock`).flush({ code: 'CONFLICT' }, { status: 409, statusText: 'Conflict' });
    TestBed.inject(ApplicationRef).tick();
    expect(ref.getState()).toBe(MatDialogState.OPEN);
    expect(document.querySelector('[role="alert"]')?.textContent).toContain('changed by someone else');
  });
});
