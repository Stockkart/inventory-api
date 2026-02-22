package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.rest.dto.inventory.InventoryPricingDto;
import com.inventory.product.service.InventoryPricingAdapter;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Aspect
@Component
@RequiredArgsConstructor
public class InventoryPricingEnrichmentAspect {

  private final InventoryPricingAdapter pricingAdapter;

  @Around("execution(* com.inventory.product.domain.repository.InventoryRepository.*(..))")
  public Object enrichInventoryPricing(ProceedingJoinPoint joinPoint) throws Throwable {
    Object result = joinPoint.proceed();
    String methodName = joinPoint.getSignature().getName();
    if (!isReadMethod(methodName)) {
      return result;
    }

    List<Inventory> inventories = extractInventories(result);
    if (inventories.isEmpty()) {
      return result;
    }
    enrichPricing(inventories);
    return result;
  }

  private boolean isReadMethod(String methodName) {
    return methodName.startsWith("find")
        || methodName.startsWith("search")
        || methodName.startsWith("get");
  }

  private List<Inventory> extractInventories(Object result) {
    List<Inventory> inventories = new ArrayList<>();
    if (result == null) {
      return inventories;
    }

    if (result instanceof Inventory inventory) {
      inventories.add(inventory);
      return inventories;
    }

    if (result instanceof java.util.Optional<?> optional) {
      Object value = optional.orElse(null);
      if (value instanceof Inventory inventory) {
        inventories.add(inventory);
      }
      return inventories;
    }

    if (result instanceof Page<?> page) {
      page.getContent().stream()
          .filter(Inventory.class::isInstance)
          .map(Inventory.class::cast)
          .forEach(inventories::add);
      return inventories;
    }

    if (result instanceof Iterable<?> iterable) {
      for (Object value : iterable) {
        if (value instanceof Inventory inventory) {
          inventories.add(inventory);
        }
      }
    }

    return inventories;
  }

  private void enrichPricing(List<Inventory> inventories) {
    List<String> pricingIds = inventories.stream()
        .map(Inventory::getPricingId)
        .filter(StringUtils::hasText)
        .distinct()
        .toList();
    if (pricingIds.isEmpty()) {
      return;
    }

    Map<String, InventoryPricingDto> pricingMap = pricingAdapter.getPricingBulk(pricingIds);
    inventories.forEach(inventory -> {
      if (!StringUtils.hasText(inventory.getPricingId())) {
        return;
      }
      InventoryPricingDto pricing = pricingMap.get(inventory.getPricingId());
      if (pricing == null) {
        return;
      }
      inventory.setMaximumRetailPrice(pricing.getMaximumRetailPrice());
      inventory.setCostPrice(pricing.getCostPrice());
      inventory.setSellingPrice(pricing.getSellingPrice());
      inventory.setAdditionalDiscount(pricing.getAdditionalDiscount());
      inventory.setSgst(pricing.getSgst());
      inventory.setCgst(pricing.getCgst());
    });
  }
}
