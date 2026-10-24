import { Component, ElementRef, OnInit, computed, inject, input, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { startWith } from 'rxjs';
import { InventoryApi, OrderApi } from '../../core/api/apis';
import { AuthStore } from '../../core/auth/auth.store';
import { ApiError, normalizeError } from '../../core/http/api-error';
import { CreateOrderRequest, Customer, Product } from '../../core/models';
import { Toaster } from '../../core/notify/toaster';
import { CartStore } from '../../core/state/cart.store';
import { ErrorBanner } from '../../shared/components/feedback';
import { PageHeader } from '../../shared/components/page-header';
import {
  IdempotencyKeyHolder,
  MAX_ITEMS,
  MAX_QTY,
  MIN_QTY,
  applyServerErrors,
  countryCode,
  itemCount,
  postalCode,
  quantityRange,
  uniqueProducts,
} from './order-form';

@Component({
  selector: 'app-order-create',
  imports: [ReactiveFormsModule, PageHeader, ErrorBanner, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, RouterLink],
  template: `
    <app-page-header title="Create order" subtitle="Orders are accepted asynchronously: you will be taken to the order to watch its progress." />

    <form [formGroup]="form" (ngSubmit)="submit()" novalidate aria-label="New order" class="order-form">
      <app-error-banner [error]="error()" [retryable]="false" />
      @if (submitAttempted() && form.invalid) {
        <p class="summary" role="alert">Please fix the highlighted fields before submitting.</p>
      }
      @if (uncertain()) {
        <p class="notice" role="status">
          The server did not confirm the order. It is safe to submit again: the same request key is reused, so no duplicate order can be created.
        </p>
      }

      <section class="card" aria-labelledby="cust-h">
        <h2 id="cust-h">Customer</h2>
        @if (auth.isStaff()) {
          <mat-form-field appearance="outline" class="wide">
            <mat-label>Customer</mat-label>
            <mat-select formControlName="customerId" required data-testid="customer-select">
              @for (c of customers(); track c.id) {
                <mat-option [value]="c.id">{{ c.name }} ({{ c.email }})</mat-option>
              }
            </mat-select>
            @if (form.controls.customerId.touched && form.controls.customerId.hasError('required')) {
              <mat-error>Select a customer.</mat-error>
            }
          </mat-form-field>
        } @else {
          <p>Ordering as <strong>{{ auth.username() }}</strong> (your account customer).</p>
        }
      </section>

      <section class="card" aria-labelledby="items-h">
        <h2 id="items-h">Items</h2>
        @if (productsError()) {
          <app-error-banner [error]="productsError()" (retry)="loadProducts()" />
        }
        <div formArrayName="items">
          @for (row of items.controls; track row; let i = $index) {
            <div class="item-row" [formGroupName]="i" role="group" [attr.aria-label]="'Item ' + (i + 1)">
              <mat-form-field appearance="outline" class="prod">
                <mat-label>Product</mat-label>
                <mat-select formControlName="productId" required [attr.data-testid]="'product-' + i">
                  @for (p of products(); track p.id) {
                    <mat-option [value]="p.id">{{ p.sku }} - {{ p.name }} ({{ p.available }} available)</mat-option>
                  }
                </mat-select>
                @if (row.controls['productId'].touched && row.controls['productId'].hasError('required')) {
                  <mat-error>Select a product.</mat-error>
                }
              </mat-form-field>
              <mat-form-field appearance="outline" class="qty">
                <mat-label>Quantity</mat-label>
                <input matInput type="number" step="1" [min]="minQty" [max]="maxQty" formControlName="quantity" required [attr.data-testid]="'qty-' + i" />
                @if (row.controls['quantity'].touched && row.controls['quantity'].invalid) {
                  <mat-error>Enter a whole number from {{ minQty }} to {{ maxQty }}.</mat-error>
                }
              </mat-form-field>
              <div class="line-total" aria-live="polite">{{ lineTotal(i) }}</div>
              <button mat-button type="button" (click)="removeRow(i)" [disabled]="items.length === 1" [attr.aria-label]="'Remove item ' + (i + 1)">Remove</button>
            </div>
          }
        </div>
        @if (items.errors?.['duplicateProduct']) {
          <p class="field-error" role="alert">The same product appears on more than one line. Combine them into one line.</p>
        }
        @if (items.errors?.['maxItems']) {
          <p class="field-error" role="alert">An order can have at most {{ maxItems }} lines.</p>
        }
        <button mat-stroked-button type="button" #addBtn (click)="addRow()" [disabled]="items.length >= maxItems">Add item</button>
        <p class="total" aria-live="polite">Estimated total: <strong data-testid="est-total">{{ total() }}</strong></p>
      </section>

      <section class="card" aria-labelledby="ship-h" formGroupName="shippingAddress">
        <h2 id="ship-h">Shipping address</h2>
        <div class="addr">
          <mat-form-field appearance="outline" class="wide">
            <mat-label>Address line</mat-label>
            <input matInput formControlName="line1" autocomplete="address-line1" maxlength="200" required />
            @if (addr('line1').touched && addr('line1').invalid) { <mat-error>Address line is required.</mat-error> }
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>City</mat-label>
            <input matInput formControlName="city" autocomplete="address-level2" maxlength="100" required />
            @if (addr('city').touched && addr('city').invalid) { <mat-error>City is required.</mat-error> }
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Postal code</mat-label>
            <input matInput formControlName="postalCode" autocomplete="postal-code" maxlength="10" required />
            @if (addr('postalCode').touched && addr('postalCode').invalid) { <mat-error>Enter a valid postal code (3-10 letters, digits, space or dash).</mat-error> }
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Country (2-letter code)</mat-label>
            <input matInput formControlName="country" autocomplete="country" maxlength="2" required />
            @if (addr('country').touched && addr('country').invalid) { <mat-error>Use a 2-letter country code, for example US.</mat-error> }
          </mat-form-field>
        </div>
      </section>

      <div class="row actions">
        <button mat-flat-button type="submit" [disabled]="submitting()">{{ submitting() ? 'Submitting...' : 'Place order' }}</button>
        <a mat-button routerLink="/orders">Cancel</a>
        @if (keyHolder.current; as k) {
          <span class="muted mono" data-testid="idempotency-key">Request key: {{ k }}</span>
        }
      </div>
    </form>
  `,
  styles: `
    .order-form { display: flex; flex-direction: column; gap: 16px; }
    h2 { font-size: 1.1rem; margin: 0 0 8px; }
    .item-row { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-start; }
    .prod { flex: 3 1 280px; }
    .qty { flex: 1 1 110px; max-width: 160px; }
    .line-total { flex: 0 0 100px; padding-top: 18px; text-align: right; font-variant-numeric: tabular-nums; }
    .addr { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 0 12px; }
    .addr .wide, .wide { grid-column: 1 / -1; width: 100%; }
    .summary, .field-error { color: #7a0a0a; font-weight: 600; }
    .notice { background: #fff0cc; color: #5c3b00; padding: 8px 12px; border-radius: 4px; }
    .total { text-align: right; }
    .actions { margin-bottom: 24px; }
  `,
})
export class OrderCreate implements OnInit {
  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly orders = inject(OrderApi);
  private readonly inventory = inject(InventoryApi);
  private readonly router = inject(Router);
  private readonly toaster = inject(Toaster);
  private readonly cart = inject(CartStore);
  protected readonly auth = inject(AuthStore);

  readonly fromCart = input<string>();

  protected readonly minQty = MIN_QTY;
  protected readonly maxQty = MAX_QTY;
  protected readonly maxItems = MAX_ITEMS;

  protected readonly products = signal<Product[]>([]);
  protected readonly productsError = signal<ApiError | null>(null);
  protected readonly customers = signal<Customer[]>([]);
  protected readonly error = signal<ApiError | string | null>(null);
  readonly submitting = signal(false);
  protected readonly submitAttempted = signal(false);
  protected readonly uncertain = signal(false);

  readonly keyHolder = new IdempotencyKeyHolder();
  private readonly addBtn = viewChild<ElementRef<HTMLButtonElement>>('addBtn');

  readonly form = this.fb.group({
    customerId: ['', Validators.required],
    items: this.fb.array([this.newRow()], [itemCount(), uniqueProducts]),
    shippingAddress: this.fb.group({
      line1: ['', [Validators.required, Validators.maxLength(200)]],
      city: ['', [Validators.required, Validators.maxLength(100)]],
      postalCode: ['', [Validators.required, postalCode]],
      country: ['US', [Validators.required, countryCode]],
    }),
  });

  private readonly formValue = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly total = computed(() => {
    this.formValue();
    this.products();
    let sum = 0;
    for (let i = 0; i < this.items.length; i++) sum += this.lineAmount(i);
    return `${sum.toFixed(2)} USD`;
  });

  get items(): FormArray<FormGroup<{ productId: FormControl<string>; quantity: FormControl<number | null> }>> {
    return this.form.controls.items as unknown as FormArray<FormGroup<{ productId: FormControl<string>; quantity: FormControl<number | null> }>>;
  }

  ngOnInit(): void {
    if (this.auth.isCustomer()) {
      this.form.controls.customerId.setValue(this.auth.customerId() ?? '');
      this.form.controls.customerId.disable();
    } else {
      this.orders.customers({ page: 0, size: 100, sort: 'name,asc' }).subscribe({
        next: (p) => this.customers.set(p.content),
        error: (e: unknown) => this.error.set(normalizeError(e)),
      });
    }
    this.loadProducts();
    if (this.fromCart() && this.cart.lines().length > 0) {
      this.items.clear();
      for (const l of this.cart.lines()) this.items.push(this.newRow(l.productId, l.quantity));
    }
  }

  protected loadProducts(): void {
    this.productsError.set(null);
    this.inventory.products({ page: 0, size: 100, sort: 'sku,asc' }).subscribe({
      next: (p) => this.products.set(p.content.filter((x) => x.active !== false)),
      error: (e: unknown) => this.productsError.set(normalizeError(e)),
    });
  }

  newRow(productId = '', quantity: number | null = 1) {
    return this.fb.group({
      productId: [productId, Validators.required],
      quantity: new FormControl<number | null>(quantity, { validators: [Validators.required, quantityRange()] }),
    });
  }

  protected addr(name: string) {
    return this.form.controls.shippingAddress.get(name) as FormControl;
  }

  addRow(): void {
    if (this.items.length >= MAX_ITEMS) return;
    this.items.push(this.newRow());
    setTimeout(() => {
      const selects = document.querySelectorAll<HTMLElement>('mat-select[data-testid^="product-"]');
      selects[selects.length - 1]?.focus();
    }, 0);
  }

  removeRow(i: number): void {
    if (this.items.length <= 1) return;
    this.items.removeAt(i);
    setTimeout(() => this.addBtn()?.nativeElement.focus(), 0); // keep keyboard focus on a stable control
  }

  private lineAmount(i: number): number {
    const row = this.items.at(i)?.getRawValue();
    const p = this.products().find((x) => x.id === row?.productId);
    return p ? p.price * Number(row?.quantity ?? 0) || 0 : 0;
  }

  protected lineTotal(i: number): string {
    this.formValue();
    const a = this.lineAmount(i);
    return a ? a.toFixed(2) : '';
  }

  buildRequest(): CreateOrderRequest {
    const v = this.form.getRawValue();
    return {
      customerId: v.customerId,
      items: v.items.map((r) => ({ productId: r.productId, quantity: Number(r.quantity) })),
      shippingAddress: {
        line1: v.shippingAddress.line1.trim(),
        city: v.shippingAddress.city.trim(),
        postalCode: v.shippingAddress.postalCode.trim(),
        country: v.shippingAddress.country.trim().toUpperCase(),
      },
    };
  }

  submit(): void {
    if (this.submitting()) return; // guards double-click / Enter-key repeats
    this.submitAttempted.set(true);
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      setTimeout(() => document.querySelector<HTMLElement>('form .ng-invalid.mat-mdc-select, form input.ng-invalid')?.focus(), 0);
      return;
    }
    const body = this.buildRequest();
    const key = this.keyHolder.keyFor(body);
    this.submitting.set(true);
    this.error.set(null);
    this.orders.create(body, key).subscribe({
      next: (res) => {
        this.submitting.set(false);
        this.keyHolder.settle();
        this.cart.clear();
        this.toaster.success(res.replay ? 'This order was already accepted; showing the original.' : 'Order accepted. Processing has started.');
        void this.router.navigate(['/orders', res.order.id]);
      },
      error: (e: unknown) => {
        this.submitting.set(false);
        const err = normalizeError(e);
        if (err.status === 0 || err.status >= 500) {
          this.uncertain.set(true); // outcome unknown: keep the key so a retry is idempotent
          this.error.set(err);
        } else {
          this.uncertain.set(false);
          this.keyHolder.settle();
          const unmatched = applyServerErrors(this.form, err.fieldErrors);
          this.error.set(unmatched.length || !err.fieldErrors.length ? err : 'Some fields need attention.');
        }
      },
    });
  }
}
