package com.inventory.analytics.mapper;

import static com.inventory.analytics.utils.VendorMoneyMisUtils.toMoneyScale;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.toShopDate;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.zeroIfNull;
import static com.inventory.analytics.utils.VendorMoneyMisUtils.zeroMoney;

import com.inventory.analytics.domain.model.MisTxnType;
import com.inventory.analytics.rest.dto.response.VendorMoneyMisRowDto;
import com.inventory.analytics.rest.dto.response.VendorMoneyMisVendorSummaryDto;
import com.inventory.common.constants.PaymentMethod;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditEntryType;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.service.VendorPurchasePaymentBreakdown;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds vendor money MIS rows from source documents.
 *
 * <p>Row assembly lives here rather than in the service so the service reads as "gather, filter,
 * total" without eighty lines of builder chains in the middle.
 */
@Component
public class VendorMoneyMisMapper {

  /** Settlements without a recorded method are assumed to be cash in hand. */
  private static final PaymentMethod DEFAULT_SETTLEMENT_METHOD = PaymentMethod.CASH;

  /** A purchase invoice: what was billed and how it was tendered. */
  public VendorMoneyMisRowDto toPurchaseRow(
      VendorPurchaseInvoice invoice,
      VendorPurchasePaymentBreakdown.Result tender,
      Map<String, String> vendorNames) {
    LocalDate txnDate = toShopDate(effectiveInvoiceInstant(invoice));
    Instant posted =
        invoice.getCreatedAt() != null ? invoice.getCreatedAt() : invoice.getInvoiceDate();

    return baseRow(MisTxnType.VENDOR_PURCHASE, invoice.getId(), invoice.getVendorId(), vendorNames)
        .txnDate(txnDate)
        .postedAt(posted)
        .refNo(invoice.getInvoiceNo())
        .totalAmount(purchaseTotal(invoice, tender))
        .cashAmount(tender.cashAmount())
        .onlineAmount(tender.onlineAmount())
        .creditAmount(tender.creditAmount())
        .sourceId(invoice.getId())
        .build();
  }

  /**
   * A purchase return. Amounts are negated because a return moves money back toward the shop.
   *
   * <p>A return with no refund legs recorded is treated as wholly credit — that is the only reading
   * consistent with the vendor still owing the amount.
   */
  public VendorMoneyMisRowDto toReturnRow(
      VendorPurchaseReturn ret,
      VendorPurchaseInvoice linkedInvoice,
      Map<String, String> vendorNames) {
    String vendorId = linkedInvoice != null ? linkedInvoice.getVendorId() : null;

    BigDecimal total = zeroIfNull(ret.getReturnAmount()).negate();
    BigDecimal cash = zeroIfNull(ret.getRefundCash()).negate();
    BigDecimal online = zeroIfNull(ret.getRefundOnline()).negate();
    BigDecimal credit = zeroIfNull(ret.getRefundToCredit()).negate();
    if (hasNoRefundLegs(cash, online, credit)) {
      credit = total;
    }

    return baseRow(MisTxnType.VENDOR_RETURN, ret.getId(), vendorId, vendorNames)
        .txnDate(toShopDate(ret.getCreatedAt()))
        .postedAt(ret.getCreatedAt())
        .refNo(ret.getSupplierCreditNoteNo())
        .againstTxnId(
            linkedInvoice != null
                ? MisTxnType.VENDOR_PURCHASE.txnId(linkedInvoice.getId())
                : null)
        .againstRefNo(linkedInvoice != null ? linkedInvoice.getInvoiceNo() : null)
        .totalAmount(total)
        .cashAmount(cash)
        .onlineAmount(online)
        .creditAmount(credit)
        .sourceId(ret.getId())
        .build();
  }

  /** A settlement (money paid out) or a credit charge / adjustment. */
  public VendorMoneyMisRowDto toCreditRow(CreditEntry entry, Map<String, String> vendorNames) {
    boolean settlement = entry.getEntryType() == CreditEntryType.SETTLEMENT;
    MisTxnType type = settlement ? MisTxnType.VENDOR_PAYMENT : MisTxnType.VENDOR_CREDIT_CHARGE;

    BigDecimal amount = zeroIfNull(entry.getAmount());
    BigDecimal cash = zeroMoney();
    BigDecimal online = zeroMoney();
    BigDecimal credit = zeroMoney();

    if (settlement) {
      if (PaymentMethod.from(entry.getPaymentMethod(), DEFAULT_SETTLEMENT_METHOD).isOnlineTender()) {
        online = amount;
      } else {
        cash = amount;
      }
    } else {
      credit = amount;
    }

    LocalDate day =
        entry.getTxnDate() != null ? entry.getTxnDate() : toShopDate(entry.getCreatedAt());

    return baseRow(type, entry.getId(), entry.getPartyRefId(), vendorNames)
        .txnDate(day)
        .postedAt(entry.getCreatedAt())
        .refNo(creditRefNo(entry))
        .totalAmount(amount)
        .cashAmount(cash)
        .onlineAmount(online)
        .creditAmount(credit)
        .sourceId(entry.getId())
        .build();
  }

  /** Carried-forward balance shown above a vendor's first row in the period. */
  public VendorMoneyMisRowDto toOpeningRow(
      String vendorId,
      BigDecimal openingBalance,
      LocalDate txnDate,
      String fallbackVendorName,
      Map<String, String> vendorNames) {
    BigDecimal opening = toMoneyScale(openingBalance);
    return VendorMoneyMisRowDto.builder()
        .txnId(MisTxnType.OPENING.txnId(vendorId))
        .txnType(MisTxnType.OPENING.name())
        .txnTypeLabel(MisTxnType.OPENING.label())
        .vendorId(vendorId)
        .vendorName(vendorNames.getOrDefault(vendorId, fallbackVendorName))
        .txnDate(txnDate)
        .postedAt(null)
        .refNo("Opening balance")
        .totalAmount(opening)
        .cashAmount(zeroMoney())
        .onlineAmount(zeroMoney())
        .creditAmount(opening)
        .balanceAfter(opening)
        .sourceType(MisTxnType.OPENING.sourceType())
        .sourceId(null)
        .opening(true)
        .build();
  }

  public VendorMoneyMisVendorSummaryDto toVendorSummary(
      String vendorId,
      BigDecimal openingBalance,
      BigDecimal closingBalance,
      Map<String, String> vendorNames) {
    return VendorMoneyMisVendorSummaryDto.builder()
        .vendorId(vendorId)
        .vendorName(vendorNames.getOrDefault(vendorId, vendorId))
        .openingBalance(openingBalance)
        .closingBalanceInPeriod(closingBalance)
        .currentBalance(closingBalance)
        .build();
  }

  /** Shared identity columns; callers fill the amount and reference columns. */
  private VendorMoneyMisRowDto.VendorMoneyMisRowDtoBuilder baseRow(
      MisTxnType type, String sourceId, String vendorId, Map<String, String> vendorNames) {
    return VendorMoneyMisRowDto.builder()
        .txnId(type.txnId(sourceId))
        .txnType(type.name())
        .txnTypeLabel(type.label())
        .vendorId(vendorId)
        .vendorName(vendorNames.getOrDefault(vendorId, vendorId))
        .sourceType(type.sourceType())
        .opening(false);
  }

  /** Invoice date when recorded, else when the invoice was captured. */
  public static Instant effectiveInvoiceInstant(VendorPurchaseInvoice invoice) {
    return invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : invoice.getCreatedAt();
  }

  /** Billed total, falling back to the tendered legs when no total was stored. */
  private BigDecimal purchaseTotal(
      VendorPurchaseInvoice invoice, VendorPurchasePaymentBreakdown.Result tender) {
    BigDecimal stored = zeroIfNull(invoice.getInvoiceTotal());
    return toMoneyScale(
        stored.signum() > 0 ? stored : tender.paidAmount().add(tender.creditAmount()));
  }

  private boolean hasNoRefundLegs(BigDecimal cash, BigDecimal online, BigDecimal credit) {
    return cash.signum() == 0 && online.signum() == 0 && credit.signum() == 0;
  }

  /** Most human-readable reference available on a credit entry. */
  private String creditRefNo(CreditEntry entry) {
    if (StringUtils.hasText(entry.getNote())) {
      return entry.getNote();
    }
    return StringUtils.hasText(entry.getBankRef()) ? entry.getBankRef() : entry.getId();
  }
}
