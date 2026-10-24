import { TestBed } from '@angular/core/testing';
import { CartLine, CartStore, MAX_LINE_QTY, MAX_LINES, addToCart, removeFromCart, setQuantity } from './cart.store';

const P = { id: 'p1', sku: 'SKU-1001', name: 'Widget', price: 10 };

describe('cart pure functions', () => {
  it('adds new lines and merges quantities for the same product', () => {
    const a = addToCart([], P);
    expect(a).toEqual([{ productId: 'p1', sku: 'SKU-1001', name: 'Widget', unitPrice: 10, quantity: 1 }]);
    expect(addToCart(a, P, 4)[0].quantity).toBe(5);
    expect(a[0].quantity).toBe(1); // immutable
  });

  it('caps quantity and line count', () => {
    expect(addToCart(addToCart([], P, MAX_LINE_QTY), P, 10)[0].quantity).toBe(MAX_LINE_QTY);
    const many: CartLine[] = Array.from({ length: MAX_LINES }, (_, i) => ({ productId: `x${i}`, sku: 's', name: 'n', unitPrice: 1, quantity: 1 }));
    expect(addToCart(many, P).length).toBe(MAX_LINES);
  });

  it('setQuantity removes the line for < 1 and floors decimals', () => {
    const a = addToCart([], P, 3);
    expect(setQuantity(a, 'p1', 0)).toEqual([]);
    expect(setQuantity(a, 'p1', 2.9)[0].quantity).toBe(2);
    expect(removeFromCart(a, 'p1')).toEqual([]);
  });
});

describe('CartStore', () => {
  it('computes count and total', () => {
    const cart = TestBed.inject(CartStore);
    cart.add(P, 2);
    cart.add({ id: 'p2', sku: 'SKU-1002', name: 'Gadget', price: 2.5 }, 4);
    expect(cart.count()).toBe(6);
    expect(cart.total()).toBeCloseTo(30);
    cart.setQuantity('p2', 2);
    expect(cart.total()).toBeCloseTo(25);
    cart.remove('p1');
    expect(cart.lines().length).toBe(1);
    cart.clear();
    expect(cart.count()).toBe(0);
  });
});
