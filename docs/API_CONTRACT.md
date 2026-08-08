# CinemaSeat API Contract

This document is the shared API contract for the CinemaSeat project.

All frontend, backend, payment, and integration work must follow these endpoints and data conventions.

---

## Base URL

Local development:

```text
http://localhost:<API_PORT>
```

Inside Docker:

```text
http://api:<API_PORT>
```

The exact API port must be defined by the project's Docker configuration.

---

# 1. Health

## GET `/health`

Returns application health.

### Success

```http
200 OK
```

Example:

```json
{
  "status": "UP"
}
```

Requirements:

- Must respond quickly.
- Must not synchronously call the payment gateway.
- Must continue returning 200 if the gateway is unavailable.

---

# 2. Movies

## GET `/api/movies`

Returns available movies.

### Example response

```json
[
  {
    "id": 1,
    "title": "Example Movie",
    "description": "Movie description",
    "durationMinutes": 120,
    "posterUrl": "/images/movie-1.jpg"
  }
]
```

---

# 3. Movie Shows

## GET `/api/movies/{movieId}/shows`

Returns available shows for a movie.

### Example response

```json
[
  {
    "id": 101,
    "movieId": 1,
    "theatreId": 1,
    "theatreName": "Cinema Hall 1",
    "screenId": 1,
    "screenName": "Screen 1",
    "startTime": "2026-08-08T18:00:00",
    "price": 450
  }
]
```

---

# 4. Seat Map

## GET `/api/shows/{showId}/seats`

Returns the seat map for a specific show.

### Example response

```json
[
  {
    "id": 501,
    "seatId": 101,
    "row": "A",
    "number": 1,
    "price": 450,
    "status": "AVAILABLE"
  },
  {
    "id": 502,
    "seatId": 102,
    "row": "A",
    "number": 2,
    "price": 450,
    "status": "HELD"
  },
  {
    "id": 503,
    "seatId": 103,
    "row": "A",
    "number": 3,
    "price": 450,
    "status": "BOOKED"
  }
]
```

Allowed seat statuses:

```text
AVAILABLE
HELD
BOOKED
```

Expired holds must be treated as effectively available.

---

# 5. Hold Seat

## POST `/api/shows/{showId}/seats/{seatId}/hold`

Attempts to temporarily hold a seat.

### Request

```json
{
  "userId": "user-001"
}
```

The exact authentication mechanism is intentionally simple for the hackathon.

The backend must still identify the user/client that owns the hold.

### Successful response

```http
200 OK
```

Example:

```json
{
  "bookingRef": "BK-001",
  "showId": 101,
  "seatId": 501,
  "status": "PENDING_PAYMENT",
  "holdExpiresAt": "2026-08-08T10:30:30Z",
  "amount": 450
}
```

### Seat unavailable

```http
409 Conflict
```

Example:

```json
{
  "error": "SEAT_UNAVAILABLE",
  "message": "Seat is already held or booked."
}
```

### Critical requirement

This endpoint must be safe under concurrent requests.

If 100 users call this endpoint for the same ShowSeat simultaneously:

```text
Exactly 1 → success
99 → rejected
Oversell → 0
```

The frontend must NOT be responsible for preventing double booking.

The database must enforce the invariant.

---

# 6. Booking Status

## GET `/api/bookings/{bookingRef}`

Returns the current booking/payment state.

### Example

```json
{
  "bookingRef": "BK-001",
  "status": "PENDING_PAYMENT",
  "paymentStatus": "PENDING",
  "showId": 101,
  "seatId": 501,
  "amount": 450
}
```

Possible booking states:

```text
PENDING_PAYMENT
CONFIRMED
PAYMENT_FAILED
EXPIRED
```

Possible payment states:

```text
PENDING
SUCCEEDED
FAILED
REFUNDED
```

---

# 7. Pay

## POST `/api/bookings/{bookingRef}/pay`

Starts payment for a held booking.

### Request

No body required unless the implementation needs additional information.

### Successful response

The endpoint must return quickly.

Example:

```http
202 Accepted
```

```json
{
  "bookingRef": "BK-001",
  "paymentId": "pay_abc123",
  "status": "PENDING"
}
```

### IMPORTANT

This endpoint must NOT wait for final payment success.

The payment gateway is asynchronous.

Flow:

```text
/pay
  ↓
gateway /charge
  ↓
PENDING
  ↓
return
  ↓
later callback
  ↓
final payment state
```

---

# 8. Payment Callback

## POST `/api/payments/callback`

This endpoint is called by the provided gateway.

The gateway sends:

```json
{
  "event_id": "evt_9f2a",
  "payment_id": "pay_abc123",
  "booking_ref": "BK-001",
  "status": "SUCCEEDED",
  "amount": 450,
  "currency": "BDT",
  "timestamp": "2026-08-08T11:03:22.418Z"
}
```

Possible statuses:

```text
SUCCEEDED
FAILED
REFUNDED
```

### Response

For successfully received callbacks:

```http
200 OK
```

### Duplicate callbacks

A duplicate callback carries the same:

```text
event_id
```

The backend must:

1. detect that event_id was already processed
2. do nothing again
3. return 200

Never return 409 for a duplicate callback.

The gateway interprets non-2xx as delivery failure.

---

# 9. OTP Send

## POST `/api/otp/send`

Request:

```json
{
  "phone": "01700000000",
  "ref": "BK-001"
}
```

The backend forwards the request to the provided gateway with a `callback_url`
pointing at `/api/webhooks/otp`. The gateway may silently fail to deliver an OTP
(~10% drop rate). Successful delivery arrives asynchronously at the webhook,
not synchronously from this call.

Successful response:

```http
200 OK
```

```json
{ "ok": true, "session_ref": "sess_..." }
```

---

# 10. OTP Verify

## POST `/api/otp/verify`

Request:

```json
{
  "ref": "BK-001",
  "code": "123456"
}
```

Example response:

```json
{ "verified": true }
```

The provided gateway uses `123456` in deterministic mode. After 5 wrong
attempts the gateway returns HTTP 429.

---

# 10a. OTP Status (poll after send)

## GET `/api/otp/booking/{bookingRef}`

Returns the OTP record the gateway has delivered for this booking, if any.
The frontend polls this every ~2s after `/api/otp/send` until a code shows up
(the gateway may delay 2-15s and silently drop ~10%).

### Found

```http
200 OK
```

```json
{
  "bookingRef": "BK-001",
  "phone": "01700000000",
  "code": "482913",
  "verified": false,
  "attempts": 0,
  "deliveredAt": "2026-08-08T11:03:25.418Z"
}
```

### Not yet delivered

```http
404 Not Found
```

```json
{ "error": "OTP_NOT_DELIVERED", "message": "..." }
```

---

# 10b. OTP Webhook (gateway → backend)

## POST `/api/webhooks/otp`

Called by the provided gateway to deliver the OTP code asynchronously.

Body:

```json
{
  "event_id": "evt_9f2a...",
  "ref": "BK-001",
  "phone": "01700000000",
  "code": "482913",
  "timestamp": "2026-08-08T11:03:25.418Z"
}
```

### Response

```http
200 OK
```

Duplicate deliveries carry the same `event_id`; the backend must dedupe and
return 200. Always return 2xx — the gateway treats non-2xx as delivery
failure and retries with exponential backoff (up to 8 times).

---

# 11. HTTP Status Conventions

Use:

```text
200 OK
```

for successful reads and operations.

```text
201 Created
```

when a new resource is explicitly created.

```text
202 Accepted
```

for asynchronous payment initiation.

```text
400 Bad Request
```

for invalid input.

```text
404 Not Found
```

for missing resources.

```text
409 Conflict
```

for seat conflicts/state conflicts.

```text
500 Internal Server Error
```

only for unexpected server errors.

---

# 12. Error Format

Use a consistent error response.

Example:

```json
{
  "error": "SEAT_UNAVAILABLE",
  "message": "Seat is already held."
}
```

Do not expose stack traces.

---

# 13. API Invariants

The following are non-negotiable:

1. One ShowSeat cannot be successfully held by two users.
2. A BOOKED seat cannot be held.
3. An active HELD seat cannot be held by another user.
4. An expired HELD seat can be reclaimed.
5. A duplicate payment callback must not change state twice.
6. Duplicate callbacks must receive HTTP 200.
7. `/pay` must not wait for final gateway result.
8. `/health` must not depend on gateway availability.