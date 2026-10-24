import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InventoryApi } from '../../core/api/apis';
import { ApiError, normalizeError } from '../../core/http/api-error';
import { Product } from '../../core/models';
import { ErrorBanner } from '../../shared/components/feedback';

/** Whole, non-zero number (positive adds stock, negative removes it). */
export const nonZeroInteger: ValidatorFn = (c: AbstractControl): ValidationErrors | null => {
  const v = c.value;
  if (v === null || v === '' || v === undefined) return null; // `required` handles empty
  const n = Number(v);
  if (!Number.isInteger(n)) return { integer: true };
  return n === 0 ? { nonZero: true } : null;
};

/** The result must not drop on-hand stock below what is already reserved (the server enforces this too). */
export function notBelowReserved(onHand: number, reserved: number): ValidatorFn {
  return (c) => {
    const n = Number(c.value);
    if (c.value === null || c.value === '' || !Number.isInteger(n)) return null;
    return onHand + n < reserved ? { belowReserved: { min: reserved - onHand } } : null;
  };
}

@Component({
  selector: 'app-stock-adjust-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, ErrorBanner],
  template: `
    <h2 mat-dialog-title id="adjust-title">Adjust stock: {{ data.product.sku }}</h2>
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <mat-dialog-content>
        <p class="muted">
          {{ data.product.name }}. On hand {{ data.product.onHand ?? 0 }}, reserved {{ data.product.reserved ?? 0 }}.
        </p>
        <app-error-banner [error]="error()" [retryable]="false" />
        <mat-form-field appearance="outline" class="full">
          <mat-label>Change (use a negative number to remove)</mat-label>
          <input matInput type="number" step="1" formControlName="delta" required />
          @if (form.controls.delta.touched) {
            @if (form.controls.delta.hasError('required')) { <mat-error>Enter a number.</mat-error> }
            @else if (form.controls.delta.hasError('integer')) { <mat-error>Whole numbers only.</mat-error> }
            @else if (form.controls.delta.hasError('nonZero')) { <mat-error>The change must not be zero.</mat-error> }
            @else if (form.controls.delta.hasError('belowReserved')) { <mat-error>Stock cannot fall below the reserved quantity.</mat-error> }
          }
        </mat-form-field>
        <mat-form-field appearance="outline" class="full">
          <mat-label>Reason</mat-label>
          <textarea matInput rows="2" formControlName="reason" maxlength="200" required></textarea>
          @if (form.controls.reason.touched && form.controls.reason.invalid) {
            <mat-error>A reason of at least 5 characters is required for the audit trail.</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button type="submit" [disabled]="busy()">{{ busy() ? 'Saving...' : 'Apply change' }}</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .full { width: 100%; min-width: min(380px, 80vw); }
  `,
})
export class StockAdjustDialog {
  protected readonly data = inject<{ product: Product }>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<StockAdjustDialog, Product>>(MatDialogRef);
  private readonly api = inject(InventoryApi);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly form = this.fb.group({
    delta: [
      null as number | null,
      [Validators.required, nonZeroInteger, notBelowReserved(this.data.product.onHand ?? 0, this.data.product.reserved ?? 0)],
    ],
    reason: ['', [Validators.required, Validators.minLength(5), Validators.maxLength(200)]],
  });

  submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    const { delta, reason } = this.form.getRawValue();
    this.api.adjustStock(this.data.product.id, Number(delta), reason.trim()).subscribe({
      next: (p) => this.ref.close(p),
      error: (e: unknown) => {
        this.busy.set(false);
        this.error.set(normalizeError(e));
      },
    });
  }
}
