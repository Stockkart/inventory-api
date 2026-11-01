package com.inventory.plan.mapper;

import com.inventory.plan.domain.model.ShopCreditEntry;
import com.inventory.plan.domain.model.ShopCreditSource;
import com.inventory.plan.rest.dto.response.WalletEntryResponse;
import com.inventory.plan.rest.dto.response.WalletResponse;
import com.inventory.plan.service.wallet.WalletBalances;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface WalletMapper {

  WalletEntryResponse toEntryResponse(ShopCreditEntry entry);

  List<WalletEntryResponse> toEntryResponses(List<ShopCreditEntry> entries);

  default WalletResponse toResponse(WalletBalances balances, List<ShopCreditEntry> entries) {
    return WalletResponse.builder()
        .availableBalance(balances.available())
        .reservedBalance(balances.reserved())
        .outstandingClawback(balances.outstanding())
        .entries(toEntryResponses(entries))
        .build();
  }

  default ShopCreditEntry toEntry(String shopId, String referenceId, ShopCreditSource source, String sourceId,
      BigDecimal amount, WalletBalances before, WalletBalances after, String note, String actorUserId, Instant at) {
    return ShopCreditEntry.builder()
        .shopId(shopId)
        .referenceId(referenceId)
        .source(source)
        .sourceId(sourceId)
        .amount(amount)
        .availableDelta(after.available().subtract(before.available()))
        .reservedDelta(after.reserved().subtract(before.reserved()))
        .outstandingDelta(after.outstanding().subtract(before.outstanding()))
        .availableAfter(after.available())
        .reservedAfter(after.reserved())
        .outstandingAfter(after.outstanding())
        .note(note)
        .createdByUserId(actorUserId)
        .createdAt(at)
        .build();
  }
}
