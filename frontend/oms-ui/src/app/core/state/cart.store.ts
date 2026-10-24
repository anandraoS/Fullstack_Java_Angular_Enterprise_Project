import { Injectable, computed, signal } from '@angular/core';
import { Product } from '../models';

export interface CartLine {
  productId: string;
  sku: string;
  name: string;
  unitPrice: number;
  quantity: number;
}

export const MAX_LINE_QTY = 1000;
export const MAX_LINES = 50;

/* pure update functions */
export function addToCart(lines: CartLine[], p: Pick<Product, 'id' | 'sku' | 'name' | 'price'>, qty = 1): CartLine[] {
  const existing = lines.find((l) => l.productId === p.id);
  if (existing) {
    return lines.map((l) =>
      l.productId === p.id ? { ...l, quantity: Math.min(MAX_LINE_QTY, l.quantity + qty) } : l,
    );
  }
  if (lines.length >= MAX_LINES) return lines;
  return [...lines, { productId: p.id, sku: p.sku, name: p.name, unitPrice: p.price, quantity: Math.min(MAX_LINE_QTY, qty) }];
}

export function setQuantity(lines: CartLine[], productId: string, qty: number): CartLine[] {
  if (!Number.isFinite(qty) || qty < 1) return lines.filter((l) => l.productId !== productId);
  return lines.map((l) => (l.productId === productId ? { ...l, quantity: Math.min(MAX_LINE_QTY, Math.floor(qty)) } : l));
}

export function removeFromCart(lines: CartLine[], productId: string): CartLine[] {
  return lines.filter((l) => l.productId !== productId);
}

/** In-memory shopping cart (cleared on reload by design: it is only a hand-off from catalog to the order form). */
@Injectable({ providedIn: 'root' })
export class CartStore {
  private readonly _lines = signal<CartLine[]>([]);
  readonly lines = this._lines.asReadonly();
  readonly count = computed(() => this._lines().reduce((n, l) => n + l.quantity, 0));
  readonly total = computed(() => this._lines().reduce((n, l) => n + l.quantity * l.unitPrice, 0));

  add(p: Pick<Product, 'id' | 'sku' | 'name' | 'price'>, qty = 1): void {
    this._lines.update((l) => addToCart(l, p, qty));
  }

  setQuantity(productId: string, qty: number): void {
    this._lines.update((l) => setQuantity(l, productId, qty));
  }

  remove(productId: string): void {
    this._lines.update((l) => removeFromCart(l, productId));
  }

  clear(): void {
    this._lines.set([]);
  }
}
