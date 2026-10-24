# 12 Treating an Angular route guard as the security control

Real counterparts: `frontend/oms-ui/src/app/core/auth/guards.ts` (its header comment states the rule),
`core/auth/guards.spec.ts`, and the backend authorization matrix in `docs/architecture/00-shared-contract.md` section 4.

## Prompt that produces it
"Only admins should be able to adjust stock. Protect the route."

## Flawed approach
```ts
// routes
{ path: 'inventory/adjust', component: StockAdjustComponent, canActivate: [adminGuard] }

// component: also hides the button for non-admins
<button *ngIf="auth.isAdmin()" (click)="adjust()">Adjust stock</button>
```
and, on the backend, `PUT /inventory/{id}/stock` has no `@PreAuthorize` because "the UI already restricts it". An
assistant that is told to "secure the page" will happily stop here.

## Why it is risky here
Everything in the browser is attacker-controlled: the guard can be bypassed by editing the JS, calling the API with
`curl`, or replaying a captured request. A CUSTOMER token must get 403 from `PUT /api/v1/inventory/{productId}/stock`
regardless of what the UI shows. Roles read from a decoded JWT in the browser are display hints, not proof (the
signature is only verified by the server and gateway). OWASP A01 and API5 (broken function-level authorization).

## Correct layering
1. Backend is authoritative: method security (`@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")`) plus resource-level
   ownership checks; the gateway validates the JWT at the edge; services validate again (zero trust between layers).
2. UI guards and `*ngIf`/`@if` are UX only: they avoid showing screens that would only produce 403s.
3. The interceptor maps 401 to "sign in again" and 403 to a forbidden page, never retrying silently.

## Regression tests
Backend (the one that matters; covered in inventory-service by `InventoryApiTest#adjustValidationAndAuthorization`): with a CUSTOMER token `PUT /api/v1/inventory/{id}/stock`
returns 403 `FORBIDDEN`, with no token 401 `UNAUTHENTICATED`, with OPERATOR 200. Platform-level security behaviour is
already exercised by `SecurityApiTest` in `services/platform-common/src/test/java/com/enterprise/oms/common/security/`.

Frontend (illustrative): the guard redirects a CUSTOMER to `/forbidden`; plus a comment-level assertion in review
that "removing this guard must not change what the API allows".
```ts
it('redirects a customer away from an ops route', () => {
  auth.setSession({ roles: ['CUSTOMER'] } as any);
  const result = TestBed.runInInjectionContext(() => roleGuard({ data: { roles: ['OPERATOR','ADMIN'] } } as any, {} as any));
  expect(result.toString()).toContain('/forbidden');
});
```

## Review prompt to catch it
"If I delete this guard and call the endpoint directly with a CUSTOMER token, what does the server say? Which test proves it?"
