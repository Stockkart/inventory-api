# Metrics

Every metric this backend emits, so a dashboard can be built from one list rather than by
grepping for `.record(`. Generated from the `*MetricsConstants` classes and their call sites;
regenerate it when a metric is added, or the dashboard drifts from the code.

All metrics are counters recorded through `MetricsWrapper.record(name, amount, tags...)`, and
every one carries a `module` tag whose value is the module column below. Metrics that add
further tags list them.

**65 metrics across 15 modules.**

## accounting

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_accounting_accounts_total` | `AccountingMetricsConstants.ACCOUNTS_TOTAL` | AccountService |
| `inventory_accounting_backfill_total` | `AccountingMetricsConstants.BACKFILL_TOTAL` | AccountingBackfillService |
| `inventory_accounting_index_failures_total` | `AccountingMetricsConstants.INDEX_FAILURES_TOTAL` | JournalSourceIndexMigration (counter, extra tag `index`: `shop_source_unique`; startup continues, the log line gives the duplicate counts) |
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
| `inventory_documents_generated_total` | `DocumentMetricsConstants.GENERATED_TOTAL` | CreditNotePdfService, InvoicePdfService, MisExcelDocumentService, MisPdfDocumentService, PayslipPdfService (`operation=payslip_pdf`) |

## hr

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_hr_audit_failures_total` | `HrMetricsConstants.AUDIT_FAILURES_TOTAL` | HrAuditService (counter, extra tag `action`) |
| `inventory_hr_attendance_writes_total` | `HrMetricsConstants.ATTENDANCE_WRITES_TOTAL` | AttendanceService (counter, amount = days written, extra tag `operation`: `save`, `mark_unmarked_present`) |
| `inventory_hr_query_timeouts_total` | `HrMetricsConstants.QUERY_TIMEOUTS_TOTAL` | HrQueryRunner (counter, extra tag `query`, e.g. `attendance_month`; the request returns 503 `HR_QUERY_TOO_SLOW`) |
| `inventory_hr_payroll_finalise_total` | `HrMetricsConstants.PAYROLL_FINALISE_TOTAL` | PayrollService (counter, extra tag `outcome`: `success`, `post_failed`, `journal_mismatch`) |
| `inventory_hr_payroll_reopen_total` | `HrMetricsConstants.PAYROLL_REOPEN_TOTAL` | PayrollService (counter, extra tag `outcome`: `success`) |
| `inventory_hr_payroll_calc_total` | `HrMetricsConstants.PAYROLL_CALC_TOTAL` | PayrollService (counter, one per calculation incl. its reads, extra tag `operation`: `create`, `recalculate`, `adjust`, `finalise`) |
| `inventory_hr_payroll_calc_seconds_total` | `HrMetricsConstants.PAYROLL_CALC_SECONDS_TOTAL` | PayrollService (counter, amount = seconds spent; ÷ `payroll_calc_total` = mean; same `operation` tag) |
| `inventory_hr_journal_post_failures_total` | `HrMetricsConstants.JOURNAL_POST_FAILURES_TOTAL` | PayrollService, AdvanceService, SalaryPaymentService (counter, extra tag `source`: `PAYROLL_RUN`, `STAFF_ADVANCE`, `SALARY_PAYMENT`; a payroll run goes back to DRAFT, an advance stays pending and is posted again later, a pay batch goes back to PENDING or is finished by the next pay call) |
| `inventory_hr_salary_payments_total` | `HrMetricsConstants.SALARY_PAYMENTS_TOTAL` | SalaryPaymentService (counter, amount = payroll lines, extra tag `outcome`: `paid`, `undone`, `post_failed`, `run_not_finalised`) |
| `inventory_hr_documents_total` | `HrMetricsConstants.DOCUMENTS_TOTAL` | PayslipService, HrReportService (counter, one per file, extra tag `operation`: `payslip_pdf`, `payslip_email_render`, `month_xlsx`, `leave_balance_xlsx`, `salary_register_xlsx`, `salary_register_pdf`) |
| `inventory_hr_documents_seconds_total` | `HrMetricsConstants.DOCUMENTS_SECONDS_TOTAL` | PayslipService, HrReportService (counter, amount = seconds spent building the file; ÷ `documents_total` = mean; same `operation` tag) |
| `inventory_hr_account_conflicts_total` | `HrMetricsConstants.ACCOUNT_CONFLICTS_TOTAL` | HrAccountingPoster (counter, extra tag `code`: `1250`, `2400`, `5700`; the post is refused with 409 `HR_ACCOUNT_CONFLICT`) |
| `inventory_hr_self_checkin_total` | `HrMetricsConstants.SELF_CHECKIN_TOTAL` | SelfCheckInService (counter, one per `POST /hr/me/check-in`, extra tag `result`: `accepted`, `flagged`, `duplicate`, `off`, `not_linked`, `no_permission`, `not_employed`, `on_leave`, `locked`, `outside_shop`, `location_unclear`, `invalid`, `error`) |

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

## pricing

| Metric | Constant | Emitted by |
|---|---|---|
| `inventory_pricing_rate_default_fallback_total` | `PricingMetricsConstants.RATE_DEFAULT_FALLBACK_TOTAL` | RateGenerationService |
| `inventory_pricing_rate_rule_skipped_total` | `PricingMetricsConstants.RATE_RULE_SKIPPED_TOTAL` | RateGenerationService |

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
| `inventory_product_print_bridge_checks_total` | `ProductMetricsConstants.PRINT_BRIDGE_CHECKS` | PrintBridgeService |
| `inventory_product_print_job_outcomes_total` | `ProductMetricsConstants.PRINT_JOB_OUTCOMES` | PrintJobService |
| `inventory_product_print_jobs_total` | `ProductMetricsConstants.PRINT_JOBS` | PrintJobService |
| `inventory_product_qr_tokens_total` | `ProductMetricsConstants.QR_TOKENS_TOTAL` | QRUploadService |
| `inventory_product_quotations_total` | `ProductMetricsConstants.QUOTATIONS_TOTAL` | QuotationService |
| `inventory_product_rate_rules_updates_total` | `ProductMetricsConstants.RATE_RULES_UPDATES` | RateRulesService |
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
| `inventory_product_print_jobs_total` | `operation`, `outcome` | `sale`, `refund`, `vendor_return`; `bridge`, `download`, `in_progress` |
| `inventory_product_print_job_outcomes_total` | `operation`, `outcome` | the source as above; `printed`, `failed_printer`, `still_queued`, `bridge_unreachable`, `already_sent`, `bridge_rejected`, `unconfirmed` |
| `inventory_product_print_bridge_checks_total` | `outcome` | `not_detected`, `outdated`, `connected` |
| `inventory_pricing_rate_rule_skipped_total` | `reason` | `BELOW_COST` |

## Adding a metric

1. Add the constant to that module's `*MetricsConstants`.
2. Record it through the injected `MetricsWrapper`, passing `module` as the first tag.
3. Add the row here, so the dashboard has it without a code read.
