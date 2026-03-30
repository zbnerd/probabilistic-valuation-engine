# Sample Event: donation.created.v1

> **Event Type:** `donation.created.v1`
> **Version:** 1
> **Domain:** Donation
> **Related Issues:** #502

## Purpose

Published when a new donation record is created in the system. This event notifies downstream systems (analytics, notifications, audit logging) about new donation activity.

---

## Event Envelope

```json
{
  "eventId": "660e8400-e29b-41d4-a716-446655440001",
  "eventType": "donation.created.v1",
  "occurredAt": "2025-03-10T15:00:00.456Z",
  "version": "1",
  "producer": "donation-service",
  "idempotencyKey": "donation-d12345",
  "payload": {
    "donationId": "d12345",
    "userId": "u67890",
    "amount": "10000",
    "currency": "KRW",
    "donationType": "ONE_TIME",
    "status": "COMPLETED",
    "createdAt": "2025-03-10T15:00:00.000Z"
  }
}
```

---

## Field Definitions

### Common Fields (Envelope)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `eventId` | String (UUID) | Yes | Unique event identifier for tracing/deduplication |
| `eventType` | String | Yes | Always `"donation.created.v1"` |
| `occurredAt` | String (ISO-8601) | Yes | When donation was recorded |
| `version` | String | Yes | Event schema version: `"1"` |
| `producer` | String | Yes | Service that produced: `"donation-service"` |
| `idempotencyKey` | String | Yes | Key: `donation-{donationId}` |

### Payload Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `donationId` | String | Yes | Unique donation identifier |
| `userId` | String | Yes | User who made the donation |
| `amount` | String | Yes | Donation amount as string (avoid precision loss) |
| `currency` | String | Yes | ISO 4217 currency code |
| `donationType` | String | Yes | Type: `ONE_TIME`, `RECURRING`, `SPONSOR` |
| `status` | String | Yes | Status: `PENDING`, `COMPLETED`, `FAILED`, `REFUNDED` |
| `createdAt` | String (ISO-8601) | Yes | When donation was created in database |

---

## Payload Schema (JSON Schema)

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["donationId", "userId", "amount", "currency", "donationType", "status", "createdAt"],
  "properties": {
    "donationId": {
      "type": "string",
      "description": "Unique donation identifier"
    },
    "userId": {
      "type": "string",
      "description": "User identifier"
    },
    "amount": {
      "type": "string",
      "pattern": "^\\d+(\\.\\d{1,2})?$",
      "description": "Donation amount (as string for precision)"
    },
    "currency": {
      "type": "string",
      "pattern": "^[A-Z]{3}$",
      "description": "ISO 4217 currency code"
    },
    "donationType": {
      "type": "string",
      "enum": ["ONE_TIME", "RECURRING", "SPONSOR"],
      "description": "Type of donation"
    },
    "status": {
      "type": "string",
      "enum": ["PENDING", "COMPLETED", "FAILED", "REFUNDED"],
      "description": "Current donation status"
    },
    "createdAt": {
      "type": "string",
      "format": "date-time",
      "description": "When donation was created"
    }
  }
}
```

---

## Consumer Usage

### Analytics Consumer

```kotlin
fun handle(event: IntegrationEvent<DonationCreatedPayload>) {
    val payload = event.payload
    if (payload.status == "COMPLETED") {
        donationMetrics.recordDonation(
            amount = BigDecimal(payload.amount),
            currency = payload.currency,
            type = payload.donationType
        )
    }
}
```

### Notification Consumer

```kotlin
fun handle(event: IntegrationEvent<DonationCreatedPayload>) {
    val payload = event.payload
    if (payload.status == "COMPLETED") {
        notificationService.sendThankYou(
            userId = payload.userId,
            amount = payload.amount,
            currency = payload.currency
        )
    }
}
```

---

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    Donation Processing                       │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Payment Gateway ──▶ Donation Service ──▶ Event Publisher   │
│                                           │                  │
│                                           ▼                  │
│                            donation.created.v1               │
│                                           │                  │
│                   ┌───────────────────────┼──────────────┐  │
│                   │                       │              │  │
│                   ▼                       ▼              ▼  │
│             Analytics              Notifications      Audit Log│
│             (Metrics)              (Thank You)        (Compliance)│
└─────────────────────────────────────────────────────────────┘
```

---

## Notes

### Amount as String

Amount is stored as string to avoid floating-point precision issues with monetary values. Consumers should convert to `BigDecimal` for calculations.

### Status Transitions

This event is published for the initial creation. Status changes (`COMPLETED`, `FAILED`, `REFUNDED`) may trigger additional events like `donation.updated.v1` in future versions.

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| v1 | 2025-03-10 | Initial definition |
