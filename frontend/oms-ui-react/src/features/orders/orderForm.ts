import { z } from 'zod';
import type { CreateOrderRequest } from '../../models';

export const MAX_ITEMS = 50;
export const MIN_QTY = 1;
export const MAX_QTY = 1000;
const POSTAL = /^[A-Za-z0-9][A-Za-z0-9 -]{2,9}$/;
const COUNTRY = /^[A-Za-z]{2}$/;
const KEY_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;

export const QTY_MESSAGE = `Enter a whole number from ${MIN_QTY} to ${MAX_QTY}.`;

/** Quantity is held as the raw input string; valid = whole number in range. */
export function isValidQuantity(v: string): boolean {
  if (v.trim() === '') return false;
  const n = Number(v);
  return Number.isInteger(n) && n >= MIN_QTY && n <= MAX_QTY;
}

export const duplicateProductMessage = 'The same product appears on more than one line. Combine them into one line.';

/** Pure: product ids that appear on more than one line (the API rejects duplicates). */
export function duplicateProductIds(items: readonly { productId: string }[]): string[] {
  const seen = new Set<string>();
  const dupes = new Set<string>();
  for (const i of items) {
    if (!i.productId) continue;
    if (seen.has(i.productId)) dupes.add(i.productId);
    seen.add(i.productId);
  }
  return [...dupes];
}

export const orderFormSchema = z.object({
  customerId: z.string().min(1, 'Select a customer.'),
  items: z
    .array(
      z.object({
        productId: z.string().min(1, 'Select a product.'),
        quantity: z.string().refine(isValidQuantity, QTY_MESSAGE),
      }),
    )
    .min(1, 'Add at least one item.')
    .max(MAX_ITEMS, `An order can have at most ${MAX_ITEMS} lines.`)
    .refine((items) => duplicateProductIds(items).length === 0, duplicateProductMessage),
  shippingAddress: z.object({
    line1: z.string().trim().min(1, 'Address line is required.').max(200, 'At most 200 characters.'),
    city: z.string().trim().min(1, 'City is required.').max(100, 'At most 100 characters.'),
    postalCode: z.string().trim().regex(POSTAL, 'Enter a valid postal code (3-10 letters, digits, space or dash).'),
    country: z.string().trim().regex(COUNTRY, 'Use a 2-letter country code, for example US.'),
  }),
});

export type OrderFormValues = z.infer<typeof orderFormSchema>;

export function buildRequest(v: OrderFormValues): CreateOrderRequest {
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
 * Chooses the Idempotency-Key for each submit attempt (same rules as the Angular IdempotencyKeyHolder).
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

const KNOWN_FIELD = /^(customerId|items\.\d+\.(productId|quantity)|shippingAddress\.(line1|city|postalCode|country))$/;

/** `items[0].quantity` -> `items.0.quantity` (react-hook-form path syntax). */
export function toFormPath(field: string): string {
  return field.replace(/\[(\d+)\]/g, '.$1');
}

/**
 * Splits server field errors (`shippingAddress.city`, `items[0].quantity`) into those that map onto a form control and
 * messages that could not be mapped (shown in the banner).
 */
export function splitServerErrors(errors: { field: string; message: string }[]): {
  mapped: { path: string; message: string }[];
  unmatched: string[];
} {
  const mapped: { path: string; message: string }[] = [];
  const unmatched: string[] = [];
  for (const e of errors) {
    const path = toFormPath(e.field);
    if (KNOWN_FIELD.test(path)) mapped.push({ path, message: e.message });
    else unmatched.push(`${e.field}: ${e.message}`);
  }
  return { mapped, unmatched };
}
