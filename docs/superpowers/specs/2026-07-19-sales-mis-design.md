# Sales MIS (customer-side Party Money MIS) — Design

Date: 2026-07-19
Status: implemented on branch `sales-mis` (both `inventory-api` and `inventory-platform`), branched off `MIS`.

## Goal
Add a customer-side "Sales MIS" that mirrors the existing Vendor Money MIS, by
generalizing the `side` parameter rather than duplicating a separate module. It
reports sales, customer receipts, sales returns, and customer credit charges with
cash / online / credit columns and a running **receivable** balance per customer.

## Data sources (backend, `inventory-api`)
- Completed sales = `Purchase` docs (`purchases`), status `COMPLETED`, business
  date `soldAt`. Explicit split fields `cashAmount` / `onlineAmount` /
  `creditAmount`. `customerId` is nullable (walk-in / cash sales).
- Sales returns = `Refund` docs (`refunds`): `refundCash/refundOnline/refundToCredit`,
  linked to a sale by `purchaseId`.
- Customer credit = `CreditEntry` with `CreditPartyType.CUSTOMER` (same repository
  as vendor, party-type parameterized).

## Backend approach
- Extend `PartyMoneyMisController` to accept `side ∈ {VENDOR, CUSTOMER}` on all three
  endpoints (`/`, `/excel`, `/pdf`); route `CUSTOMER` to `getCustomerMis`. Filenames
  switch to `sales-money-mis-*`. `getVendorMis` untouched.
- New `PartyMoneyMisService.getCustomerMis(...)` parallel to `getVendorMis`:
  - Row types (local literals): `SALE` (SSAL-), `CUSTOMER_RECEIPT` (SRCT-, from
    SETTLEMENT; cash/online routed by `paymentMethod`), `SALES_RETURN` (SRET-, legs
    negated), `CUSTOMER_CREDIT_CHARGE` (SCHG-, from CHARGE/ADJUSTMENT).
  - Skips auto credit charges whose `sourceKey` starts with `SALE:CREDIT:` (they
    duplicate the sale's credit leg).
  - Walk-in: `customerId == null` rolls up under a synthetic `WALKIN` party
    ("Walk-in / Cash sale").
- Sign convention (receivable): SALE + CUSTOMER_CREDIT_CHARGE + OPENING increase the
  balance; CUSTOMER_RECEIPT and SALES_RETURN decrease it. Implemented by extending the
  shared `partyDelta` with the customer txn types.
- DTOs reused as-is. `periodPurchaseTotal` carries the period **sales** total;
  `currentPayableTotal` carries the current **receivable** total (documented reuse; a
  report only ever holds one side's rows).
- Excel/PDF writers parameterized off `report.getSide()` ("Sales Money MIS",
  "Current receivable").

## Frontend approach (`inventory-platform`)
- Add `PartyMoneyMisCustomerTxnType` union; `PartyMoneyMisSide` already allowed CUSTOMER.
- `SalesMisPage.tsx` mirrors `VendorMoneyMisPage` with `side: 'CUSTOMER'`, sales txn
  checkboxes, and a "Current receivable" KPI. Row expand shows key fields only (no
  per-sale invoice-detail fetch — kept out of scope for v1).
- `accountingApi.salesMoneyMis / salesMoneyMisExcel / salesMoneyMisPdf` thin wrappers
  pin `side: CUSTOMER` and use `sales-money-mis-*` filenames.
- Wiring: `nav.ts` (both nav groups), `routes.ts` + `routes/sales-mis.tsx`,
  `AccountingTabs.tsx`, `index.ts`.

## Out of scope (YAGNI)
- No CSV export, no new per-sale drill-down endpoint, no generalized payment-breakdown
  class (Purchase already stores the split), no changes to `getVendorMis`.

## Verification
- Backend: `mvn -pl core/analytics -am compile` — clean.
- Frontend: `tsc -b core/accounting/tsconfig.lib.json` — clean (exit 0).
