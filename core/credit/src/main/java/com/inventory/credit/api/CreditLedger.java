package com.inventory.credit.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What other modules may do with customer and vendor credit. */
public interface CreditLedger {

  /**
   * Records a charge, and posts its journal entry when the books do not already hold one (a
   * charge tied to a sale or vendor invoice is already on that document's entry). Same path as
   * {@code POST /api/v1/credit/charge}. Idempotent on {@link CreditCharge#sourceKey()}.
   *
   * @return the entry id
   */
  String charge(String shopId, String userId, CreditCharge charge);

  /**
   * Records the ledger row only, for a caller that posts the journal entry itself. Idempotent on
   * {@link CreditCharge#sourceKey()}.
   *
   * @return the entry id
   */
  String recordCharge(String shopId, String userId, CreditCharge charge);

  /**
   * Lowers what a party owes because goods came back (a sales return or a purchase return). The
   * return's own journal entry already moved the books. Idempotent on {@code sourceKey}.
   */
  void recordReturnDueReduction(
      String shopId,
      String userId,
      CreditPartyType partyType,
      String partyId,
      String partyDisplayName,
      BigDecimal amount,
      String referenceType,
      String referenceId,
      String sourceKey,
      String note,
      LocalDate txnDate);

  /** Every credit account of this party type in the shop. */
  List<CreditAccountView> accounts(String shopId, CreditPartyType partyType);

  /** Every ledger entry of this party type in the shop. */
  List<CreditEntryView> entries(String shopId, CreditPartyType partyType);
}
