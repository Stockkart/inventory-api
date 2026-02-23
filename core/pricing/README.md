# Pricing Module

Handles all pricing-related operations for inventory items. Stores MRP, SP, CP, CGST, SGST, and additionalDiscount. HSN and batchNo remain in the Inventory module.

## Structure

- **domain/model** - `Pricing` entity (MongoDB)
- **domain/repository** - `PricingRepository`
- **rest/dto** - `CreatePricingRequest`, `UpdatePricingRequest`, `PricingResponse`
- **rest/mapper** - MapStruct mappers
- **service** - `PricingService`
- **validation** - `PricingValidator`

## Write Operations

- **Create**: When inventory items are created (single or bulk API), pricing data is persisted here.
- **Update**: When inventory is updated with pricing fields (e.g. `additionalDiscount`), the Pricing module is updated.

## Write Operations Only

- **Create/Update**: Pricing data is written to both Inventory (for current reads) and the Pricing module.
- **Reads**: Currently served from Inventory. A different read strategy will be implemented later.
- **Pricing module stores**: MRP, SP, CP, CGST, SGST, additionalDiscount (no hsn/batchNo; those stay in Inventory).
