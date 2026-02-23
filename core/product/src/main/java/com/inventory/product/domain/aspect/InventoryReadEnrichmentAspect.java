package com.inventory.product.domain.aspect;

import com.inventory.product.domain.enrichment.InventoryPricingEnricher;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * AOP aspect that enriches Inventory entities with pricing data after repository reads.
 * No changes needed in services or mappers - pricing is automatically populated.
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class InventoryReadEnrichmentAspect {

  @Autowired
  private InventoryPricingEnricher enricher;

  @Pointcut("bean(inventoryRepository)")
  void inventoryRepository() {}

  @Pointcut("execution(* find*(..)) || execution(* search*(..))")
  void findOrSearchMethods() {}

  @Pointcut("execution(* *LotSummaries*(..)) || execution(* count*(..))")
  void excludedMethods() {}

  @Around("inventoryRepository() && findOrSearchMethods() && !excludedMethods()")
  public Object enrichInventoryReads(ProceedingJoinPoint joinPoint) throws Throwable {
    Object result = joinPoint.proceed();
    if (result == null) return result;

    if (result instanceof Optional<?> opt) {
      opt.ifPresent(this::enrichIfInventory);
      return result;
    }
    if (result instanceof List<?> list && !list.isEmpty()) {
      if (list.get(0) instanceof Inventory) {
        enricher.enrich((List<Inventory>) list);
      }
      return result;
    }
    if (result instanceof Inventory inv) {
      enricher.enrich(inv);
      return result;
    }
    return result;
  }

  private void enrichIfInventory(Object obj) {
    if (obj instanceof Inventory inv) {
      enricher.enrich(inv);
    }
  }
}
