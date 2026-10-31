# API and messaging contracts

The generated OpenAPI document describes the combined lite API. Each distributed service exposes its own `/v3/api-docs` internally. Native development Swagger UI is at `http://localhost:18080/swagger-ui/index.html`. Obtain an access token from login and use Bearer authorization for protected endpoints.

The event envelope is versioned in `events.schema.json`. Payloads are interpreted by the receiving service:

| Command/event | Target | Payload |
|---|---|---|
| Reserve | inventory | `items: [{skuId, quantity}]` |
| Commit, Release | inventory | empty object |
| Consume | inventory | `warehouseId, shipmentId` |
| Restock | inventory | `returnId, warehouseId, skuId, quantity, disposition` |
| StockReserved, StockCommitted | orders | `allocations: [{warehouseId, skuId, quantity}]` |
| StockRejected | orders | `reason` |
| StockExpired, StockReleased | orders | empty object |
| CreateFulfillment | fulfillment | `customerId, allocations, items` (snapshotted price/name) |
| CancelFulfillment | fulfillment | `customerId` |
| FulfillmentReady, FulfillmentCancelled | orders | empty object |
| CancellationRejected, ShipmentProgress | orders | `state` |
| StockConsumed | fulfillment | `shipmentId` |
| ReturnRestocked | fulfillment | `returnId` |
| OrderConfirmed | notification | `email, orderId` |

The aggregate ID is the order ID for order workflows. Consumers validate schema version, target, business state, and relevant quantities. This is a versioned JSON contract, not a deployed schema registry. Update producers, consumers, tests, and this document together when changing it.
