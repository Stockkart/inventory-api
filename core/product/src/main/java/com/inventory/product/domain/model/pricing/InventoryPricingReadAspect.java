package com.inventory.product.domain.model.pricing;

import com.inventory.product.domain.model.Inventory;
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

@Slf4j
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class InventoryPricingReadAspect {

  @Autowired
  private InventoryPricingReadHandler handler;

  @Pointcut("bean(inventoryRepository)")
  void inventoryRepository() {}

  @Pointcut("execution(* find*(..)) || execution(* search*(..))")
  void findOrSearchMethods() {}

  @Pointcut("execution(* *LotSummaries*(..)) || execution(* count*(..))")
  void excludedMethods() {}

  @Around("inventoryRepository() && findOrSearchMethods() && !excludedMethods()")
  public Object enrichOnRead(ProceedingJoinPoint jp) throws Throwable {
    Object result = jp.proceed();
    if (result == null) return result;

    if (result instanceof Optional<?> opt) {
      opt.ifPresent(o -> { if (o instanceof Inventory inv) handler.enrich(inv); });
      return result;
    }
    if (result instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Inventory) {
      handler.enrich((List<Inventory>) list);
      return result;
    }
    if (result instanceof Inventory inv) {
      handler.enrich(inv);
      return result;
    }
    return result;
  }
}
