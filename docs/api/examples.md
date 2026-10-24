# API Examples (captured from the running stack)

**Status: REAL.** Every request/response below was produced by `node scripts/capture-api-examples.mjs` against the local no-Docker stack (`scripts/run-local.sh`: embedded Kafka, 4 services, gateway) on 2026-10-18, through the gateway at `http://localhost:18080`. Nothing is hand-written. Bearer tokens are redacted (`<redacted>`), the demo password is a placeholder, ids are the values generated in that run (orders are created fresh on every run, so ids differ each time; seed ids are fixed). Long arrays are shortened (marked). All data is synthetic.

Re-generate: start the stack, then `OMS_DEMO_PASSWORD=<printed value> OMS_JWT_SECRET=<only needed for the expired-token example> node scripts/capture-api-examples.mjs`.

Base path `/api/v1`. Common headers: `Authorization: Bearer <JWT>`, `Content-Type: application/json`, optional `X-Correlation-Id` (echoed back, generated when absent).
Error shape (RFC 7807, `application/problem+json`): `type, title, status, detail, instance, code, correlationId, timestamp, errors[]`.

| Item | Value |
|---|---|
| customer1 (Acme Industrial Supply) | `c0000000-0000-0000-0000-000000000001` |
| customer2 (Globex Logistics) | `c0000000-0000-0000-0000-000000000002` |
| SKU-1001 (stock 500) | `a0000000-0000-0000-0000-000000001001` |
| SKU-1019 (stock 3) | `a0000000-0000-0000-0000-000000001019` |
| SKU-1020 (stock 0) | `a0000000-0000-0000-0000-000000001020` |
| Demo users | `customer1`, `customer2` (CUSTOMER), `operator1` (OPERATOR), `admin1` (ADMIN) |

---

### 1. Login (dev token endpoint, gateway profile `dev` only)

The password is the per-run random value printed by `scripts/run-local.sh`; it is replaced by a placeholder below.

Request

```http
POST /api/v1/auth/token
Content-Type: application/json

{
  "username": "customer1",
  "password": "<OMS_DEMO_PASSWORD>"
}
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: 1a3da4ef-8082-43f2-b939-df6ce430622c

{
  "accessToken": "<redacted>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "username": "customer1",
  "roles": [
    "CUSTOMER"
  ],
  "customerId": "c0000000-0000-0000-0000-000000000001"
}
```

### 2. Login with a wrong password: 401

Request

```http
POST /api/v1/auth/token
Content-Type: application/json

{
  "username": "customer1",
  "password": "wrong-password"
}
```

Response: **HTTP 401**

```http
content-type: application/problem+json
www-authenticate: Bearer
x-correlation-id: 1c2e7166-e71d-4080-b437-600992757e0d

{
  "type": "https://oms.example/problems/unauthenticated",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Invalid credentials.",
  "instance": "/api/v1/auth/token",
  "code": "UNAUTHENTICATED",
  "correlationId": "1c2e7166-e71d-4080-b437-600992757e0d",
  "timestamp": "2026-10-18T15:37:05.907396700Z",
  "errors": []
}
```

### 3. Create order: success (202 Accepted)

Validation and the saga run asynchronously, so the order is returned in status `CREATED` with `totalAmount` 0.00 and unpriced items (contract section 13).

Request

```http
POST /api/v1/orders
Authorization: Bearer <redacted>
Content-Type: application/json
Idempotency-Key: ex-e3fd6717-6ca5-48c2-ac29-a178dd00e646

{
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001001",
      "quantity": 2
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  }
}
```

Response: **HTTP 202**

```http
content-type: application/json
location: /api/v1/orders/34b7449a-7ad4-44ac-952b-a8cd27391cde
x-correlation-id: 03f49000-8886-44e9-aa26-02d8700cc13c

{
  "id": "34b7449a-7ad4-44ac-952b-a8cd27391cde",
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "status": "CREATED",
  "statusReason": null,
  "totalAmount": 0,
  "currency": "USD",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001001",
      "sku": null,
      "name": null,
      "quantity": 2,
      "unitPrice": null,
      "lineTotal": null
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  },
  "correlationId": "03f49000-8886-44e9-aa26-02d8700cc13c",
  "createdAt": "2026-10-18T15:37:05.922899500Z",
  "updatedAt": "2026-10-18T15:37:05.922899500Z",
  "version": 0
}
```

### 4. Idempotent replay (same key, same body): 202 + `Idempotent-Replay: true`

Returns the ORIGINAL snapshot (status `CREATED`), not the order's current state.

Request

```http
POST /api/v1/orders
Authorization: Bearer <redacted>
Content-Type: application/json
Idempotency-Key: ex-e3fd6717-6ca5-48c2-ac29-a178dd00e646

{
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001001",
      "quantity": 2
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  }
}
```

Response: **HTTP 202**

```http
content-type: application/json
location: /api/v1/orders/34b7449a-7ad4-44ac-952b-a8cd27391cde
idempotent-replay: true
x-correlation-id: 5a379164-188d-47ef-a442-373cef23a98f

{
  "id": "34b7449a-7ad4-44ac-952b-a8cd27391cde",
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "status": "CREATED",
  "statusReason": null,
  "totalAmount": 0,
  "currency": "USD",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001001",
      "sku": null,
      "name": null,
      "quantity": 2,
      "unitPrice": null,
      "lineTotal": null
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  },
  "correlationId": "03f49000-8886-44e9-aa26-02d8700cc13c",
  "createdAt": "2026-10-18T15:37:05.922899500Z",
  "updatedAt": "2026-10-18T15:37:05.922899500Z",
  "version": 0
}
```

### 5. Idempotency key reused with a different body: 422

Request

```http
POST /api/v1/orders
Authorization: Bearer <redacted>
Content-Type: application/json
Idempotency-Key: ex-e3fd6717-6ca5-48c2-ac29-a178dd00e646

{
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001001",
      "quantity": 3
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  }
}
```

Response: **HTTP 422**

```http
content-type: application/problem+json
x-correlation-id: 761b0d3c-3e5a-4a34-8138-b6fa8f9ae1c2

{
  "type": "https://oms.example/problems/idempotency-key-reused",
  "title": "Idempotency key reused with a different request",
  "status": 422,
  "detail": "Idempotency-Key was already used with a different request body",
  "instance": "/api/v1/orders",
  "code": "IDEMPOTENCY_KEY_REUSED",
  "correlationId": "761b0d3c-3e5a-4a34-8138-b6fa8f9ae1c2",
  "timestamp": "2026-10-18T15:37:05.996353600Z",
  "errors": []
}
```

### 6. Missing Idempotency-Key: 400 `IDEMPOTENCY_KEY_REQUIRED`

Request

```http
POST /api/v1/orders
Authorization: Bearer <redacted>
Content-Type: application/json

{
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001001",
      "quantity": 2
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  }
}
```

Response: **HTTP 400**

```http
content-type: application/problem+json
x-correlation-id: 50b33952-2a12-4eb8-9b8e-7f4f87b3220d

{
  "type": "https://oms.example/problems/idempotency-key-required",
  "title": "Idempotency-Key header required",
  "status": 400,
  "detail": "Header Idempotency-Key is required (8-64 chars of A-Z a-z 0-9 _ -)",
  "instance": "/api/v1/orders",
  "code": "IDEMPOTENCY_KEY_REQUIRED",
  "correlationId": "50b33952-2a12-4eb8-9b8e-7f4f87b3220d",
  "timestamp": "2026-10-18T15:37:06.012296300Z",
  "errors": []
}
```

### 7. Validation error: 400 `VALIDATION_FAILED`

Request

```http
POST /api/v1/orders
Authorization: Bearer <redacted>
Content-Type: application/json
Idempotency-Key: ex-16fab17c-3a88-4cae-87ef-c18a5c977a05

{
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001001",
      "quantity": 0
    }
  ],
  "shippingAddress": {
    "line1": "",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  }
}
```

Response: **HTTP 400**

```http
content-type: application/problem+json
x-correlation-id: cbb52cc1-374a-414a-a96e-3559835d36bf

{
  "type": "https://oms.example/problems/validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "Validation failed",
  "instance": "/api/v1/orders",
  "code": "VALIDATION_FAILED",
  "correlationId": "cbb52cc1-374a-414a-a96e-3559835d36bf",
  "timestamp": "2026-10-18T15:37:06.170365100Z",
  "errors": [
    {
      "field": "items[0].quantity",
      "message": "must be greater than or equal to 1"
    },
    {
      "field": "shippingAddress.line1",
      "message": "must not be blank"
    }
  ]
}
```

### 8. Get order after the saga finished (FULFILLED)

Request

```http
GET /api/v1/orders/34b7449a-7ad4-44ac-952b-a8cd27391cde
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: cfb77661-a862-4e11-9ddd-52bfd330271f

{
  "id": "34b7449a-7ad4-44ac-952b-a8cd27391cde",
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "status": "FULFILLED",
  "statusReason": null,
  "totalAmount": 49,
  "currency": "USD",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001001",
      "sku": "SKU-1001",
      "name": "Industrial Safety Gloves (12 pair)",
      "quantity": 2,
      "unitPrice": 24.5,
      "lineTotal": 49
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  },
  "correlationId": "03f49000-8886-44e9-aa26-02d8700cc13c",
  "createdAt": "2026-10-18T15:37:05.922900Z",
  "updatedAt": "2026-10-18T15:37:13.644259Z",
  "version": 3
}
```

### 9. Order status history

Request

```http
GET /api/v1/orders/34b7449a-7ad4-44ac-952b-a8cd27391cde/history
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: 1329d63b-e0dc-4638-a865-516719dc523e

[
  {
    "fromStatus": null,
    "toStatus": "CREATED",
    "reason": "Order created",
    "actor": "customer1",
    "occurredAt": "2026-10-18T15:37:05.927897Z"
  },
  {
    "fromStatus": "CREATED",
    "toStatus": "VALIDATED",
    "reason": null,
    "actor": "saga",
    "occurredAt": "2026-10-18T15:37:05.964709Z"
  },
  "... 3 more item(s) omitted"
]
```

### 10. Cross-customer access: 404 (not 403, to avoid enumeration)

Request

```http
GET /api/v1/orders/34b7449a-7ad4-44ac-952b-a8cd27391cde
Authorization: Bearer <redacted>
```

Response: **HTTP 404**

```http
content-type: application/problem+json
x-correlation-id: bcb5db0a-7147-4c33-85fe-e33d29034051

{
  "type": "https://oms.example/problems/not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "Order 34b7449a-7ad4-44ac-952b-a8cd27391cde not found",
  "instance": "/api/v1/orders/34b7449a-7ad4-44ac-952b-a8cd27391cde",
  "code": "NOT_FOUND",
  "correlationId": "bcb5db0a-7147-4c33-85fe-e33d29034051",
  "timestamp": "2026-10-18T15:37:14.126513300Z",
  "errors": []
}
```

### 11. Cancel a FULFILLED order: 409 `INVALID_STATE_TRANSITION`

Request

```http
POST /api/v1/orders/34b7449a-7ad4-44ac-952b-a8cd27391cde/cancel
Authorization: Bearer <redacted>
Content-Type: application/json

{
  "reason": "too late"
}
```

Response: **HTTP 409**

```http
content-type: application/problem+json
x-correlation-id: bacd92d7-b0a7-41b9-87c4-bb1cf06ccba4

{
  "type": "https://oms.example/problems/invalid-state-transition",
  "title": "Invalid state transition",
  "status": 409,
  "detail": "Order cannot transition from FULFILLED to CANCELLED",
  "instance": "/api/v1/orders/34b7449a-7ad4-44ac-952b-a8cd27391cde/cancel",
  "code": "INVALID_STATE_TRANSITION",
  "correlationId": "bacd92d7-b0a7-41b9-87c4-bb1cf06ccba4",
  "timestamp": "2026-10-18T15:37:14.175863100Z",
  "errors": []
}
```

### 12. Insufficient stock, step 1: accepted (202)

Request

```http
POST /api/v1/orders
Authorization: Bearer <redacted>
Content-Type: application/json
Idempotency-Key: it-d75461d5-a165-4044-a787-2e668a0a8d8a

{
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001020",
      "quantity": 1
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  }
}
```

Response: **HTTP 202**

```http
content-type: application/json
location: /api/v1/orders/f88862ee-7774-4323-b61d-d74be7e5bcd6
x-correlation-id: c49989f6-6c76-4068-ad79-ce063df7b97e

{
  "id": "f88862ee-7774-4323-b61d-d74be7e5bcd6",
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "status": "CREATED",
  "statusReason": null,
  "totalAmount": 0,
  "currency": "USD",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001020",
      "sku": null,
      "name": null,
      "quantity": 1,
      "unitPrice": null,
      "lineTotal": null
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  },
  "correlationId": "c49989f6-6c76-4068-ad79-ce063df7b97e",
  "createdAt": "2026-10-18T15:37:14.216874500Z",
  "updatedAt": "2026-10-18T15:37:14.216874500Z",
  "version": 0
}
```

### 13. Insufficient stock, step 2: the order is REJECTED (SKU-1020 has stock 0)

Request

```http
GET /api/v1/orders/f88862ee-7774-4323-b61d-d74be7e5bcd6
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: 9ad44a6b-e2f6-43da-8181-0f220dcf4b38

{
  "id": "f88862ee-7774-4323-b61d-d74be7e5bcd6",
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "status": "REJECTED",
  "statusReason": "Validation failed: SKU-1020: INSUFFICIENT_STOCK",
  "totalAmount": 0,
  "currency": "USD",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001020",
      "sku": null,
      "name": null,
      "quantity": 1,
      "unitPrice": null,
      "lineTotal": null
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  },
  "correlationId": "c49989f6-6c76-4068-ad79-ce063df7b97e",
  "createdAt": "2026-10-18T15:37:14.216875Z",
  "updatedAt": "2026-10-18T15:37:14.321845Z",
  "version": 1
}
```

### 14. Customer creating an order for another customer: 403

Request

```http
POST /api/v1/orders
Authorization: Bearer <redacted>
Content-Type: application/json
Idempotency-Key: ex-ba854cec-f440-469c-a2e6-4aec9b01feb8

{
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001001",
      "quantity": 2
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  }
}
```

Response: **HTTP 403**

```http
content-type: application/problem+json
x-correlation-id: 797f18cd-34b7-4cd1-9083-1a2a82f1ff0f

{
  "type": "https://oms.example/problems/forbidden",
  "title": "Forbidden",
  "status": 403,
  "detail": "customerId does not match the authenticated customer",
  "instance": "/api/v1/orders",
  "code": "FORBIDDEN",
  "correlationId": "797f18cd-34b7-4cd1-9083-1a2a82f1ff0f",
  "timestamp": "2026-10-18T15:37:14.972511600Z",
  "errors": []
}
```

### 15. Cancel an in-flight order: success (200)

Allowed from CREATED, VALIDATED, INVENTORY_RESERVED and CONFIRMED; a CONFIRMED order passes through COMPENSATION_PENDING while the reservation is released.

Request

```http
POST /api/v1/orders/d89893a3-2546-4b24-970d-f382fac1bbef/cancel
Authorization: Bearer <redacted>
Content-Type: application/json

{
  "reason": "Ordered by mistake"
}
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: 8bfa1366-7987-4202-8963-fa451b212ba5

{
  "id": "d89893a3-2546-4b24-970d-f382fac1bbef",
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "status": "COMPENSATION_PENDING",
  "statusReason": "Ordered by mistake",
  "totalAmount": 129.99,
  "currency": "USD",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001005",
      "sku": "SKU-1005",
      "name": "Cordless Drill 18V",
      "quantity": 1,
      "unitPrice": 129.99,
      "lineTotal": 129.99
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  },
  "correlationId": "467ac446-f0c8-484d-89ec-987a12634730",
  "createdAt": "2026-10-18T15:37:15.015620Z",
  "updatedAt": "2026-10-18T15:37:15.685281600Z",
  "version": 3
}
```

### 16. Failed fulfillment (shipping city `FAILTOWN`) ends FAILED, stock released

Final state reached FAILED; the history passes CONFIRMED > COMPENSATION_PENDING > FAILED.

Request

```http
GET /api/v1/orders/200a13f4-be5a-449d-801f-741f7126c425
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: 55e25dae-e1e5-47b5-9aef-f524a33a7739

{
  "id": "200a13f4-be5a-449d-801f-741f7126c425",
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "status": "FAILED",
  "statusReason": "Fulfillment failed: Shipping address not serviceable: city FAILTOWN (attempts 3)",
  "totalAmount": 89,
  "currency": "USD",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001004",
      "sku": "SKU-1004",
      "name": "Steel Toe Work Boots",
      "quantity": 1,
      "unitPrice": 89,
      "lineTotal": 89
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "FAILTOWN",
    "postalCode": "12345",
    "country": "US"
  },
  "correlationId": "4801a573-2c86-4469-b404-ebfd9eebee19",
  "createdAt": "2026-10-18T15:37:16.883116Z",
  "updatedAt": "2026-10-18T15:37:23.364526Z",
  "version": 4
}
```

### 17. Failed order status history

Request

```http
GET /api/v1/orders/200a13f4-be5a-449d-801f-741f7126c425/history
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: 2b7c9937-205c-4088-9a92-1fde6ac01a85

[
  {
    "fromStatus": null,
    "toStatus": "CREATED",
    "reason": "Order created",
    "actor": "customer1",
    "occurredAt": "2026-10-18T15:37:16.889235Z"
  },
  {
    "fromStatus": "CREATED",
    "toStatus": "VALIDATED",
    "reason": null,
    "actor": "saga",
    "occurredAt": "2026-10-18T15:37:16.920830Z"
  },
  "... 4 more item(s) omitted"
]
```

### 18. Customer product list (paged, availability only)

Request

```http
GET /api/v1/products?size=2&sort=sku,asc
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: cf0204c2-f866-42af-b7ac-a5b00b1c692f

{
  "content": [
    {
      "id": "a0000000-0000-0000-0000-000000001001",
      "sku": "SKU-1001",
      "name": "Industrial Safety Gloves (12 pair)",
      "description": "Cut-resistant work gloves, bulk pack",
      "category": "Safety",
      "price": 24.5,
      "currency": "USD",
      "active": true,
      "available": 496
    },
    {
      "id": "a0000000-0000-0000-0000-000000001002",
      "sku": "SKU-1002",
      "name": "Hard Hat, Class E",
      "description": "ANSI rated hard hat with ratchet suspension",
      "category": "Safety",
      "price": 18.75,
      "currency": "USD",
      "active": true,
      "available": 318
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 20,
  "totalPages": 10
}
```

### 19. Operator inventory item (stock fields visible)

Request

```http
GET /api/v1/inventory/a0000000-0000-0000-0000-000000001019
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: 2a902f1c-3a34-4900-ab72-606a2b3f0522

{
  "id": "a0000000-0000-0000-0000-000000001019",
  "sku": "SKU-1019",
  "name": "Forklift Battery Charger",
  "description": "Industrial 24V charger (limited stock)",
  "category": "Material Handling",
  "price": 1450,
  "currency": "USD",
  "active": true,
  "onHand": 0,
  "reserved": 0,
  "available": 0,
  "version": 0
}
```

### 20. Customer cannot read inventory: 403

Request

```http
GET /api/v1/inventory
Authorization: Bearer <redacted>
```

Response: **HTTP 403**

```http
content-type: application/problem+json
x-correlation-id: 5a0bf29c-8f9e-4a9c-8443-2bb417505fb4

{
  "type": "https://oms.example/problems/forbidden",
  "title": "Forbidden",
  "status": 403,
  "detail": "You are not allowed to perform this operation.",
  "instance": "/api/v1/inventory",
  "code": "FORBIDDEN",
  "correlationId": "5a0bf29c-8f9e-4a9c-8443-2bb417505fb4",
  "timestamp": "2026-10-18T15:37:23.831568200Z",
  "errors": []
}
```

### 21. Stock adjustment below reserved/on-hand: 409 `CONFLICT`

Request

```http
PUT /api/v1/inventory/a0000000-0000-0000-0000-000000001019/stock
Authorization: Bearer <redacted>
Content-Type: application/json

{
  "delta": -100000,
  "reason": "invalid adjustment demo"
}
```

Response: **HTTP 409**

```http
content-type: application/problem+json
x-correlation-id: ee3c4c72-eb33-4ee3-a59a-682ae7db36a0

{
  "type": "https://oms.example/problems/conflict",
  "title": "Conflict",
  "status": 409,
  "detail": "Resulting on-hand -100000 would be below the 0 units already reserved for orders",
  "instance": "/api/v1/inventory/a0000000-0000-0000-0000-000000001019/stock",
  "code": "CONFLICT",
  "correlationId": "ee3c4c72-eb33-4ee3-a59a-682ae7db36a0",
  "timestamp": "2026-10-18T15:37:23.866748400Z",
  "errors": []
}
```

### 22. Saga of an order (operator)

Request

```http
GET /api/v1/admin/saga/200a13f4-be5a-449d-801f-741f7126c425
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: a419cdef-44cf-4126-8a04-0728dd6d5828

{
  "orderId": "200a13f4-be5a-449d-801f-741f7126c425",
  "sagaId": "6fa0a211-0e3d-4e35-a350-894c30ec9488",
  "state": "FAILED",
  "currentStep": "RELEASE_INVENTORY",
  "attempts": 0,
  "startedAt": "2026-10-18T15:37:16.884628Z",
  "updatedAt": "2026-10-18T15:37:23.368181Z",
  "lastError": null,
  "steps": [
    {
      "step": "VALIDATE",
      "status": "STARTED",
      "attempt": 0,
      "detail": "order accepted",
      "at": "2026-10-18T15:37:16.884628Z"
    },
    {
      "step": "VALIDATE",
      "status": "SUCCEEDED",
      "attempt": 1,
      "detail": "priced 1 line(s), total 89.00",
      "at": "2026-10-18T15:37:16.923865Z"
    },
    "... 8 more item(s) omitted"
  ]
}
```

### 23. Retry of a terminal order: 409

Request

```http
POST /api/v1/admin/orders/200a13f4-be5a-449d-801f-741f7126c425/retry
Authorization: Bearer <redacted>
```

Response: **HTTP 409**

```http
content-type: application/problem+json
x-correlation-id: 1664af7c-ee53-4606-9590-cf80e663e2e6

{
  "type": "https://oms.example/problems/invalid-state-transition",
  "title": "Invalid state transition",
  "status": 409,
  "detail": "Order cannot transition from FAILED to RETRY",
  "instance": "/api/v1/admin/orders/200a13f4-be5a-449d-801f-741f7126c425/retry",
  "code": "INVALID_STATE_TRANSITION",
  "correlationId": "1664af7c-ee53-4606-9590-cf80e663e2e6",
  "timestamp": "2026-10-18T15:37:23.959299100Z",
  "errors": []
}
```

### 24. Failed-orders list (operator)

Request

```http
GET /api/v1/admin/orders/failed?size=2
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: c6a1e038-8709-4001-be9b-7cf151a24215

{
  "content": [
    {
      "id": "200a13f4-be5a-449d-801f-741f7126c425",
      "customerId": "c0000000-0000-0000-0000-000000000001",
      "status": "FAILED",
      "statusReason": "Fulfillment failed: Shipping address not serviceable: city FAILTOWN (attempts 3)",
      "totalAmount": 89,
      "currency": "USD",
      "items": [
        {
          "productId": "a0000000-0000-0000-0000-000000001004",
          "sku": "SKU-1004",
          "name": "Steel Toe Work Boots",
          "quantity": 1,
          "unitPrice": 89,
          "lineTotal": 89
        }
      ],
      "shippingAddress": {
        "line1": "1 Test Street",
        "city": "FAILTOWN",
        "postalCode": "12345",
        "country": "US"
      },
      "correlationId": "4801a573-2c86-4469-b404-ebfd9eebee19",
      "createdAt": "2026-10-18T15:37:16.883116Z",
      "updatedAt": "2026-10-18T15:37:23.364526Z",
      "version": 4
    },
    {
      "id": "f88862ee-7774-4323-b61d-d74be7e5bcd6",
      "customerId": "c0000000-0000-0000-0000-000000000001",
      "status": "REJECTED",
      "statusReason": "Validation failed: SKU-1020: INSUFFICIENT_STOCK",
      "totalAmount": 0,
      "currency": "USD",
      "items": [
        {
          "productId": "a0000000-0000-0000-0000-000000001020",
          "sku": null,
          "name": null,
          "quantity": 1,
          "unitPrice": null,
          "lineTotal": null
        }
      ],
      "shippingAddress": {
        "line1": "1 Test Street",
        "city": "Springfield",
        "postalCode": "12345",
        "country": "US"
      },
      "correlationId": "c49989f6-6c76-4068-ad79-ce063df7b97e",
      "createdAt": "2026-10-18T15:37:14.216875Z",
      "updatedAt": "2026-10-18T15:37:14.321845Z",
      "version": 1
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 35,
  "totalPages": 18
}
```

### 25. Fulfillment of the failed order (operator)

Request

```http
GET /api/v1/fulfillments?orderId=200a13f4-be5a-449d-801f-741f7126c425
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: e350884f-dde3-4166-b4a7-c599936d4293

{
  "content": [
    {
      "id": "794308d5-2afa-4675-9a7b-f92a4afb078a",
      "orderId": "200a13f4-be5a-449d-801f-741f7126c425",
      "status": "FAILED",
      "trackingNumber": null,
      "attempts": 3,
      "failureReason": "Shipping address not serviceable: city FAILTOWN",
      "events": [
        {
          "status": "PENDING",
          "at": "2026-10-18T15:37:17.934289Z"
        },
        {
          "status": "FAILED",
          "at": "2026-10-18T15:37:22.749023Z"
        }
      ],
      "createdAt": "2026-10-18T15:37:17.934289Z",
      "updatedAt": "2026-10-18T15:37:22.749023Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### 26. Notifications of the order (customer, own only; recipient address never returned)

Request

```http
GET /api/v1/notifications?orderId=34b7449a-7ad4-44ac-952b-a8cd27391cde&size=2
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: 0b662205-848f-4109-b8d4-dd8581179347

{
  "content": [
    {
      "id": "5bab6ef3-3752-47d3-9231-7bdbc8076306",
      "customerId": "c0000000-0000-0000-0000-000000000001",
      "orderId": "34b7449a-7ad4-44ac-952b-a8cd27391cde",
      "channel": "EMAIL",
      "template": "ORDER_FULFILLED",
      "subject": "Order 34b7449a delivered - tracking OMS-50DFE420109C",
      "status": "SENT",
      "attempts": 1,
      "lastError": null,
      "correlationId": "03f49000-8886-44e9-aa26-02d8700cc13c",
      "sourceEventId": "d89dc13a-6701-4c6a-9580-b84a344bf50b",
      "createdAt": "2026-10-18T15:37:14.148832Z",
      "sentAt": "2026-10-18T15:37:14.260254Z"
    },
    {
      "id": "42b89708-b050-4f70-a24c-48cbbfcbfb54",
      "customerId": "c0000000-0000-0000-0000-000000000001",
      "orderId": "34b7449a-7ad4-44ac-952b-a8cd27391cde",
      "channel": "EMAIL",
      "template": "FULFILLMENT_COMPLETED",
      "subject": "Order 34b7449a has shipped - tracking OMS-50DFE420109C",
      "status": "SENT",
      "attempts": 1,
      "lastError": null,
      "correlationId": "03f49000-8886-44e9-aa26-02d8700cc13c",
      "sourceEventId": "2c6d06c6-6bcb-4b4a-a3c5-2eea627073a9",
      "createdAt": "2026-10-18T15:37:13.634557Z",
      "sentAt": "2026-10-18T15:37:13.699281Z"
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 5,
  "totalPages": 3
}
```

### 27. Audit entries of an order (operator)

Request

```http
GET /api/v1/ops/order/audit?entityType=Order&entityId=34b7449a-7ad4-44ac-952b-a8cd27391cde&size=2
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: f6f462f3-eb5a-439e-bfa8-89787e865500

{
  "content": [
    {
      "id": "1af33d5a-89ab-4f5c-973c-21765349e760",
      "occurredAt": "2026-10-18T15:37:13.646274Z",
      "actor": "saga",
      "entityType": "Order",
      "entityId": "34b7449a-7ad4-44ac-952b-a8cd27391cde",
      "action": "STATUS_FULFILLED",
      "detail": "{\"from\":\"CONFIRMED\",\"to\":\"FULFILLED\",\"trigger\":\"FULFILLMENT_COMPLETED\"}",
      "correlationId": "03f49000-8886-44e9-aa26-02d8700cc13c"
    },
    {
      "id": "3ceebef5-c2a2-497f-a011-f782a289ed9b",
      "occurredAt": "2026-10-18T15:37:06.521947Z",
      "actor": "saga",
      "entityType": "Order",
      "entityId": "34b7449a-7ad4-44ac-952b-a8cd27391cde",
      "action": "STATUS_CONFIRMED",
      "detail": "{\"from\":\"INVENTORY_RESERVED\",\"to\":\"CONFIRMED\",\"trigger\":\"CONFIRM\"}",
      "correlationId": "03f49000-8886-44e9-aa26-02d8700cc13c"
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 5,
  "totalPages": 3
}
```

### 28. Messaging status through the gateway rewrite (operator)

Request

```http
GET /api/v1/ops/order/messaging
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: ce138552-476c-4375-a2c8-040af25bc859

{
  "service": "order-service",
  "consumerGroup": "order-service",
  "topics": [
    {
      "topic": "oms.inventory.events.v1",
      "partitions": 3,
      "consumerLag": 0,
      "committedOffsets": {
        "0": 4,
        "1": 17,
        "2": 18
      }
    },
    {
      "topic": "oms.fulfillment.events.v1",
      "partitions": 3,
      "consumerLag": 0,
      "committedOffsets": {
        "0": 4,
        "1": 19,
        "2": 20
      }
    }
  ],
  "processedEvents": 82,
  "duplicatesIgnored": 0,
  "retries": 0,
  "deadLettered": 0,
  "outboxPending": 0,
  "lastProcessedAt": "2026-10-18T15:37:23.370196700Z",
  "dltTopics": [
    "oms.inventory.events.v1.DLT",
    "oms.fulfillment.events.v1.DLT"
  ],
  "lagAvailable": true
}
```

### 29. Aggregate health (operator)

Request

```http
GET /api/v1/ops/health
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: 85d4d5ad-0a52-4d6a-a7b8-8a64635736a4

{
  "status": "UP",
  "services": {
    "order": {
      "status": "UP",
      "detail": null
    },
    "inventory": {
      "status": "UP",
      "detail": null
    },
    "fulfillment": {
      "status": "UP",
      "detail": null
    },
    "notification": {
      "status": "UP",
      "detail": null
    }
  }
}
```

### 30. Reconciliation (admin)

Request

```http
POST /api/v1/admin/reconciliation/run
Authorization: Bearer <redacted>
```

Response: **HTTP 200**

```http
content-type: application/json
x-correlation-id: 07bc8c65-1bfa-4518-9f1b-7c34f939f764

{
  "examined": 0,
  "repaired": 0,
  "details": []
}
```

### 31. Reconciliation as OPERATOR: 403

Request

```http
POST /api/v1/admin/reconciliation/run
Authorization: Bearer <redacted>
```

Response: **HTTP 403**

```http
content-type: application/problem+json
x-correlation-id: e4982423-b7e9-4e3a-86fc-0db9d40e6133

{
  "type": "https://oms.example/problems/forbidden",
  "title": "Forbidden",
  "status": 403,
  "detail": "You are not allowed to perform this operation.",
  "instance": "/api/v1/admin/reconciliation/run",
  "code": "FORBIDDEN",
  "correlationId": "e4982423-b7e9-4e3a-86fc-0db9d40e6133",
  "timestamp": "2026-10-18T15:37:24.199022800Z",
  "errors": []
}
```

### 32. Internal endpoints are not routed through the gateway: 404

Request

```http
POST /api/v1/internal/products/validate
Authorization: Bearer <redacted>
Content-Type: application/json

{
  "items": []
}
```

Response: **HTTP 404**

```http
content-type: application/problem+json
x-correlation-id: e0cdc950-d8f6-42d1-92f9-b34b3de94e8c

{
  "type": "https://oms.example/problems/not-found",
  "title": "Not Found",
  "status": 404,
  "detail": "No such resource.",
  "instance": "/api/v1/internal/products/validate",
  "code": "NOT_FOUND",
  "correlationId": "e0cdc950-d8f6-42d1-92f9-b34b3de94e8c",
  "timestamp": "2026-10-18T15:37:24.203021500Z",
  "errors": []
}
```

### 33. No token: 401

Request

```http
GET /api/v1/orders
```

Response: **HTTP 401**

```http
content-type: application/problem+json
www-authenticate: Bearer
x-correlation-id: 807fc995-8d37-4040-9042-aadf2aeca882

{
  "type": "https://oms.example/problems/unauthenticated",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Authentication is required.",
  "instance": "/api/v1/orders",
  "code": "UNAUTHENTICATED",
  "correlationId": "807fc995-8d37-4040-9042-aadf2aeca882",
  "timestamp": "2026-10-18T15:37:24.207019300Z",
  "errors": []
}
```

### 34. Forged token (signed with a random secret): 401

Request

```http
GET /api/v1/orders
Authorization: Bearer <forged JWT>
```

Response: **HTTP 401**

```http
content-type: application/problem+json
www-authenticate: Bearer
x-correlation-id: 99a0d725-9ca7-4645-9287-19c4c0ddc428

{
  "type": "https://oms.example/problems/unauthenticated",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Authentication is required.",
  "instance": "/api/v1/orders",
  "code": "UNAUTHENTICATED",
  "correlationId": "99a0d725-9ca7-4645-9287-19c4c0ddc428",
  "timestamp": "2026-10-18T15:37:24.217543800Z",
  "errors": []
}
```

### 35. Expired token (correct signature, `exp` in the past): 401

Request

```http
GET /api/v1/orders
Authorization: Bearer <expired JWT>
```

Response: **HTTP 401**

```http
content-type: application/problem+json
www-authenticate: Bearer
x-correlation-id: 7187682c-f937-48d6-853d-e236ea061ca2

{
  "type": "https://oms.example/problems/unauthenticated",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Authentication is required.",
  "instance": "/api/v1/orders",
  "code": "UNAUTHENTICATED",
  "correlationId": "7187682c-f937-48d6-853d-e236ea061ca2",
  "timestamp": "2026-10-18T15:37:24.222574500Z",
  "errors": []
}
```

### 36. Algorithm `none` token: 401

Request

```http
GET /api/v1/orders
Authorization: Bearer <alg=none JWT>
```

Response: **HTTP 401**

```http
content-type: application/problem+json
www-authenticate: Bearer
x-correlation-id: c309dd10-6076-44d4-b399-e5b2e4881480

{
  "type": "https://oms.example/problems/unauthenticated",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Authentication is required.",
  "instance": "/api/v1/orders",
  "code": "UNAUTHENTICATED",
  "correlationId": "c309dd10-6076-44d4-b399-e5b2e4881480",
  "timestamp": "2026-10-18T15:37:24.231555900Z",
  "errors": []
}
```

### 37. Payload larger than 64 KB: 413 `PAYLOAD_TOO_LARGE`

Request

```http
POST /api/v1/orders
Authorization: Bearer <redacted>
Content-Type: application/json
Idempotency-Key: ex-3e34e9c3-5c49-4b1f-81ac-3d48b4a8175f

<70203 bytes of JSON, truncated here>
```

Response: **HTTP 413**

```http
content-type: application/problem+json
x-correlation-id: 17af2604-dc18-4fe7-a7bf-43f025b32c0e

{
  "type": "https://oms.example/problems/payload-too-large",
  "title": "Payload Too Large",
  "status": 413,
  "detail": "Request body exceeds the 65536 byte limit.",
  "instance": "/api/v1/orders",
  "code": "PAYLOAD_TOO_LARGE",
  "correlationId": "17af2604-dc18-4fe7-a7bf-43f025b32c0e",
  "timestamp": "2026-10-18T15:37:24.240612900Z",
  "errors": []
}
```

### 38. Rate limited: 429 `RATE_LIMITED` with `Retry-After` (POST /orders is limited per caller)

Reached by sending small valid POST /orders requests (SKU-1016 x1, each creating a real order) back-to-back as one caller until its token bucket was empty (bucket capacity 20, refill 5/s, `oms.order.rate-limit.*`).

Request

```http
POST /api/v1/orders
Authorization: Bearer <redacted>
Content-Type: application/json
Idempotency-Key: ex-<fresh key>

{
  "customerId": "c0000000-0000-0000-0000-000000000001",
  "items": [
    {
      "productId": "a0000000-0000-0000-0000-000000001016",
      "quantity": 1
    }
  ],
  "shippingAddress": {
    "line1": "1 Test Street",
    "city": "Springfield",
    "postalCode": "12345",
    "country": "US"
  }
}
```

Response: **HTTP 429**

```http
content-type: application/problem+json
retry-after: 1
x-correlation-id: 2148022e-6e5c-4cbe-91e1-892a05a2dd67

{
  "type": "https://oms.example/problems/rate-limited",
  "title": "Rate limit exceeded",
  "status": 429,
  "detail": "Too many order requests; retry after 1s",
  "instance": "/api/v1/orders",
  "code": "RATE_LIMITED",
  "correlationId": "2148022e-6e5c-4cbe-91e1-892a05a2dd67",
  "timestamp": "2026-10-18T15:37:26.024165400Z",
  "errors": []
}
```

