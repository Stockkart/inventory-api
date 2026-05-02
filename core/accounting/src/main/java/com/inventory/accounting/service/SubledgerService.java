package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.PartyType;
import com.inventory.accounting.domain.model.SubledgerEntry;
import com.inventory.accounting.domain.model.SubledgerEntryKind;
import com.inventory.accounting.domain.repository.SubledgerEntryRepository;
import com.inventory.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SubledgerService {

  private final SubledgerEntryRepository subledgerEntryRepository;

  @Transactional(readOnly = true)
  public BigDecimal partyBalance(String shopId, PartyType partyType, String partyId) {
    validateParty(shopId, partyType, partyId);

    List<SubledgerEntry> rows =
        subledgerEntryRepository.findByShopIdAndPartyTypeAndPartyIdOrderByPostedAtAsc(
            shopId, partyType, partyId);

    BigDecimal balance = BigDecimal.ZERO.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
    for (SubledgerEntry e : rows) {
      BigDecimal a = e.getAmount() != null ? e.getAmount() : BigDecimal.ZERO;
      if (partyType == PartyType.VENDOR) {
        balance =
            e.getKind() == SubledgerEntryKind.DEBIT
                ? balance.add(a)
                : balance.subtract(a);
      } else {
        balance =
            e.getKind() == SubledgerEntryKind.CREDIT
                ? balance.add(a)
                : balance.subtract(a);
      }
    }
    return balance.setScale(2, RoundingMode.HALF_UP);
  }

  @Transactional(readOnly = true)
  public Page<SubledgerEntry> listEntries(String shopId, PartyType partyType, String partyId, int page, int size) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }
    PageRequest pr =
        PageRequest.of(
            Math.max(0, page),
            Math.min(100, Math.max(1, size)),
            Sort.by(Sort.Direction.DESC, "postedAt"));

    if (partyType != null && StringUtils.hasText(partyId)) {
      return subledgerEntryRepository.findByShopIdAndPartyTypeAndPartyIdOrderByPostedAtDesc(
          shopId, partyType, partyId, pr);
    }
    return subledgerEntryRepository.findByShopIdOrderByPostedAtDesc(shopId, pr);
  }

  private static void validateParty(String shopId, PartyType partyType, String partyId) {
    if (!StringUtils.hasText(shopId) || partyType == null || !StringUtils.hasText(partyId)) {
      throw new ValidationException("shopId, partyType, and partyId are required");
    }
  }
}
