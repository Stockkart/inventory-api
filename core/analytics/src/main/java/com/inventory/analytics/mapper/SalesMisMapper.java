package com.inventory.analytics.mapper;

import static com.inventory.analytics.utils.SalesMisUtils.toMoneyScale;
import static com.inventory.analytics.utils.SalesMisUtils.toShopDate;
import static com.inventory.analytics.utils.SalesMisUtils.zeroIfNull;
import static com.inventory.analytics.utils.SalesMisUtils.zeroMoney;

import com.inventory.analytics.domain.model.SalesMisTxnType;
import com.inventory.analytics.rest.dto.response.SalesMisCustomerSummaryDto;
import com.inventory.analytics.rest.dto.response.SalesMisDailyRowDto;
import com.inventory.analytics.rest.dto.response.SalesMisRowDto;
import com.inventory.common.constants.PaymentMethod;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditEntryType;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.service.VendorPurchasePaymentBreakdown;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds sales money MIS rows from source documents.
 *
 * <p>Customer-side counterpart of {@link VendorMoneyMisMapper}. Row assembly lives here rather than
 * in the service so the service reads as "gather, filter, total" without eighty lines of builder
 * chains in the middle.
 */
@Component
public class SalesMisMapper {

  /** Receipts without a recorded method are assumed to be cash in hand. */
  private static final PaymentMethod DEFAULT_RECEIPT_METHOD = PaymentMethod.CASH;

  /** Bucket for walk-in cash sales, which carry no customer id. */
  public static final String WALK_IN_CUSTOMER_ID = "WALKIN";

  public static final String WALK_IN_CUSTOMER_NAME = "Walk-in / Cash sale";

  /** A sale: what was billed and how it was tendered. */
  public SalesMisRowDto toSaleRow(
      Purchase sale,
      VendorPurchasePaymentBreakdown.Result tender,
      Map<String, String> customerNames) {
    Instant soldAt = effectiveSaleInstant(sale);
    String customerId = customerKeyOf(sale);

    return baseRow(SalesMisTxnType.SALE, sale.getId(), customerId, customerNames)
        .customerName(customerNameFor(customerId, sale.getCustomerName(), customerNames))
        .txnDate(toShopDate(soldAt))
        .postedAt(soldAt)
        .refNo(sale.getInvoiceNo())
        .totalAmount(saleTotal(sale, tender))
        .cashAmount(tender.cashAmount())
        .onlineAmount(tender.onlineAmount())
        .creditAmount(tender.creditAmount())
        .sourceId(sale.getId())
        .build();
  }

  /**
   * A sales return. Amounts are negated because a return moves money back toward the customer.
   *
   * <p>A return with no refund legs recorded is treated as wholly credit — that is the only reading
   * consistent with the customer still owing the amount.
   */
  public SalesMisRowDto toSalesReturnRow(
      Refund refund, Purchase linkedSale, Map<String, String> customerNames) {
    String customerId = customerKeyOf(refund, linkedSale);

    BigDecimal total = zeroIfNull(refund.getRefundAmount()).negate();
    BigDecimal cash = zeroIfNull(refund.getRefundCash()).negate();
    BigDecimal online = zeroIfNull(refund.getRefundOnline()).negate();
    BigDecimal credit = zeroIfNull(refund.getRefundToCredit()).negate();
    if (hasNoRefundLegs(cash, online, credit)) {
      credit = total;
    }

    return baseRow(SalesMisTxnType.SALES_RETURN, refund.getId(), customerId, customerNames)
        .txnDate(toShopDate(refund.getCreatedAt()))
        .postedAt(refund.getCreatedAt())
        .refNo(refund.getCreditNoteNo())
        .againstTxnId(linkedSale != null ? linkedSale.getId() : null)
        .againstRefNo(linkedSale != null ? linkedSale.getInvoiceNo() : null)
        .totalAmount(total)
        .cashAmount(cash)
        .onlineAmount(online)
        .creditAmount(credit)
        .sourceId(refund.getId())
        .build();
  }

  /** A receipt (money taken in) or a credit charge / adjustment. */
  public SalesMisRowDto toCreditRow(CreditEntry entry, Map<String, String> customerNames) {
    boolean receipt = entry.getEntryType() == CreditEntryType.SETTLEMENT;
    SalesMisTxnType type =
        receipt ? SalesMisTxnType.CUSTOMER_RECEIPT : SalesMisTxnType.CUSTOMER_CREDIT_CHARGE;

    BigDecimal amount = zeroIfNull(entry.getAmount());
    BigDecimal cash = zeroMoney();
    BigDecimal online = zeroMoney();
    BigDecimal credit = zeroMoney();

    if (receipt) {
      if (PaymentMethod.from(entry.getPaymentMethod(), DEFAULT_RECEIPT_METHOD).isOnlineTender()) {
        online = amount;
      } else {
        cash = amount;
      }
    } else {
      credit = amount;
    }

    LocalDate day =
        entry.getTxnDate() != null ? entry.getTxnDate() : toShopDate(entry.getCreatedAt());

    return baseRow(type, entry.getTxnId(), entry.getPartyRefId(), customerNames)
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

  /** Carried-forward balance shown above a customer's first row in the period. */
  public SalesMisRowDto toOpeningRow(
      String customerId,
      BigDecimal openingBalance,
      LocalDate txnDate,
      String fallbackCustomerName,
      Map<String, String> customerNames) {
    BigDecimal opening = toMoneyScale(openingBalance);
    return SalesMisRowDto.builder()
        .txnId(customerId)
        .txnType(SalesMisTxnType.OPENING.name())
        .txnTypeLabel(SalesMisTxnType.OPENING.label())
        .customerId(customerId)
        .customerName(customerNames.getOrDefault(customerId, fallbackCustomerName))
        .txnDate(txnDate)
        .postedAt(null)
        .refNo("Opening balance")
        .totalAmount(opening)
        .cashAmount(zeroMoney())
        .onlineAmount(zeroMoney())
        .creditAmount(opening)
        .balanceAfter(opening)
        .sourceType(SalesMisTxnType.OPENING.sourceType())
        .sourceId(null)
        .opening(true)
        .build();
  }

  /** One day of the trading summary, with the month's running total already resolved. */
  public SalesMisDailyRowDto toDailyRow(
      LocalDate day,
      BigDecimal totalSale,
      BigDecimal cash,
      BigDecimal online,
      BigDecimal credit,
      BigDecimal monthToDate) {
    return SalesMisDailyRowDto.builder()
        .txnDate(day)
        .totalSale(toMoneyScale(totalSale))
        .cashAmount(toMoneyScale(cash))
        .onlineAmount(toMoneyScale(online))
        .creditAmount(toMoneyScale(credit))
        .monthToDateTotal(toMoneyScale(monthToDate))
        .build();
  }

  public SalesMisCustomerSummaryDto toCustomerSummary(
      String customerId,
      BigDecimal openingBalance,
      BigDecimal closingBalance,
      Map<String, String> customerNames) {
    return SalesMisCustomerSummaryDto.builder()
        .customerId(customerId)
        .customerName(customerNames.getOrDefault(customerId, customerId))
        .openingBalance(openingBalance)
        .closingBalanceInPeriod(closingBalance)
        .currentBalance(closingBalance)
        .build();
  }

  /**
   * Shared identity columns; callers fill the amount and reference columns.
   *
   * <p>Unlike vendor documents, sales and refunds carry no generated business transaction id, so
   * their storage id stands in as {@code txnId} and also travels as {@code sourceId}. Credit
   * entries do carry one and pass it through.
   */
  private SalesMisRowDto.SalesMisRowDtoBuilder baseRow(
      SalesMisTxnType type, String txnId, String customerId, Map<String, String> customerNames) {
    return SalesMisRowDto.builder()
        .txnId(txnId)
        .txnType(type.name())
        .txnTypeLabel(type.label())
        .customerId(customerId)
        .customerName(customerNames.getOrDefault(customerId, customerId))
        .sourceType(type.sourceType())
        .opening(false);
  }

  /** Sold-at when recorded, else when the sale was captured. */
  public static Instant effectiveSaleInstant(Purchase sale) {
    return sale.getSoldAt() != null ? sale.getSoldAt() : sale.getCreatedAt();
  }

  /** The party a sale belongs to; walk-in cash sales roll up under one synthetic customer. */
  public static String customerKeyOf(Purchase sale) {
    return StringUtils.hasText(sale.getCustomerId()) ? sale.getCustomerId() : WALK_IN_CUSTOMER_ID;
  }

  /** The party a refund belongs to, falling back to the sale it was raised against. */
  public static String customerKeyOf(Refund refund, Purchase linkedSale) {
    if (StringUtils.hasText(refund.getCustomerId())) {
      return refund.getCustomerId();
    }
    if (linkedSale != null && StringUtils.hasText(linkedSale.getCustomerId())) {
      return linkedSale.getCustomerId();
    }
    return WALK_IN_CUSTOMER_ID;
  }

  /**
   * Directory name when the customer is on file, else the name captured on the sale itself.
   *
   * <p>Counter sales often record only a name, with no customer record to look up.
   */
  private String customerNameFor(
      String customerId, String nameOnSale, Map<String, String> customerNames) {
    String directoryName = customerNames.get(customerId);
    if (StringUtils.hasText(directoryName) && !directoryName.equals(customerId)) {
      return directoryName;
    }
    return StringUtils.hasText(nameOnSale) ? nameOnSale : customerId;
  }

  /** Billed total, falling back to the tendered legs when no total was stored. */
  private BigDecimal saleTotal(Purchase sale, VendorPurchasePaymentBreakdown.Result tender) {
    BigDecimal stored = zeroIfNull(sale.getGrandTotal());
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
