import type { TokenResponse } from '../models';
import authReducer, {
  SESSION_STORAGE_KEY,
  hasAnyRole,
  loggedOut,
  loginSucceeded,
  msUntilExpiry,
  parseStoredSession,
  readStoredSession,
  selectCustomerId,
  selectIsAdmin,
  selectIsAuthenticated,
  selectIsCustomer,
  selectIsStaff,
  selectRoles,
  selectToken,
  selectUsername,
  sessionFromToken,
  sessionStarted,
  writeStoredSession,
} from './authSlice';
import cartReducer, { addToCart, cartAdd, cartClear, cartRemove, cartSetQuantity, removeFromCart, selectCartCount, selectCartTotal, setQuantity, MAX_LINES, MAX_LINE_QTY } from './cartSlice';
import uiReducer, { mobileNavToggled, selectCurrentToast, selectMobileNavOpen, toast, toastDismissed, toastDuration } from './uiSlice';

const tokenRes: TokenResponse = { accessToken: 't', tokenType: 'Bearer', expiresIn: 3600, username: 'admin1', roles: ['ADMIN'], customerId: null };
const sess = (roles: TokenResponse['roles'], extra = {}) => ({ accessToken: 't', username: 'u', roles, customerId: null, expiresAt: 10_000, ...extra });

describe('auth reducer and helpers', () => {
  it('sessionFromToken computes an absolute expiry and normalises customerId', () => {
    expect(sessionFromToken(tokenRes, 1000)).toEqual({ accessToken: 't', username: 'admin1', roles: ['ADMIN'], customerId: null, expiresAt: 1000 + 3_600_000 });
    expect(sessionFromToken({ ...tokenRes, customerId: undefined as unknown as null }, 0).customerId).toBeNull();
  });

  it('hasAnyRole: no session -> false, empty wanted -> any authenticated, otherwise intersection', () => {
    expect(hasAnyRole(null, ['ADMIN'])).toBe(false);
    expect(hasAnyRole(sess(['CUSTOMER']), undefined)).toBe(true);
    expect(hasAnyRole(sess(['CUSTOMER']), [])).toBe(true);
    expect(hasAnyRole(sess(['CUSTOMER']), ['OPERATOR', 'ADMIN'])).toBe(false);
    expect(hasAnyRole(sess(['OPERATOR']), ['OPERATOR', 'ADMIN'])).toBe(true);
  });

  it('parseStoredSession rejects missing, malformed, incomplete and expired values', () => {
    expect(parseStoredSession(null, 0)).toBeNull();
    expect(parseStoredSession('{not json', 0)).toBeNull();
    expect(parseStoredSession(JSON.stringify({ accessToken: 1 }), 0)).toBeNull();
    expect(parseStoredSession(JSON.stringify(sess(['ADMIN'], { expiresAt: 50 })), 100)).toBeNull(); // expired
    const ok = parseStoredSession(JSON.stringify({ ...sess(['ADMIN']), customerId: undefined }), 100);
    expect(ok).toMatchObject({ username: 'u', customerId: null, expiresAt: 10_000 });
  });

  it('msUntilExpiry clamps to [0, setTimeout max]', () => {
    expect(msUntilExpiry(500, 1000)).toBe(0);
    expect(msUntilExpiry(2000, 1000)).toBe(1000);
    expect(msUntilExpiry(Number.MAX_SAFE_INTEGER, 0)).toBe(2_147_483_000);
  });

  it('storage helpers survive an unavailable storage', () => {
    const boom = {
      getItem: () => {
        throw new Error('denied');
      },
      setItem: () => {
        throw new Error('denied');
      },
      removeItem: () => {
        throw new Error('denied');
      },
    };
    expect(readStoredSession(boom, 0)).toBeNull();
    expect(() => writeStoredSession(boom, sess(['ADMIN']))).not.toThrow();
    expect(() => writeStoredSession(boom, null)).not.toThrow();
    expect(readStoredSession(null, 0)).toBeNull();
    writeStoredSession(null, sess(['ADMIN']));
  });

  it('storage round trip uses the documented key', () => {
    const data = new Map<string, string>();
    const storage = { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v), removeItem: (k: string) => void data.delete(k) };
    writeStoredSession(storage, sess(['OPERATOR']));
    expect(data.has(SESSION_STORAGE_KEY)).toBe(true);
    expect(readStoredSession(storage, 0)?.roles).toEqual(['OPERATOR']);
    writeStoredSession(storage, null);
    expect(readStoredSession(storage, 0)).toBeNull();
  });

  it('reducers: login, session start and logout (with reason)', () => {
    let s = authReducer(undefined, { type: 'init' });
    expect(s).toEqual({ session: null, logoutReason: null });
    s = authReducer(s, loginSucceeded(tokenRes, 0));
    expect(s.session?.expiresAt).toBe(3_600_000);
    s = authReducer(s, loggedOut('expired'));
    expect(s).toEqual({ session: null, logoutReason: 'expired' });
    s = authReducer(s, sessionStarted(sess(['CUSTOMER'])));
    expect(s.logoutReason).toBeNull();
    s = authReducer(s, loggedOut(undefined));
    expect(s.logoutReason).toBe('user');
  });

  it('selectors derive role flags', () => {
    const state = (session: ReturnType<typeof sess> | null) => ({ auth: { session, logoutReason: null } });
    expect(selectIsAuthenticated(state(null))).toBe(false);
    expect(selectToken(state(null))).toBeNull();
    expect(selectUsername(state(null))).toBe('');
    expect(selectRoles(state(null))).toEqual([]);
    const staff = state(sess(['OPERATOR']));
    expect(selectIsStaff(staff)).toBe(true);
    expect(selectIsAdmin(staff)).toBe(false);
    expect(selectIsCustomer(staff)).toBe(false);
    const customer = state(sess(['CUSTOMER'], { customerId: 'c1' }));
    expect(selectIsCustomer(customer)).toBe(true);
    expect(selectCustomerId(customer)).toBe('c1');
    expect(selectIsAdmin(state(sess(['ADMIN'])))).toBe(true);
    // a user with CUSTOMER and OPERATOR is staff, not a plain customer
    expect(selectIsCustomer(state(sess(['CUSTOMER', 'OPERATOR'])))).toBe(false);
  });
});

describe('cart', () => {
  const p = { id: 'p1', sku: 'S1', name: 'One', price: 2 };

  it('addToCart merges lines, caps quantity and line count', () => {
    let lines = addToCart([], p);
    lines = addToCart(lines, p, 2);
    expect(lines).toEqual([{ productId: 'p1', sku: 'S1', name: 'One', unitPrice: 2, quantity: 3 }]);
    expect(addToCart(lines, p, 5000)[0].quantity).toBe(MAX_LINE_QTY);
    expect(addToCart([], p, 5000)[0].quantity).toBe(MAX_LINE_QTY);
    const full = Array.from({ length: MAX_LINES }, (_, i) => ({ productId: `x${i}`, sku: 's', name: 'n', unitPrice: 1, quantity: 1 }));
    expect(addToCart(full, p)).toBe(full);
  });

  it('setQuantity floors, caps and removes on invalid values; removeFromCart', () => {
    const lines = addToCart([], p);
    expect(setQuantity(lines, 'p1', 4.9)[0].quantity).toBe(4);
    expect(setQuantity(lines, 'p1', 99999)[0].quantity).toBe(MAX_LINE_QTY);
    expect(setQuantity(lines, 'p1', 0)).toEqual([]);
    expect(setQuantity(lines, 'p1', Number.NaN)).toEqual([]);
    expect(removeFromCart(lines, 'p1')).toEqual([]);
    expect(removeFromCart(lines, 'zzz')).toHaveLength(1);
  });

  it('slice actions, selectors and logout clearing', () => {
    let s = cartReducer(undefined, cartAdd({ product: p, qty: 2 }));
    s = cartReducer(s, cartAdd({ product: { ...p, id: 'p2', price: 3 } }));
    const state = { cart: s };
    expect(selectCartCount(state)).toBe(3);
    expect(selectCartTotal(state)).toBe(2 * 2 + 3);
    s = cartReducer(s, cartSetQuantity({ productId: 'p1', qty: 5 }));
    expect(s.lines[0].quantity).toBe(5);
    s = cartReducer(s, cartRemove('p2'));
    expect(s.lines).toHaveLength(1);
    expect(cartReducer(s, loggedOut('user')).lines).toEqual([]);
    expect(cartReducer(s, cartClear()).lines).toEqual([]);
  });
});

describe('ui slice', () => {
  it('queues toasts (head shown first), caps the queue and dismisses by id', () => {
    let s = uiReducer(undefined, { type: 'init' });
    expect(selectCurrentToast({ ui: s })).toBeNull();
    s = uiReducer(s, toast.success('one'));
    s = uiReducer(s, toast.warn('two'));
    expect(selectCurrentToast({ ui: s })?.message).toBe('one');
    s = uiReducer(s, toastDismissed(s.toasts[0].id));
    expect(selectCurrentToast({ ui: s })?.message).toBe('two');
    for (let i = 0; i < 10; i++) s = uiReducer(s, toast.info(`m${i}`));
    expect(s.toasts.length).toBeLessThanOrEqual(5);
    expect(toast.error('e').payload.severity).toBe('error');
  });

  it('toggles the mobile nav and exposes auto-hide durations', () => {
    let s = uiReducer(undefined, { type: 'init' });
    s = uiReducer(s, mobileNavToggled(undefined));
    expect(selectMobileNavOpen({ ui: s })).toBe(true);
    s = uiReducer(s, mobileNavToggled(false));
    expect(selectMobileNavOpen({ ui: s })).toBe(false);
    expect(toastDuration('error')).toBeGreaterThan(toastDuration('success'));
  });
});
