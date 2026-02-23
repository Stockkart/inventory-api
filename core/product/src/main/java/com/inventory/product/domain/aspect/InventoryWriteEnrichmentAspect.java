package com.inventory.product.domain.aspect;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.writer.InventoryPricingWriter;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * AOP aspect that persists pricing when Inventory is saved.
 * When inventory has pricingWriteIntent set, the writer creates/updates pricing before the save.
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InventoryWriteEnrichmentAspect {

  @Autowired
  private InventoryPricingWriter writer;

  @Pointcut("bean(inventoryRepository)")
  void inventoryRepository() {}

  @Pointcut("execution(* save(..)) || execution(* saveAll(..))")
  void saveMethods() {}

  @Around("inventoryRepository() && saveMethods()")
  public Object persistPricingOnSave(ProceedingJoinPoint joinPoint) throws Throwable {
    Object[] args = joinPoint.getArgs();
    if (args.length == 0) return joinPoint.proceed();

    Object arg = args[0];
    if (arg instanceof Inventory inv) {
      writer.processFromContext(inv);
    } else if (arg instanceof Iterable<?> iterable) {
      for (Object item : iterable) {
        if (item instanceof Inventory inv) {
          writer.processFromContext(inv);
        }
      }
    }

    return joinPoint.proceed(args);
  }
}
