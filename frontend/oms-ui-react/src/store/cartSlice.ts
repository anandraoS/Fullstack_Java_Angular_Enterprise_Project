import { createSelector, createSlice, type PayloadAction } from '@reduxjs/toolkit';
import type { Product } from '../models';
import { loggedOut } from './authSlice';

export interface CartLine {
  productId: string;
  sku: string;
  name: string;
  unitPrice: number;
  quantity: number;
}

export const MAX_LINE_QTY = 1000;
export const MAX_LINES = 50;

type ProductRef = Pick<Product, 'id' | 'sku' | 'name' | 'price'>;

/* pure update functions (shared with the Angular app's logic, unit-tested without a store) */
export function addToCart(lines: CartLine[], p: ProductRef, qty = 1): CartLine[] {
  const existing = lines.find((l) => l.productId === p.id);
  if (existing) {
    return lines.map((l) => (l.productId === p.id ? { ...l, quantity: Math.min(MAX_LINE_QTY, l.quantity + qty) } : l));
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

/** In-memory cart (cleared on reload by design: it is only a hand-off from the catalog to the order form). */
export const cartSlice = createSlice({
  name: 'cart',
  initialState: { lines: [] as CartLine[] },
  reducers: {
    cartAdd(state, a: PayloadAction<{ product: ProductRef; qty?: number }>) {
      state.lines = addToCart(state.lines, a.payload.product, a.payload.qty ?? 1);
    },
    cartSetQuantity(state, a: PayloadAction<{ productId: string; qty: number }>) {
      state.lines = setQuantity(state.lines, a.payload.productId, a.payload.qty);
    },
    cartRemove(state, a: PayloadAction<string>) {
      state.lines = removeFromCart(state.lines, a.payload);
    },
    cartClear(state) {
      state.lines = [];
    },
  },
  extraReducers: (b) => {
    b.addCase(loggedOut, (state) => {
      state.lines = []; // a cart never leaks to the next user of the tab
    });
  },
});

export const { cartAdd, cartSetQuantity, cartRemove, cartClear } = cartSlice.actions;

interface WithCart {
  cart: { lines: CartLine[] };
}
export const selectCartLines = (s: WithCart) => s.cart.lines;
export const selectCartCount = createSelector(selectCartLines, (l) => l.reduce((n, x) => n + x.quantity, 0));
export const selectCartTotal = createSelector(selectCartLines, (l) => l.reduce((n, x) => n + x.quantity * x.unitPrice, 0));

export default cartSlice.reducer;
