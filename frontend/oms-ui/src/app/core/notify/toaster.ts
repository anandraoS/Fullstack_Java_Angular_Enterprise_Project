import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

/** Thin wrapper over MatSnackBar. The snack bar container is an aria-live region, so messages are announced. */
@Injectable({ providedIn: 'root' })
export class Toaster {
  private readonly bar = inject(MatSnackBar);

  success(message: string): void {
    this.open(message, 'toast-success', 4000);
  }

  info(message: string): void {
    this.open(message, 'toast-info', 4000);
  }

  warn(message: string): void {
    this.open(message, 'toast-warn', 6000);
  }

  error(message: string): void {
    this.open(message, 'toast-error', 8000);
  }

  private open(message: string, panelClass: string, duration: number): void {
    this.bar.open(message, 'Dismiss', { duration, panelClass: [panelClass], politeness: 'polite' });
  }
}
