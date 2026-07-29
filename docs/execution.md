# Execution model

## Request pipeline

For each GraphQL request, the engine runs these steps in order:

1. **Validation** — built-in arg rules, then custom validator (if declared). Fails fast; backend is never called.
2. **Custom resolver** — if `resolver:` is declared, it handles the request entirely and steps 3–5 are skipped.
3. **Backend chain** — steps are grouped into waves by topological sort of their `deps` and executed.
4. **Output mapping** — jq expressions map step results to output fields.
5. **Transformer** — post-processes the mapped output before it is returned.

## Execution waves

Steps within the chain are grouped into waves by topological sort of their `deps`.
Steps in the same wave have no dependency on each other and run in parallel.

```
Wave 0: steps with no unmet deps   → run in parallel (missionary m/join)
Wave 1: steps whose deps are done  → run in parallel
...
```

Within a wave all steps fire simultaneously. Waves execute sequentially — wave N+1
only starts after wave N completes.

## Critical vs non-critical steps

- `critical: true` — if the step fails, the entire chain aborts and the GraphQL
  response contains only errors with no data.
- `critical: false` (default) — failure is recorded, sibling and downstream steps
  still run with `nil` data from this step, and errors appear in the GraphQL
  `errors` array alongside whatever data was successfully resolved.

## Error handling

### Partial failure response

When non-critical steps fail the response includes both data and errors:

```json
{
  "data": {
    "createOrder": {
      "orderId": "ord_123",
      "status": "confirmed",
      "totalAmount": 49.99,
      "notificationSent": false,
      "inventoryReserved": false,
      "warnings": [
        "Order confirmed but notification could not be sent.",
        "Order confirmed but inventory reservation failed."
      ]
    }
  },
  "errors": [
    {
      "message": "Request to https://notification-service/... timed out",
      "extensions": { "code": "timeout", "step": "notify_user" }
    },
    {
      "message": "Backend returned 503",
      "extensions": { "code": "backend-error", "step": "update_inventory" }
    }
  ]
}
```

### Error codes

| Code                 | Cause                                   |
|----------------------|-----------------------------------------|
| `bad-request`        | Backend returned 400                    |
| `unauthorized`       | Backend returned 401                    |
| `forbidden`          | Backend returned 403                    |
| `not-found`          | Backend returned 404                    |
| `unprocessable`      | Backend returned 422                    |
| `backend-error`      | Backend returned 5xx                    |
| `timeout`            | Connection or read timeout              |
| `connection-refused` | Could not connect to backend            |
| `execution-error`    | Critical step failure or spec error     |
| `internal-error`     | Unexpected exception in resolver        |
