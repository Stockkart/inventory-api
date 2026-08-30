# Metrics

Every metric this backend emits, so a dashboard can be built from one list rather than by
grepping for `.record(`. Generated from the `*MetricsConstants` classes and their call sites;
regenerate it when a metric is added, or the dashboard drifts from the code.

All metrics are counters recorded through `MetricsWrapper.record(name, amount, tags...)`, and
every one carries a `module` tag whose value is the module column below. Metrics that add
further tags list them.

**45 metrics across 13 modules.**

## accounting

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_accounting_accounts_total` | `AccountingMetricsConstants.ACCOUNTS_TOTAL` | AccountService |
| `inventory_accounting_backfill_total` | `AccountingMetricsConstants.BACKFILL_TOTAL` | AccountingBackfillService |
| `inventory_accounting_journals_total` | `AccountingMetricsConstants.JOURNALS_TOTAL` | AccountingPostingService |

## analytics

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_analytics_queries_total` | `AnalyticsMetricsConstants.QUERIES_TOTAL` | CustomerAnalyticsService, InventoryAnalyticsService, ProfitAnalyticsService, SalesAnalyticsService, VendorAnalyticsService |
| `inventory_mis_reports_total` | `AnalyticsMetricsConstants.MIS_REPORTS_TOTAL` | CustomerMoneyMisService, SalesMisService, StockMisService, VendorMoneyMisService |

## cafe

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_cafe_tokens_total` | `CafeMetricsConstants.TOKENS_TOTAL` | CafeCheckoutCompletionHandler, CafeTokenService |

## credit

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_credit_entries_total` | `CreditMetricsConstants.ENTRIES_TOTAL` | CreditService |

## documentservice

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_documents_generated_total` | `DocumentMetricsConstants.GENERATED_TOTAL` | CreditNotePdfService, InvoicePdfService, MisExcelDocumentService, MisPdfDocumentService |

## notifications

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_notifications_enqueued_total` | `NotificationMetricsConstants.ENQUEUED_TOTAL` | MessagingService |
| `inventory_notifications_sent_total` | `NotificationMetricsConstants.SENT_TOTAL` | MessageQueueProcessor |

## ocr

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_ocr_items` | `OcrMetricsConstants.ITEMS` | OcrService |
| `inventory_ocr_parse` | `OcrMetricsConstants.PARSE` | OcrService |
| `inventory_ocr_parse_total` | `OcrMetricsConstants.PARSE_TOTAL` | OcrService |

## plan

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_plan_assigned_total` | `PlanMetricsConstants.ASSIGNED_TOTAL` | PlanService |
| `inventory_plan_gateway` | `PlanMetricsConstants.GATEWAY` | RazorpayPaymentGateway |
| `inventory_plan_payments_total` | `PlanMetricsConstants.PAYMENTS_TOTAL` | PlanPaymentService |
| `inventory_plan_webhooks_total` | `PlanMetricsConstants.WEBHOOKS_TOTAL` | PlanPaymentService |

## product

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_product_barcodes_generated_total` | `ProductMetricsConstants.BARCODES_GENERATED` | BarcodeService |
| `inventory_product_cart_created_total` | `ProductMetricsConstants.CART_CREATED` | CheckoutService |
| `inventory_product_cart_updated_total` | `ProductMetricsConstants.CART_UPDATED` | CheckoutService |
| `inventory_product_credit_notes_total` | `ProductMetricsConstants.CREDIT_NOTES_TOTAL` | CreditNoteService |
| `inventory_product_inventory_corrections_total` | `ProductMetricsConstants.INVENTORY_CORRECTIONS` | InventoryCorrectionService |
| `inventory_product_inventory_items_added` | `ProductMetricsConstants.INVENTORY_ITEMS_ADDED` | InventoryService |
| `inventory_product_inventory_operations_total` | `ProductMetricsConstants.INVENTORY_OPERATION` | InventoryService |
| `inventory_product_invoices_generated_total` | `ProductMetricsConstants.INVOICES_GENERATED` | InvoiceService |
| `inventory_product_orders_amount` | `ProductMetricsConstants.ORDERS_AMOUNT` | CheckoutService |
| `inventory_product_orders_completed_total` | `ProductMetricsConstants.ORDERS_COMPLETED` | CheckoutService |
| `inventory_product_qr_tokens_total` | `ProductMetricsConstants.QR_TOKENS_TOTAL` | QRUploadService |
| `inventory_product_quotations_total` | `ProductMetricsConstants.QUOTATIONS_TOTAL` | QuotationService |
| `inventory_product_refund_amount` | `ProductMetricsConstants.REFUND_AMOUNT` | RefundService |
| `inventory_product_refunds_total` | `ProductMetricsConstants.REFUNDS_TOTAL` | RefundService |
| `inventory_product_vendor_purchases_total` | `ProductMetricsConstants.VENDOR_PURCHASES` | InventoryService |
| `inventory_product_vendor_returns_total` | `ProductMetricsConstants.VENDOR_RETURNS` | VendorPurchaseReturnService |

## reminders

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_reminders_dispatch_total` | `ReminderMetricsConstants.DISPATCH_TOTAL` | EventService |
| `inventory_reminders_mutations_total` | `ReminderMetricsConstants.MUTATIONS_TOTAL` | ReminderService |

## resource

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_resource_tutorial_views_total` | `ResourceMetricsConstants.TUTORIAL_VIEWS_TOTAL` | TutorialResourceService |

## taxation

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_taxation_reports_total` | `TaxationMetricsConstants.REPORTS_TOTAL` | Gstr1ReportService, Gstr2ReportService, Gstr3bReportService |

## user

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_user_auth_total` | `UserMetricsConstants.AUTH_TOTAL` | AuthService |
| `inventory_user_customers_total` | `UserMetricsConstants.CUSTOMERS_TOTAL` | CustomerService |
| `inventory_user_invitations_total` | `UserMetricsConstants.INVITATIONS_TOTAL` | InvitationService |
| `inventory_user_join_requests_total` | `UserMetricsConstants.JOIN_REQUESTS_TOTAL` | JoinRequestService |
| `inventory_user_rbac_updates_total` | `UserMetricsConstants.RBAC_UPDATES_TOTAL` | RbacService |
| `inventory_user_shop_switch_total` | `UserMetricsConstants.SHOP_SWITCH` | UserShopMembershipService |
| `inventory_user_shops_created_total` | `UserMetricsConstants.SHOPS_CREATED` | ShopService |
| `inventory_user_vendors_total` | `UserMetricsConstants.VENDORS_TOTAL` | VendorService |

## Extra tags

Some call sites add a second tag beside `module`. The ones that matter for a dashboard:

| Metric | Tag | Values seen |
|---|---|---|
| `document.generated.total` | `operation` | `invoice_pdf`, and one per document kind |

## Adding a metric

1. Add the constant to that module's `*MetricsConstants`.
2. Record it through the injected `MetricsWrapper`, passing `module` as the first tag.
3. Add the row here, so the dashboard has it without a code read.
