package com.inventory.plan.rest.mapper;

import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.rest.dto.plan.RecordUsageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Applies RecordUsageRequest deltas to Usage (adds amounts to current values).
 */
@Component
public class UsageMapper {

  public void applyRecordUsage(Usage usage, RecordUsageRequest request) {
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
