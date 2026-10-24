import { Component, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface ConfirmDialogData {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** when set, a mandatory reason textarea is shown */
  reasonLabel?: string;
  minReasonLength?: number;
  destructive?: boolean;
}

export interface ConfirmDialogResult {
  reason: string;
}

/** Open with: dialog.open(ConfirmDialog, confirmDialogConfig(data)). Focus moves to the first control and is restored on close. */
@Component({
  selector: 'app-confirm-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, ReactiveFormsModule],
  template: `
    <h2 mat-dialog-title id="confirm-title">{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      @if (data.reasonLabel) {
        <mat-form-field appearance="outline" class="full">
          <mat-label>{{ data.reasonLabel }}</mat-label>
          <textarea matInput rows="3" [formControl]="reason" maxlength="500" required></textarea>
          @if (reason.invalid && reason.touched) {
            <mat-error>Enter at least {{ minLen }} characters.</mat-error>
          }
        </mat-form-field>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" (click)="cancel()">{{ data.cancelLabel ?? 'Cancel' }}</button>
      <button mat-flat-button type="button" [color]="data.destructive ? 'warn' : 'primary'" (click)="confirm()">
        {{ data.confirmLabel ?? 'Confirm' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .full { width: 100%; min-width: min(420px, 80vw); }
  `,
})
export class ConfirmDialog {
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ConfirmDialog, ConfirmDialogResult>>(MatDialogRef);
  protected readonly minLen = this.data.minReasonLength ?? 3;
  protected readonly reason = new FormControl('', {
    nonNullable: true,
    validators: this.data.reasonLabel ? [Validators.required, Validators.minLength(this.minLen)] : [],
  });

  cancel(): void {
    this.ref.close();
  }

  confirm(): void {
    this.reason.markAsTouched();
    if (this.reason.invalid) return;
    this.ref.close({ reason: this.reason.value.trim() });
  }
}

export function confirmDialogConfig(data: ConfirmDialogData) {
  return {
    data,
    width: '480px',
    maxWidth: '95vw',
    autoFocus: 'first-tabbable' as const,
    restoreFocus: true,
    role: 'alertdialog' as const,
    ariaLabelledBy: 'confirm-title',
  };
}
