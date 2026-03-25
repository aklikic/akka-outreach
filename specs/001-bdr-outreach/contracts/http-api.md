# HTTP API Contracts: BDR LinkedIn Outreach Tool

**Feature**: 001-bdr-outreach | **Phase**: 1 | **Date**: 2026-03-25
**Base path**: `/`
**Auth**: None (FR-032)
**Content-Type**: `application/json` unless noted

---

## Static UI

### `GET /`
Serves `index.html` — the single-page application.

---

## Accounts

### `POST /accounts`
Create a new account.

**Request**:
```json
{
  "companyName": "Acme Corp",
  "industry": "Software",
  "website": "https://acme.example.com",
  "targetPersona": "VP Engineering",
  "notes": "Met at KubeCon"
}
```

**Response 201**:
```json
{
  "accountId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "NEW"
}
```

**Response 400**: Missing required field.

---

### `GET /accounts`
List all accounts.

**Response 200**:
```json
{
  "entries": [
    {
      "accountId": "...",
      "companyName": "Acme Corp",
      "industry": "Software",
      "website": "https://acme.example.com",
      "targetPersona": "VP Engineering",
      "notes": "",
      "status": "MESSAGE_READY",
      "signals": ["Hiring AI engineers", "..."],
      "messages": [
        { "messageType": "CONNECTION_REQUEST", "body": "..." },
        { "messageType": "FOLLOW_UP_1", "body": "..." },
        { "messageType": "FOLLOW_UP_2", "body": "..." }
      ],
      "hubspotId": null,
      "createdAt": "2026-03-25T10:00:00Z"
    }
  ]
}
```

---

### `POST /accounts/{accountId}/generate`
Trigger AI message generation for one account.

**Response 200**:
```json
{ "accountId": "...", "status": "NEW" }
```
(Generation runs asynchronously; status updates via SSE)

---

### `POST /accounts/{accountId}/respond`
Mark account as responded. Triggers HubSpot sync automatically.

**Response 200**:
```json
{ "accountId": "...", "status": "RESPONDED" }
```

---

### `GET /accounts/{accountId}/sfdc`
Get Salesforce-ready export payload.

**Response 200**:
```json
{
  "Company": "Acme Corp",
  "Industry": "Software",
  "Website": "https://acme.example.com",
  "TargetPersona": "VP Engineering",
  "LeadStatus": "Responded",
  "IntentSignals": ["...", "...", "..."],
  "LinkedInMessages": {
    "ConnectionRequest": "...",
    "FollowUp1": "...",
    "FollowUp2": "..."
  },
  "HubSpotId": "hs_123456"
}
```

---

## Bulk Upload

### `POST /accounts/bulk`
Upload a CSV and create accounts in bulk.

**Request**: `Content-Type: text/csv` or `multipart/form-data`
Body: CSV with headers `companyName,industry,website,targetPersona,notes`

**Response 200**:
```json
{
  "total": 10,
  "created": 9,
  "rejected": [
    { "rowIndex": 4, "companyName": "", "reason": "companyName is required" }
  ]
}
```

---

### `POST /accounts/generate-all`
Trigger AI generation for all accounts currently in NEW or ERROR status.

**Response 200**:
```json
{ "bulkJobId": "...", "accountCount": 9 }
```
(Processing runs asynchronously; status updates via SSE)

---

## Server-Sent Events

### `GET /events`
Real-time account status stream.

**Response**: `Content-Type: text/event-stream`

Each event is the full current account list (same shape as `GET /accounts`):
```
data: {"entries":[...]}

data: {"entries":[...]}
```

Events are emitted every ~2 seconds. The browser reconnects automatically on disconnect.