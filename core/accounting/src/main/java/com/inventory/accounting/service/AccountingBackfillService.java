package com.inventory.accounting.service;

import com.inventory.accounting.api.AccountingFacade;
import com.inventory.accounting.api.PartyCreditChargePostingRequest;
import com.inventory.accounting.api.PartySettlementPostingRequest;
import com.inventory.accounting.api.SaleInvoicePostingRequest;
import com.inventory.accounting.api.SalesReturnPostingRequest;
import com.inventory.accounting.api.VendorPurchaseInvoicePostingRequest;
import com.inventory.accounting.api.VendorPurchaseReturnPostingRequest;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Replays historical business events through the {@link AccountingFacade} so a shop turning the
 * accounting module on for the first time has a full retrospective ledger. Reads source documents
 * directly via {@link MongoTemplate} to avoid an inverse dependency on the product module.
 *
 * <p>Idempotent — entries already present in {@code journal_entries} are skipped by the
 * (shopId, sourceType, sourceId) unique index.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingBackfillService {

  private static final String VENDOR_PURCHASE_INVOICES = "vendor_purchase_invoices";
  private static final String VENDORS = "vendors";
  private static final String SHOPS = "shops";
  private static final String INVENTORIES = "inventory";
  private static final String PRICING = "pricing";
  private static final String CREDIT_ENTRIES = "credit_entries";
  private static final String PURCHASES = "purchases";
  private static final String CUSTOMERS = "customers";
  private static final String REFUNDS = "refunds";
  private static final String VENDOR_PURCHASE_RETURNS = "vendor_purchase_returns";

  private final MongoTemplate mongoTemplate;
  private final AccountingFacade accountingFacade;
  private final JournalEntryRepository journalEntryRepository;
  private final LedgerEntryRepository ledgerEntryRepository;
  private final AccountService accountService;

  public BackfillResult backfill(String shopId, String userId, LocalDate from, LocalDate to) {
    return backfill(shopId, userId, from, to, false);
  }

  /**
   * Replays vendor purchase invoices through the accounting facade.
   *
   * <p>{@code force=false} (default) is idempotent — invoices that already have a posted journal
   * entry are skipped. {@code force=true} first deletes any existing journal entry (and its ledger
   * rows) for each invoice in scope and then re-posts through the current logic. Use this after
   * tweaking shop-level GST / payment / chart-of-accounts settings to make the books reflect the
   * new configuration.
   */
  public BackfillResult backfill(
      String shopId, String userId, LocalDate from, LocalDate to, boolean force) {
    accountService.ensureSeeded(shopId);
    int processed = 0;
    int skipped = 0;
    int posted = 0;
    int reposted = 0;
    int failed = 0;

    Criteria criteria = Criteria.where("shopId").is(shopId);
    if (from != null) {
      criteria = criteria.and("createdAt").gte(Date.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));
    }
    if (to != null) {
      Date toExclusive =
          Date.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
      criteria =
          criteria.andOperator(
              Criteria.where("createdAt").lt(toExclusive),
              from != null
                  ? Criteria.where("createdAt")
                      .gte(Date.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()))
                  : new Criteria());
    }
    Query q = new Query(criteria);

    Document shopDoc =
        mongoTemplate.findOne(
            new Query(Criteria.where("_id").is(shopId)), Document.class, SHOPS);
    BigDecimal shopCgstPct = parsePercentage(shopDoc != null ? shopDoc.getString("cgst") : null);
    BigDecimal shopSgstPct = parsePercentage(shopDoc != null ? shopDoc.getString("sgst") : null);

    List<Document> invoices = mongoTemplate.find(q, Document.class, VENDOR_PURCHASE_INVOICES);
    for (Document inv : invoices) {
      processed++;
      String invoiceId = stringId(inv.get("_id"));
      if (invoiceId == null) {
        failed++;
        continue;
      }
      Optional<JournalEntry> existing =
          journalEntryRepository.findByShopIdAndSourceTypeAndSourceId(
              shopId, JournalSource.VENDOR_PURCHASE_INVOICE, invoiceId);
      boolean alreadyPosted = existing.isPresent();
      if (alreadyPosted && !force) {
        skipped++;
        continue;
      }
      try {
        VendorPurchaseInvoicePostingRequest req =
            toRequest(invoiceId, inv, shopCgstPct, shopSgstPct);
        if (req == null) {
          skipped++;
          continue;
        }
        if (alreadyPosted) {
          deleteExistingPosting(shopId, existing.get());
        }
        accountingFacade.postVendorPurchaseInvoice(shopId, userId, req);
        if (alreadyPosted) {
          reposted++;
        } else {
          posted++;
        }
      } catch (Exception ex) {
        failed++;
        log.warn(
            "Backfill failed for vendor purchase invoice {} shop {}: {}",
            invoiceId,
            shopId,
            ex.getMessage());
      }
    }

    Criteria saleCriteria =
        Criteria.where("shopId").is(shopId).and("status").is("COMPLETED");
    if (from != null) {
      saleCriteria =
          saleCriteria.and("createdAt").gte(Date.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));
    }
    if (to != null) {
      Date toExclusive =
          Date.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
      saleCriteria = saleCriteria.and("createdAt").lt(toExclusive);
    }
    List<Document> sales = mongoTemplate.find(new Query(saleCriteria), Document.class, PURCHASES);
    for (Document sale : sales) {
      processed++;
      String saleId = stringId(sale.get("_id"));
      if (saleId == null) {
        failed++;
        continue;
      }
      Optional<JournalEntry> existingSale =
          journalEntryRepository.findByShopIdAndSourceTypeAndSourceId(
              shopId, JournalSource.SALE, saleId);
      boolean saleAlreadyPosted = existingSale.isPresent();
      if (saleAlreadyPosted && !force) {
        skipped++;
        continue;
      }
      try {
        SaleInvoicePostingRequest saleReq = toSaleRequest(saleId, sale, shopCgstPct, shopSgstPct);
        if (saleReq == null) {
          skipped++;
          continue;
        }
        if (saleAlreadyPosted) {
          deleteExistingPosting(shopId, existingSale.get());
        }
        accountingFacade.postSale(shopId, userId, saleReq);
        if (saleAlreadyPosted) {
          reposted++;
        } else {
          posted++;
        }
      } catch (Exception ex) {
        failed++;
        log.warn(
            "Backfill failed for sale {} shop {}: {}",
            saleId,
            shopId,
            ex.getMessage());
      }
    }

    Criteria returnDate = Criteria.where("shopId").is(shopId);
    if (from != null) {
      returnDate =
          returnDate.and("createdAt").gte(Date.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));
    }
    if (to != null) {
      returnDate =
          returnDate.and("createdAt")
              .lt(Date.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }
    List<Document> vendorReturns =
        mongoTemplate.find(new Query(returnDate), Document.class, VENDOR_PURCHASE_RETURNS);
    for (Document vr : vendorReturns) {
      processed++;
      String returnId = stringId(vr.get("_id"));
      if (returnId == null) {
        failed++;
        continue;
      }
      Optional<JournalEntry> existingVr =
          journalEntryRepository.findByShopIdAndSourceTypeAndSourceId(
              shopId, JournalSource.VENDOR_PURCHASE_RETURN, returnId);
      boolean vrPosted = existingVr.isPresent();
      if (vrPosted && !force) {
        skipped++;
        continue;
      }
      try {
        VendorPurchaseReturnPostingRequest vrReq = toVendorReturnRequest(returnId, vr);
        if (vrReq == null) {
          skipped++;
          continue;
        }
        if (vrPosted) {
          deleteExistingPosting(shopId, existingVr.get());
        }
        accountingFacade.postVendorPurchaseReturn(shopId, userId, vrReq);
        if (vrPosted) {
          reposted++;
        } else {
          posted++;
        }
      } catch (Exception ex) {
        failed++;
        log.warn(
            "Backfill failed for vendor purchase return {} shop {}: {}",
            returnId,
            shopId,
            ex.getMessage());
      }
    }

    List<Document> refundDocs = mongoTemplate.find(new Query(returnDate), Document.class, REFUNDS);
    for (Document refundDoc : refundDocs) {
      processed++;
      String refundId = stringId(refundDoc.get("_id"));
      if (refundId == null) {
        failed++;
        continue;
      }
      Optional<JournalEntry> existingRefund =
          journalEntryRepository.findByShopIdAndSourceTypeAndSourceId(
              shopId, JournalSource.SALES_RETURN, refundId);
      boolean refundPosted = existingRefund.isPresent();
      if (refundPosted && !force) {
        skipped++;
        continue;
      }
      try {
        SalesReturnPostingRequest refundReq = toSalesReturnRequest(shopId, refundId, refundDoc);
        if (refundReq == null) {
          skipped++;
          continue;
        }
        if (refundPosted) {
          deleteExistingPosting(shopId, existingRefund.get());
        }
        accountingFacade.postSalesReturn(shopId, userId, refundReq);
        if (refundPosted) {
          reposted++;
        } else {
          posted++;
        }
      } catch (Exception ex) {
        failed++;
        log.warn(
            "Backfill failed for sales return {} shop {}: {}",
            refundId,
            shopId,
            ex.getMessage());
      }
    }

    int[] settlementCounts = backfillCreditSettlements(shopId, userId, from, to);
    posted += settlementCounts[0];
    skipped += settlementCounts[1];
    failed += settlementCounts[2];

    int[] chargeCounts = backfillCreditCharges(shopId, userId, from, to);
    posted += chargeCounts[0];
    skipped += chargeCounts[1];
    failed += chargeCounts[2];

    // Re-run the seeder so retired codes (e.g. legacy IGST accounts) that no longer have any
    // ledger activity after the rebuild get pruned automatically.
    if (force) {
      accountService.ensureSeeded(shopId);
    }
    return new BackfillResult(processed, posted, reposted, skipped, failed);
  }

  /**
   * Posts journal entries for historical credit settlements that pre-date accounting integration.
   * Returns {@code [posted, skipped, failed]}.
   */
  private int[] backfillCreditSettlements(
      String shopId, String userId, LocalDate from, LocalDate to) {
    int posted = 0;
    int skipped = 0;
    int failed = 0;

    Criteria c = Criteria.where("shopId").is(shopId).and("entryType").is("SETTLEMENT");
    if (from != null) {
      c = c.and("createdAt").gte(Date.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));
    }
    if (to != null) {
      c =
          c.and("createdAt")
              .lt(Date.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    List<Document> rows = mongoTemplate.find(new Query(c), Document.class, CREDIT_ENTRIES);
    for (Document row : rows) {
      String entryId = stringId(row.get("_id"));
      if (entryId == null) {
        failed++;
        continue;
      }
      if (shouldSkipSettlementBackfill(row)) {
        skipped++;
        continue;
      }
      String partyTypeRaw = row.getString("partyType");
      boolean vendor = "VENDOR".equalsIgnoreCase(partyTypeRaw);
      JournalSource sourceType =
          vendor ? JournalSource.VENDOR_PAYMENT : JournalSource.CUSTOMER_SETTLEMENT;
      String settlementId = settlementIdFromCreditRow(row, entryId);
      if (journalEntryRepository
          .findByShopIdAndSourceTypeAndSourceId(shopId, sourceType, settlementId)
          .isPresent()) {
        skipped++;
        continue;
      }
      String partyId = row.getString("partyRefId");
      if (partyId == null || partyId.isBlank()) {
        failed++;
        continue;
      }
      String partyName = resolvePartyDisplayName(shopId, partyTypeRaw, partyId);
      BigDecimal amount = toBigDecimal(row.get("amount"));
      if (amount.signum() <= 0) {
        skipped++;
        continue;
      }
      try {
        PartySettlementPostingRequest req =
            PartySettlementPostingRequest.builder()
                .sourceId(settlementId)
                .txnDate(toLocalDate(row.get("txnDate")))
                .paymentMethod(inferPaymentMethod(row))
                .amount(amount)
                .partyId(partyId)
                .partyDisplayName(partyName)
                .bankRef(row.getString("bankRef"))
                .narration(row.getString("note"))
                .build();
        if (vendor) {
          accountingFacade.postVendorPayment(shopId, userId, req);
        } else {
          accountingFacade.postCustomerSettlement(shopId, userId, req);
        }
        posted++;
      } catch (Exception ex) {
        failed++;
        log.warn(
            "Settlement backfill failed for credit entry {} shop {}: {}",
            entryId,
            shopId,
            ex.getMessage());
      }
    }
    return new int[] {posted, skipped, failed};
  }

  /**
   * Posts journal entries for historical credit charges (UI “You owe more” / “They owe more”) that
   * are not already covered by a purchase or sale invoice JE. Returns {@code [posted, skipped,
   * failed]}.
   */
  private int[] backfillCreditCharges(String shopId, String userId, LocalDate from, LocalDate to) {
    int posted = 0;
    int skipped = 0;
    int failed = 0;

    Criteria c = Criteria.where("shopId").is(shopId).and("entryType").is("CHARGE");
    if (from != null) {
      c = c.and("createdAt").gte(Date.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));
    }
    if (to != null) {
      c =
          c.and("createdAt")
              .lt(Date.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    List<Document> rows = mongoTemplate.find(new Query(c), Document.class, CREDIT_ENTRIES);
    for (Document row : rows) {
      String entryId = stringId(row.get("_id"));
      if (entryId == null) {
        failed++;
        continue;
      }
      if (shouldSkipChargeBackfill(shopId, row)) {
        skipped++;
        continue;
      }
      String partyTypeRaw = row.getString("partyType");
      boolean vendor = "VENDOR".equalsIgnoreCase(partyTypeRaw);
      JournalSource sourceType =
          vendor ? JournalSource.VENDOR_CREDIT_CHARGE : JournalSource.CUSTOMER_CREDIT_CHARGE;
      String chargeId = settlementIdFromCreditRow(row, entryId);
      if (journalEntryRepository
          .findByShopIdAndSourceTypeAndSourceId(shopId, sourceType, chargeId)
          .isPresent()) {
        skipped++;
        continue;
      }
      String partyId = row.getString("partyRefId");
      if (partyId == null || partyId.isBlank()) {
        failed++;
        continue;
      }
      BigDecimal amount = toBigDecimal(row.get("amount"));
      if (amount.signum() <= 0) {
        skipped++;
        continue;
      }
      try {
        PartyCreditChargePostingRequest req =
            PartyCreditChargePostingRequest.builder()
                .sourceId(chargeId)
                .txnDate(toLocalDate(row.get("txnDate")))
                .amount(amount)
                .partyId(partyId)
                .partyDisplayName(partyId)
                .narration(row.getString("note"))
                .build();
        if (vendor) {
          accountingFacade.postVendorCreditCharge(shopId, userId, req);
        } else {
          accountingFacade.postCustomerCreditCharge(shopId, userId, req);
        }
        posted++;
      } catch (Exception ex) {
        failed++;
        log.warn(
            "Charge backfill failed for credit entry {} shop {}: {}",
            entryId,
            shopId,
            ex.getMessage());
      }
    }
    return new int[] {posted, skipped, failed};
  }

  private static boolean shouldSkipSettlementBackfill(Document row) {
    if ("RETURN".equalsIgnoreCase(row.getString("entryType"))) {
      return true;
    }
    String sourceKey = row.getString("sourceKey");
    if (sourceKey != null && sourceKey.trim().toUpperCase().startsWith("RETURN:CREDIT:")) {
      return true;
    }
    String refType = row.getString("referenceType");
    return "REFUND".equalsIgnoreCase(refType) || "VENDOR_RETURN".equalsIgnoreCase(refType);
  }

  private boolean shouldSkipChargeBackfill(String shopId, Document row) {
    String sourceKey = row.getString("sourceKey");
    if (sourceKey != null) {
      String sk = sourceKey.trim().toUpperCase();
      if (sk.startsWith("PURCHASE:CREDIT:") || sk.startsWith("SALE:CREDIT:")) {
        return true;
      }
    }
    String refType = row.getString("referenceType");
    String refId = row.getString("referenceId");
    if ("PURCHASE".equalsIgnoreCase(refType) && refId != null && !refId.isBlank()) {
      return journalEntryRepository
          .findByShopIdAndSourceTypeAndSourceId(
              shopId, JournalSource.VENDOR_PURCHASE_INVOICE, refId.trim())
          .isPresent();
    }
    if ("SALE".equalsIgnoreCase(refType) && refId != null && !refId.isBlank()) {
      return journalEntryRepository
          .findByShopIdAndSourceTypeAndSourceId(shopId, JournalSource.SALE, refId.trim())
          .isPresent();
    }
    return false;
  }

  private static String settlementIdFromCreditRow(Document row, String entryId) {
    String sourceKey = row.getString("sourceKey");
    if (sourceKey != null && !sourceKey.isBlank()) {
      int colon = sourceKey.indexOf(':');
      if (colon > 0 && colon < sourceKey.length() - 1) {
        return sourceKey.substring(colon + 1).trim();
      }
      return sourceKey.trim();
    }
    return entryId;
  }

  private String resolvePartyDisplayName(String shopId, String partyTypeRaw, String partyId) {
    if ("CUSTOMER".equalsIgnoreCase(partyTypeRaw)) {
      Document customer =
          mongoTemplate.findOne(
              new Query(Criteria.where("_id").is(partyId)), Document.class, CUSTOMERS);
      if (customer != null && StringUtils.hasText(customer.getString("name"))) {
        return customer.getString("name").trim();
      }
      return "Customer";
    }
    Document vendor =
        mongoTemplate.findOne(new Query(Criteria.where("_id").is(partyId)), Document.class, VENDORS);
    if (vendor != null && StringUtils.hasText(vendor.getString("name"))) {
      return vendor.getString("name").trim();
    }
    return "Vendor";
  }

  private static String inferPaymentMethod(Document row) {
    String method = row.getString("paymentMethod");
    if (method != null && !method.isBlank()) {
      return method.trim().toUpperCase();
    }
    String note = row.getString("note");
    if (note != null && note.toLowerCase().contains("cash")) {
      return "CASH";
    }
    return "BANK";
  }

  /**
   * Hard-deletes a journal entry and all of its ledger rows. Used by the {@code force=true} path
   * to make room for a fresh posting; the facade itself never deletes — it only posts forward,
   * which is why we go through the repositories here.
   */
  private void deleteExistingPosting(String shopId, JournalEntry je) {
    var ledgerRows =
        ledgerEntryRepository.findByShopIdAndJournalEntryId(shopId, je.getId());
    if (!ledgerRows.isEmpty()) {
      ledgerEntryRepository.deleteAll(ledgerRows);
    }
    // Also delete any reversal mate so the new posting starts from a clean slate.
    if (je.getReversedByEntryId() != null) {
      journalEntryRepository
          .findById(je.getReversedByEntryId())
          .ifPresent(
              rev -> {
                var revRows =
                    ledgerEntryRepository.findByShopIdAndJournalEntryId(shopId, rev.getId());
                if (!revRows.isEmpty()) ledgerEntryRepository.deleteAll(revRows);
                journalEntryRepository.delete(rev);
              });
    }
    journalEntryRepository.delete(je);
  }

  private VendorPurchaseReturnPostingRequest toVendorReturnRequest(String returnId, Document vr) {
    String invoiceId = vr.getString("vendorPurchaseInvoiceId");
    if (invoiceId == null || invoiceId.isBlank()) {
      return null;
    }
    Document invoice =
        mongoTemplate.findOne(
            new Query(Criteria.where("_id").is(invoiceId)), Document.class, VENDOR_PURCHASE_INVOICES);
    if (invoice == null) {
      return null;
    }
    String vendorId = invoice.getString("vendorId");
    if (vendorId == null || vendorId.isBlank()) {
      return null;
    }
    BigDecimal goods = BigDecimal.ZERO;
    BigDecimal cgst = BigDecimal.ZERO;
    BigDecimal sgst = BigDecimal.ZERO;
    Object itemsObj = vr.get("items");
    if (itemsObj instanceof List<?> items) {
      for (Object o : items) {
        if (!(o instanceof Document line)) continue;
        goods = goods.add(toBigDecimal(line.get("taxableValue")));
        cgst = cgst.add(toBigDecimal(line.get("centralTaxAmount")));
        sgst = sgst.add(toBigDecimal(line.get("stateUtTaxAmount")));
      }
    }
    BigDecimal returnTotal = toBigDecimal(vr.get("returnAmount"));
    if (returnTotal.signum() <= 0) {
      returnTotal = goods.add(cgst).add(sgst);
    }
    BigDecimal preRound = goods.add(cgst).add(sgst).setScale(2, java.math.RoundingMode.HALF_UP);
    BigDecimal roundOff =
        returnTotal.subtract(preRound).setScale(4, java.math.RoundingMode.HALF_UP);

    Document vendorDoc =
        mongoTemplate.findOne(new Query(Criteria.where("_id").is(vendorId)), Document.class, VENDORS);

    return VendorPurchaseReturnPostingRequest.builder()
        .sourceId(returnId)
        .supplierCreditNoteNo(vr.getString("supplierCreditNoteNo"))
        .txnDate(toLocalDate(vr.get("createdAt")))
        .vendorId(vendorId)
        .vendorDisplayName(vendorDoc != null ? vendorDoc.getString("name") : null)
        .originalInvoiceId(invoiceId)
        .originalInvoiceNo(invoice.getString("invoiceNo"))
        .goodsValue(goods)
        .inputCgst(cgst)
        .inputSgst(sgst)
        .returnTotal(returnTotal)
        .roundOff(roundOff)
        .refundCash(toBigDecimal(vr.get("refundCash")))
        .refundOnline(toBigDecimal(vr.get("refundOnline")))
        .refundToCredit(resolveReturnRefundToCredit(vr, returnTotal))
        .paymentMethod(
            vr.getString("paymentMethod") != null
                ? vr.getString("paymentMethod")
                : invoice.getString("paymentMethod"))
        .build();
  }

  private SalesReturnPostingRequest toSalesReturnRequest(
      String shopId, String refundId, Document refundDoc) {
    BigDecimal returnTotal = toBigDecimal(refundDoc.get("refundAmount"));
    if (returnTotal.signum() <= 0) {
      return null;
    }
    BigDecimal taxable = toBigDecimal(refundDoc.get("taxableTotal"));
    BigDecimal cgst = toBigDecimal(refundDoc.get("cgstAmount"));
    BigDecimal sgst = toBigDecimal(refundDoc.get("sgstAmount"));
    if (taxable.signum() <= 0 && cgst.signum() <= 0 && sgst.signum() <= 0) {
      return null;
    }
    String purchaseId = refundDoc.getString("purchaseId");
    Document purchase =
        purchaseId != null
            ? mongoTemplate.findOne(
                new Query(Criteria.where("_id").is(purchaseId)), Document.class, PURCHASES)
            : null;
    String customerId = refundDoc.getString("customerId");
    if ((customerId == null || customerId.isBlank()) && purchase != null) {
      customerId = purchase.getString("customerId");
    }
    String customerName = purchase != null ? purchase.getString("customerName") : null;
    if ((customerName == null || customerName.isBlank()) && customerId != null) {
      customerName = resolvePartyDisplayName(shopId, "CUSTOMER", customerId);
    }
    BigDecimal refundCash = toBigDecimal(refundDoc.get("refundCash"));
    BigDecimal refundOnline = toBigDecimal(refundDoc.get("refundOnline"));
    BigDecimal refundToCredit = toBigDecimal(refundDoc.get("refundToCredit"));
    if (refundCash.signum() == 0 && refundOnline.signum() == 0 && refundToCredit.signum() == 0) {
      refundToCredit = returnTotal;
    }
    BigDecimal roundOff = toBigDecimal(refundDoc.get("roundOff"));
    if (roundOff.signum() == 0) {
      BigDecimal preRound = taxable.add(cgst).add(sgst);
      roundOff = returnTotal.subtract(preRound).setScale(4, java.math.RoundingMode.HALF_UP);
    }
    return SalesReturnPostingRequest.builder()
        .sourceId(refundId)
        .creditNoteNo(refundDoc.getString("creditNoteNo"))
        .txnDate(toLocalDate(refundDoc.get("createdAt")))
        .customerId(customerId)
        .customerDisplayName(customerName)
        .originalSaleId(purchaseId)
        .originalInvoiceNo(purchase != null ? purchase.getString("invoiceNo") : null)
        .taxableRevenue(taxable)
        .outputCgst(cgst)
        .outputSgst(sgst)
        .returnTotal(returnTotal)
        .cogsAmount(toBigDecimal(refundDoc.get("cogsTotal")))
        .roundOff(roundOff)
        .refundCash(refundCash)
        .refundOnline(refundOnline)
        .refundToCredit(refundToCredit)
        .paymentMethod(
            refundDoc.getString("paymentMethod") != null
                ? refundDoc.getString("paymentMethod")
                : (purchase != null ? purchase.getString("paymentMethod") : null))
        .build();
  }

  private SaleInvoicePostingRequest toSaleRequest(
      String saleId, Document sale, BigDecimal shopCgstPct, BigDecimal shopSgstPct) {
    BigDecimal saleTotal = toBigDecimal(sale.get("grandTotal"));
    if (saleTotal.signum() <= 0) {
      return null;
    }

    BigDecimal revenue = toBigDecimal(sale.get("revenueBeforeTax"));
    if (revenue.signum() <= 0) {
      BigDecimal subTotal = toBigDecimal(sale.get("subTotal"));
      BigDecimal additional = toBigDecimal(sale.get("saleAdditionalDiscountTotal"));
      revenue = subTotal.subtract(additional).max(BigDecimal.ZERO);
    }

    BigDecimal cgst = toBigDecimal(sale.get("cgstAmount"));
    BigDecimal sgst = toBigDecimal(sale.get("sgstAmount"));
    BigDecimal taxTotal = toBigDecimal(sale.get("taxTotal"));
    if (cgst.signum() <= 0 && sgst.signum() <= 0 && taxTotal.signum() > 0) {
      GstSplit gst = splitSaleTaxByLines(sale, taxTotal, shopCgstPct, shopSgstPct);
      cgst = gst.cgst();
      sgst = gst.sgst();
    }

    BigDecimal taxBeforeRound = revenue.add(cgst).add(sgst);
    BigDecimal roundOff =
        saleTotal.subtract(taxBeforeRound).setScale(4, java.math.RoundingMode.HALF_UP);

    String paymentMethod = sale.getString("paymentMethod");
    if (paymentMethod == null || paymentMethod.isBlank()) {
      paymentMethod = "CASH";
    } else {
      paymentMethod = paymentMethod.trim().toUpperCase();
    }

    BigDecimal paidCash = toBigDecimal(sale.get("cashAmount"));
    BigDecimal paidOnline = toBigDecimal(sale.get("onlineAmount"));
    BigDecimal receivable = toBigDecimal(sale.get("creditAmount"));
    boolean hasStoredSplit =
        sale.get("cashAmount") != null
            || sale.get("onlineAmount") != null
            || sale.get("creditAmount") != null;
    if (!hasStoredSplit) {
      BigDecimal legacyPaid = resolveSaleLegacyPaidAmount(saleId, sale, saleTotal, paymentMethod);
      receivable = saleTotal.subtract(legacyPaid).max(BigDecimal.ZERO);
      switch (paymentMethod) {
        case "ONLINE", "UPI", "BANK", "CARD" -> {
          paidCash = BigDecimal.ZERO;
          paidOnline = legacyPaid;
        }
        case "ONLINE_CREDIT" -> {
          paidCash = BigDecimal.ZERO;
          paidOnline = legacyPaid;
        }
        case "CREDIT_CASH" -> {
          paidCash = legacyPaid;
          paidOnline = BigDecimal.ZERO;
        }
        case "CASH_ONLINE" -> {
          BigDecimal half =
              legacyPaid.divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
          paidCash = half;
          paidOnline = legacyPaid.subtract(half);
        }
        case "CREDIT" -> {
          paidCash = BigDecimal.ZERO;
          paidOnline = BigDecimal.ZERO;
        }
        default -> {
          paidCash = legacyPaid;
          paidOnline = BigDecimal.ZERO;
        }
      }
    }
    BigDecimal cogs = toBigDecimal(sale.get("totalCost"));

    LocalDate txnDate = toLocalDate(sale.get("soldAt"));
    if (txnDate == null) txnDate = toLocalDate(sale.get("updatedAt"));
    if (txnDate == null) txnDate = toLocalDate(sale.get("createdAt"));
    if (txnDate == null) txnDate = LocalDate.now();

    String customerId = sale.getString("customerId");
    String customerName = sale.getString("customerName");
    if ((customerName == null || customerName.isBlank())
        && customerId != null
        && !customerId.isBlank()) {
      Document customerDoc =
          mongoTemplate.findOne(
              new Query(Criteria.where("_id").is(customerId)), Document.class, CUSTOMERS);
      if (customerDoc != null) {
        customerName = customerDoc.getString("name");
      }
    }

    return SaleInvoicePostingRequest.builder()
        .sourceId(saleId)
        .invoiceNo(sale.getString("invoiceNo"))
        .txnDate(txnDate)
        .customerId(customerId)
        .customerDisplayName(customerName)
        .taxableRevenue(revenue)
        .outputCgst(cgst)
        .outputSgst(sgst)
        .saleTotal(saleTotal)
        .paidCash(paidCash)
        .paidOnline(paidOnline)
        .receivableAmount(receivable)
        .paymentMethod(paymentMethod)
        .cogsAmount(cogs)
        .roundOff(roundOff)
        .build();
  }

  /**
   * Infers cash collected at sale time. Uses linked {@code SALE:CREDIT:} credit row when present;
   * otherwise {@code CREDIT} tender means nothing paid up-front.
   */
  private BigDecimal resolveSaleLegacyPaidAmount(
      String saleId, Document sale, BigDecimal saleTotal, String paymentMethod) {
    Document creditRow =
        mongoTemplate.findOne(
            new Query(
                Criteria.where("sourceKey")
                    .is("SALE:CREDIT:" + saleId)
                    .and("entryType")
                    .is("CHARGE")),
            Document.class,
            CREDIT_ENTRIES);
    if (creditRow != null) {
      BigDecimal outstanding = toBigDecimal(creditRow.get("amount"));
      if (outstanding.signum() > 0 && outstanding.compareTo(saleTotal) < 0) {
        return saleTotal.subtract(outstanding).setScale(4, java.math.RoundingMode.HALF_UP);
      }
      if (outstanding.compareTo(saleTotal) >= 0) {
        return BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP);
      }
    }
    if ("CREDIT".equals(paymentMethod)) {
      return BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP);
    }
    return saleTotal.setScale(4, java.math.RoundingMode.HALF_UP);
  }

  /** Splits sale {@code taxTotal} using per-line CGST/SGST from inventory pricing (sale taxable base). */
  private GstSplit splitSaleTaxByLines(
      Document sale, BigDecimal taxTotal, BigDecimal shopCgstPct, BigDecimal shopSgstPct) {
    BigDecimal total = taxTotal != null ? taxTotal : BigDecimal.ZERO;
    BigDecimal zero = BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP);
    if (total.signum() <= 0) return new GstSplit(zero, zero);

    Object itemsObj = sale.get("items");
    if (!(itemsObj instanceof List<?> items) || items.isEmpty()) {
      return ratioSplit(total, shopCgstPct, shopSgstPct);
    }

    BigDecimal cgstSum = BigDecimal.ZERO;
    BigDecimal sgstSum = BigDecimal.ZERO;
    boolean anyLineContributed = false;
    for (Object itemObj : items) {
      if (!(itemObj instanceof Document item)) continue;
      BigDecimal cgstPct = parsePercentage(item.getString("cgst"));
      BigDecimal sgstPct = parsePercentage(item.getString("sgst"));
      if (cgstPct.signum() <= 0 && sgstPct.signum() <= 0) {
        cgstPct = shopCgstPct;
        sgstPct = shopSgstPct;
      }
      String inventoryId = item.getString("inventoryId");
      if (inventoryId != null && !inventoryId.isBlank()) {
        Document inventoryDoc =
            mongoTemplate.findOne(
                new Query(Criteria.where("_id").is(inventoryId)), Document.class, INVENTORIES);
        String pricingId = inventoryDoc != null ? inventoryDoc.getString("pricingId") : null;
        if (pricingId != null && !pricingId.isBlank()) {
          Document pricingDoc =
              mongoTemplate.findOne(
                  new Query(Criteria.where("_id").is(pricingId)), Document.class, PRICING);
          if (pricingDoc != null) {
            BigDecimal pCgst = parsePercentage(pricingDoc.getString("cgst"));
            BigDecimal pSgst = parsePercentage(pricingDoc.getString("sgst"));
            if (pCgst.signum() > 0 || pSgst.signum() > 0) {
              cgstPct = pCgst;
              sgstPct = pSgst;
            }
          }
        }
      }
      BigDecimal lineBase = saleLineTaxableBase(item);
      if (lineBase.signum() <= 0) continue;
      if (cgstPct.signum() <= 0 && sgstPct.signum() <= 0) continue;
      cgstSum =
          cgstSum.add(
              lineBase
                  .multiply(cgstPct)
                  .divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP));
      sgstSum =
          sgstSum.add(
              lineBase
                  .multiply(sgstPct)
                  .divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP));
      anyLineContributed = true;
    }

    if (!anyLineContributed) {
      return ratioSplit(total, shopCgstPct, shopSgstPct);
    }

    BigDecimal drift =
        total.subtract(cgstSum.add(sgstSum)).setScale(4, java.math.RoundingMode.HALF_UP);
    if (drift.signum() != 0) {
      if (cgstSum.compareTo(sgstSum) > 0) cgstSum = cgstSum.add(drift);
      else sgstSum = sgstSum.add(drift);
    }
    return new GstSplit(
        cgstSum.setScale(4, java.math.RoundingMode.HALF_UP),
        sgstSum.setScale(4, java.math.RoundingMode.HALF_UP));
  }

  private static BigDecimal saleLineTaxableBase(Document item) {
    BigDecimal qty = toBigDecimal(item.get("quantity"));
    if (qty.signum() <= 0) return BigDecimal.ZERO;
    BigDecimal ptr = toBigDecimal(item.get("priceToRetail"));
    BigDecimal mrp = toBigDecimal(item.get("maximumRetailPrice"));
    BigDecimal price = ptr.signum() > 0 ? ptr : mrp;
    if (price.signum() <= 0) return BigDecimal.ZERO;
    BigDecimal base = price.multiply(qty);
    BigDecimal addDisc = toBigDecimal(item.get("saleAdditionalDiscount"));
    if (addDisc.signum() > 0) {
      base =
          base.multiply(
              BigDecimal.ONE.subtract(
                  addDisc.divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP)));
    }
    return base.setScale(4, java.math.RoundingMode.HALF_UP);
  }

  private VendorPurchaseInvoicePostingRequest toRequest(
      String invoiceId, Document inv, BigDecimal shopCgstPct, BigDecimal shopSgstPct) {
    String vendorId = inv.getString("vendorId");
    if (vendorId == null || vendorId.isBlank()) {
      return null;
    }
    String shopId = inv.getString("shopId");
    BigDecimal goods = toBigDecimal(inv.get("lineSubTotal"));
    BigDecimal tax = toBigDecimal(inv.get("taxTotal"));
    BigDecimal invoiceTotal = toBigDecimal(inv.get("invoiceTotal"));
    BigDecimal shipping = toBigDecimal(inv.get("shippingCharge"));
    BigDecimal otherCharges = toBigDecimal(inv.get("otherCharges"));
    BigDecimal roundOff = toBigDecimal(inv.get("roundOff"));
    BigDecimal paidAmount = toBigDecimal(inv.get("paidAmount"));
    String paymentMethod = inv.getString("paymentMethod");
    if (paymentMethod == null || paymentMethod.isBlank()) {
      paymentMethod = "CREDIT";
    }

    if (invoiceTotal.signum() <= 0) {
      BigDecimal computed = goods.add(tax).add(shipping).add(otherCharges);
      if (computed.signum() <= 0) {
        return null;
      }
      invoiceTotal = computed;
    }

    LocalDate txnDate = toLocalDate(inv.get("invoiceDate"));
    if (txnDate == null) txnDate = toLocalDate(inv.get("createdAt"));
    if (txnDate == null) txnDate = LocalDate.now();

    Document vendorDoc =
        mongoTemplate.findOne(
            new Query(Criteria.where("_id").is(vendorId)), Document.class, VENDORS);
    String vendorName = vendorDoc != null ? vendorDoc.getString("name") : null;

    GstSplit gst = splitGstByLines(shopId, inv, tax, shopCgstPct, shopSgstPct);

    return VendorPurchaseInvoicePostingRequest.builder()
        .sourceId(invoiceId)
        .invoiceNo(inv.getString("invoiceNo"))
        .txnDate(txnDate)
        .vendorId(vendorId)
        .vendorDisplayName(vendorName)
        .goodsValue(goods)
        .inputCgst(gst.cgst)
        .inputSgst(gst.sgst)
        .shippingCharge(shipping)
        .otherCharges(otherCharges)
        .roundOff(roundOff)
        .invoiceTotal(invoiceTotal)
        .paidAmount(paidAmount)
        .paymentMethod(paymentMethod)
        .build();
  }

  /**
   * Same rule the live posting hook uses: walk each invoice line, look up its inventory's
   * pricing doc, and split CGST + SGST from those per-line rates. Lines that don't resolve to a
   * pricing doc fall back to the shop's configured rates. IGST is always zero here — interstate
   * tax will be wired in once invoices carry a place-of-supply marker.
   */
  private GstSplit splitGstByLines(
      String shopId,
      Document inv,
      BigDecimal taxTotal,
      BigDecimal shopCgstPct,
      BigDecimal shopSgstPct) {
    BigDecimal total = taxTotal != null ? taxTotal : BigDecimal.ZERO;
    BigDecimal zero = BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP);
    if (total.signum() <= 0) return new GstSplit(zero, zero);

    Object linesObj = inv.get("lines");
    if (!(linesObj instanceof List<?> lines) || lines.isEmpty()) {
      return ratioSplit(total, shopCgstPct, shopSgstPct);
    }

    BigDecimal cgstSum = BigDecimal.ZERO;
    BigDecimal sgstSum = BigDecimal.ZERO;
    boolean anyLineContributed = false;
    for (Object lineObj : lines) {
      if (!(lineObj instanceof Document line)) continue;
      BigDecimal cgstPct = shopCgstPct;
      BigDecimal sgstPct = shopSgstPct;
      String inventoryId = line.getString("inventoryId");
      if (inventoryId != null && !inventoryId.isBlank()) {
        Document inventoryDoc =
            mongoTemplate.findOne(
                new Query(Criteria.where("_id").is(inventoryId)), Document.class, INVENTORIES);
        String pricingId = inventoryDoc != null ? inventoryDoc.getString("pricingId") : null;
        if (pricingId != null && !pricingId.isBlank()) {
          Document pricingDoc =
              mongoTemplate.findOne(
                  new Query(Criteria.where("_id").is(pricingId)), Document.class, PRICING);
          if (pricingDoc != null) {
            BigDecimal pCgst = parsePercentage(pricingDoc.getString("cgst"));
            BigDecimal pSgst = parsePercentage(pricingDoc.getString("sgst"));
            if (pCgst.signum() > 0 || pSgst.signum() > 0) {
              cgstPct = pCgst;
              sgstPct = pSgst;
            }
          }
        }
      }
      BigDecimal qty =
          line.get("count") instanceof Number n
              ? BigDecimal.valueOf(n.longValue())
              : BigDecimal.ZERO;
      if (qty.signum() <= 0) continue;
      BigDecimal price = toBigDecimal(line.get("costPrice"));
      if (price.signum() <= 0) price = toBigDecimal(line.get("priceToRetail"));
      BigDecimal lineValue = price.multiply(qty);
      if (lineValue.signum() <= 0) continue;
      if (cgstPct.signum() <= 0 && sgstPct.signum() <= 0) continue;
      cgstSum =
          cgstSum.add(
              lineValue
                  .multiply(cgstPct)
                  .divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP));
      sgstSum =
          sgstSum.add(
              lineValue
                  .multiply(sgstPct)
                  .divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP));
      anyLineContributed = true;
    }

    if (!anyLineContributed) {
      return ratioSplit(total, shopCgstPct, shopSgstPct);
    }

    // Reconcile rounding drift so the JE ties out exactly to taxTotal.
    BigDecimal drift =
        total.subtract(cgstSum.add(sgstSum)).setScale(4, java.math.RoundingMode.HALF_UP);
    if (drift.signum() != 0) {
      if (cgstSum.compareTo(sgstSum) > 0) cgstSum = cgstSum.add(drift);
      else sgstSum = sgstSum.add(drift);
    }
    return new GstSplit(
        cgstSum.setScale(4, java.math.RoundingMode.HALF_UP),
        sgstSum.setScale(4, java.math.RoundingMode.HALF_UP));
  }

  private static GstSplit ratioSplit(BigDecimal total, BigDecimal cgstPct, BigDecimal sgstPct) {
    BigDecimal zero = BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP);
    BigDecimal cgst;
    BigDecimal sgst;
    if (cgstPct.signum() > 0 && sgstPct.signum() > 0) {
      BigDecimal denom = cgstPct.add(sgstPct);
      cgst = total.multiply(cgstPct).divide(denom, 4, java.math.RoundingMode.HALF_UP);
      sgst = total.subtract(cgst).setScale(4, java.math.RoundingMode.HALF_UP);
    } else if (cgstPct.signum() > 0) {
      cgst = total.setScale(4, java.math.RoundingMode.HALF_UP);
      sgst = zero;
    } else if (sgstPct.signum() > 0) {
      cgst = zero;
      sgst = total.setScale(4, java.math.RoundingMode.HALF_UP);
    } else {
      BigDecimal half = total.divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
      cgst = total.subtract(half).setScale(4, java.math.RoundingMode.HALF_UP);
      sgst = half.setScale(4, java.math.RoundingMode.HALF_UP);
    }
    return new GstSplit(cgst, sgst);
  }

  private static BigDecimal parsePercentage(String raw) {
    if (raw == null) return BigDecimal.ZERO;
    String t = raw.trim();
    if (t.isEmpty()) return BigDecimal.ZERO;
    if (t.endsWith("%")) t = t.substring(0, t.length() - 1).trim();
    try {
      BigDecimal v = new BigDecimal(t);
      return v.signum() < 0 ? BigDecimal.ZERO : v;
    } catch (NumberFormatException ex) {
      return BigDecimal.ZERO;
    }
  }

  private record GstSplit(BigDecimal cgst, BigDecimal sgst) {}

  private static String stringId(Object id) {
    if (id == null) return null;
    if (id instanceof ObjectId oid) return oid.toHexString();
    return id.toString();
  }

  private static BigDecimal resolveReturnRefundToCredit(Document doc, BigDecimal returnTotal) {
    BigDecimal cash = toBigDecimal(doc.get("refundCash"));
    BigDecimal online = toBigDecimal(doc.get("refundOnline"));
    BigDecimal credit = toBigDecimal(doc.get("refundToCredit"));
    if (cash.signum() == 0 && online.signum() == 0 && credit.signum() == 0) {
      return returnTotal;
    }
    return credit;
  }

  private static BigDecimal toBigDecimal(Object v) {
    if (v == null) return BigDecimal.ZERO;
    if (v instanceof BigDecimal bd) return bd;
    if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    try {
      return new BigDecimal(v.toString());
    } catch (NumberFormatException nfe) {
      return BigDecimal.ZERO;
    }
  }

  private static LocalDate toLocalDate(Object v) {
    if (v == null) return null;
    if (v instanceof Date d) return d.toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
    if (v instanceof Instant i) return i.atOffset(ZoneOffset.UTC).toLocalDate();
    if (v instanceof Long l) return Instant.ofEpochMilli(l).atOffset(ZoneOffset.UTC).toLocalDate();
    try {
      return LocalDate.parse(v.toString());
    } catch (Exception ignore) {
      return null;
    }
  }

  /**
   * Counters returned by the backfill endpoint. {@code reposted} is non-zero only on a
   * {@code force=true} run and counts invoices whose existing journal entry was deleted and
   * replaced with a fresh posting (after a settings change such as updated GST percentages or
   * CoA tweaks).
   */
  public record BackfillResult(
      int processed, int posted, int reposted, int skipped, int failed) {}
}
