import { Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Router } from '@angular/router';
import { AuthStore } from '../../core/auth/auth.store';
import { ApiError, normalizeError } from '../../core/http/api-error';
import { ErrorBanner } from '../../shared/components/feedback';

/** Only same-app relative paths are honoured for returnUrl (prevents open redirects). */
export function safeReturnUrl(url: string | undefined | null): string {
  return url && url.startsWith('/') && !url.startsWith('//') && !url.includes('://') ? url : '/dashboard';
}

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, ErrorBanner],
  template: `
    <div class="login-wrap">
      <form class="card" [formGroup]="form" (ngSubmit)="submit()" novalidate aria-labelledby="login-title">
        <h1 id="login-title" tabindex="-1">Sign in</h1>
        <p class="muted">Development sign-in. Demo users: customer1, customer2, operator1, admin1. Ask your administrator for the demo password.</p>

        @if (reason() === 'expired') {
          <p class="notice" role="status">Your session has expired. Please sign in again.</p>
        }
        <app-error-banner [error]="error()" [retryable]="false" />

        <mat-form-field appearance="outline">
          <mat-label>Username</mat-label>
          <input matInput formControlName="username" autocomplete="username" required />
          @if (form.controls.username.touched && form.controls.username.hasError('required')) {
            <mat-error>Username is required.</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline">
          <mat-label>Password</mat-label>
          <input matInput type="password" formControlName="password" autocomplete="current-password" required />
          @if (form.controls.password.touched && form.controls.password.hasError('required')) {
            <mat-error>Password is required.</mat-error>
          }
        </mat-form-field>

        <button mat-flat-button type="submit" [disabled]="busy()">
          {{ busy() ? 'Signing in...' : 'Sign in' }}
        </button>
      </form>
    </div>
  `,
  styles: `
    .login-wrap { display: flex; justify-content: center; padding: 32px 0; }
    form { width: min(420px, 100%); display: flex; flex-direction: column; gap: 4px; }
    h1 { margin: 0 0 4px; font-size: 1.5rem; outline: none; }
    .notice { background: #fff0cc; color: #5c3b00; padding: 8px 12px; border-radius: 4px; }
  `,
})
export class Login {
  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);

  readonly returnUrl = input<string>();
  readonly reason = input<string>();

  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  protected readonly form = this.fb.group({
    username: ['', [Validators.required]],
    password: ['', [Validators.required]],
  });

  submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    const { username, password } = this.form.getRawValue();
    this.auth.login(username.trim(), password).subscribe({
      next: () => {
        this.busy.set(false);
        void this.router.navigateByUrl(safeReturnUrl(this.returnUrl()));
      },
      error: (e: unknown) => {
        this.busy.set(false);
        const err = normalizeError(e);
        // do not reveal whether the username or the password was wrong
        this.error.set(
          err.status === 401 || err.status === 403
            ? new ApiError(err.status, err.code, err.title, null, 'Sign-in failed. Check your username and password.', err.correlationId)
            : err,
        );
        this.form.controls.password.reset('');
      },
    });
  }
}
