package com.inventory.product.domain.model.catalog;

import com.inventory.product.domain.model.Inventory;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Hydrates inventory identity from {@link com.inventory.product.domain.model.Product} on every
 * {@code find*}/{@code search*} against {@code inventoryRepository}, so ~all read call sites stay
 * unchanged. Mirrors {@code InventoryPricingReadAspect}.
 *
 * <p>On by default now that catalog identity is {@code @Transient} on inventory and owned by
 * {@link com.inventory.product.domain.model.Product}. Can be disabled with
 * {@code stockkart.product-read-hydration.enabled=false} only if identity is being persisted on
 * inventory again.
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(
    name = "stockkart.product-read-hydration.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InventoryProductReadAspect {

  @Autowired
  private InventoryProductReadHandler handler;

  @Pointcut("bean(inventoryRepository)")
  void inventoryRepository() {}

  @Pointcut("execution(* find*(..)) || execution(* search*(..))")
  void findOrSearchMethods() {}

  @Pointcut("execution(* *LotSummaries*(..)) || execution(* count*(..))")
  void excludedMethods() {}

  @Around("inventoryRepository() && findOrSearchMethods() && !excludedMethods()")
  @SuppressWarnings("unchecked")
  public Object enrichOnRead(ProceedingJoinPoint jp) throws Throwable {
    Object result = jp.proceed();
    if (result == null) {
      return null;
    }
    if (result instanceof Optional<?> opt) {
      opt.ifPresent(o -> {
        if (o instanceof Inventory inv) {
          handler.enrich(inv);
        }
      });
      return result;
    }
    if (result instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Inventory) {
      handler.enrich((List<Inventory>) list);
      return result;
    }
    if (result instanceof Inventory inv) {
      handler.enrich(inv);
    }
    return result;
  }
}
