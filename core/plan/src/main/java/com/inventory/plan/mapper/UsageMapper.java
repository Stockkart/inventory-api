package com.inventory.plan.mapper;

import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.rest.dto.request.RecordUsageRequest;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mapper for Usage entity: object creation and applying deltas.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UsageMapper {

  /** Create a new Usage entity with default values for the given shop and month. */
  default Usage toUsage(String shopId, String month) {
    Usage usage = new Usage();
    usage.setShopId(shopId);
    usage.setMonth(month);
    usage.setBillingAmountUsed(BigDecimal.ZERO);
    usage.setBillCountUsed(0);
    usage.setSmsUsed(0);
    usage.setWhatsappUsed(0);
    usage.setCreatedAt(Instant.now());
    usage.setUpdatedAt(Instant.now());
    return usage;
  }

  /** Applies RecordUsageRequest deltas to Usage (adds amounts to current values). */
  default void applyRecordUsage(Usage usage, RecordUsageRequest request) {
    if (request.getBillingAmount() != null && request.getBillingAmount().signum() > 0) {
      BigDecimal current = usage.getBillingAmountUsed() != null ? usage.getBillingAmountUsed() : BigDecimal.ZERO;
      usage.setBillingAmountUsed(current.add(request.getBillingAmount()));
    }
    if (request.getBillCount() != null && request.getBillCount() > 0) {
      int current = usage.getBillCountUsed() != null ? usage.getBillCountUsed() : 0;
      usage.setBillCountUsed(current + request.getBillCount());
    }
    if (request.getSmsCount() != null && request.getSmsCount() > 0) {
      int current = usage.getSmsUsed() != null ? usage.getSmsUsed() : 0;
      usage.setSmsUsed(current + request.getSmsCount());
    }
    if (request.getWhatsappCount() != null && request.getWhatsappCount() > 0) {
      int current = usage.getWhatsappUsed() != null ? usage.getWhatsappUsed() : 0;
      usage.setWhatsappUsed(current + request.getWhatsappCount());
    }
  }
}
