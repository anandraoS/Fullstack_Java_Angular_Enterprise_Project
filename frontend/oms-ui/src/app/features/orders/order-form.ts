import { AbstractControl, FormArray, FormGroup, ValidationErrors, ValidatorFn } from '@angular/forms';
import { CreateOrderRequest } from '../../core/models';

export const MAX_ITEMS = 50;
export const MIN_QTY = 1;
export const MAX_QTY = 1000;
const POSTAL = /^[A-Za-z0-9][A-Za-z0-9 -]{2,9}$/;
const COUNTRY = /^[A-Za-z]{2}$/;
const KEY_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;

/** Integer between min and max inclusive (empty values pass; combine with Validators.required). */
export function quantityRange(min = MIN_QTY, max = MAX_QTY): ValidatorFn {
  return (c: AbstractControl): ValidationErrors | null => {
    const v = c.value;
    if (v === null || v === '' || v === undefined) return null;
    const n = Number(v);
    if (!Number.isInteger(n)) return { integer: true };
    if (n < min) return { min: { min, actual: n } };
    if (n > max) return { max: { max, actual: n } };
    return null;
  };
}

export function postalCode(c: AbstractControl): ValidationErrors | null {
  const v = (c.value ?? '') as string;
  return v === '' || POSTAL.test(v.trim()) ? null : { postalCode: true };
}

export function countryCode(c: AbstractControl): ValidationErrors | null {
  const v = (c.value ?? '') as string;
  return v === '' || COUNTRY.test(v.trim()) ? null : { countryCode: true };
}

/** FormArray validator: 1..max lines. */
export function itemCount(min = 1, max = MAX_ITEMS): ValidatorFn {
  return (c) => {
    const n = (c as FormArray).length;
    if (n < min) return { minItems: { min } };
    if (n > max) return { maxItems: { max } };
    return null;
  };
}

/** FormArray validator: no product may appear on two lines (the API rejects duplicates). */
export const uniqueProducts: ValidatorFn = (c) => {
  const seen = new Set<string>();
  const dupes = new Set<string>();
  for (const g of (c as FormArray).controls) {
    const id = (g as FormGroup).get('productId')?.value as string | undefined;
    if (!id) continue;
    if (seen.has(id)) dupes.add(id);
    seen.add(id);
  }
  return dupes.size ? { duplicateProduct: [...dupes] } : null;
};

/* ---------------- idempotency ---------------- */

export function generateIdempotencyKey(): string {
  return `oms-${crypto.randomUUID()}`; // 40 chars, [A-Za-z0-9-]
}

export function isValidIdempotencyKey(key: string): boolean {
  return KEY_PATTERN.test(key);
}

/** Stable representation of the request body so "same submission" can be detected. */
export function fingerprint(req: CreateOrderRequest): string {
  const items = [...req.items].sort((a, b) => a.productId.localeCompare(b.productId));
  return JSON.stringify({ c: req.customerId, i: items, a: req.shippingAddress });
}

/**
 * Chooses the Idempotency-Key for each submit attempt.
 * - A fresh key for every new logical submission.
 * - If the previous attempt had an UNKNOWN outcome (network error, 5xx, timeout) and the body is unchanged, the SAME
 *   key is reused, so a retry can never create a second order (server replays the original response).
 * - If the body changed, or the previous attempt got a definite answer, a new key is used (same key + different body
 *   would be rejected with 422 IDEMPOTENCY_KEY_REUSED).
 */
export class IdempotencyKeyHolder {
  private pending: { key: string; fp: string } | null = null;

  constructor(private readonly gen: () => string = generateIdempotencyKey) {}

  keyFor(req: CreateOrderRequest): string {
    const fp = fingerprint(req);
    if (this.pending && this.pending.fp === fp) return this.pending.key;
    this.pending = { key: this.gen(), fp };
    return this.pending.key;
  }

  /** The attempt got a definite answer: forget the key. */
  settle(): void {
    this.pending = null;
  }

  get current(): string | null {
    return this.pending?.key ?? null;
  }
}

/**
 * Maps server field errors (`shippingAddress.city`, `items[0].quantity`) onto controls. Returns messages that could
 * not be mapped so the caller can show them in the banner.
 */
export function applyServerErrors(form: FormGroup, errors: { field: string; message: string }[]): string[] {
  const unmatched: string[] = [];
  for (const e of errors) {
    const path = e.field.replace(/\[(\d+)\]/g, '.$1');
    const ctl = form.get(path);
    if (ctl) {
      ctl.setErrors({ ...(ctl.errors ?? {}), server: e.message });
      ctl.markAsTouched();
    } else {
      unmatched.push(`${e.field}: ${e.message}`);
    }
  }
  return unmatched;
}
