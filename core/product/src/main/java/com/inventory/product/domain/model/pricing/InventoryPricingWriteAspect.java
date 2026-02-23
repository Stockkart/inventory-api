package com.inventory.product.domain.model.pricing;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.rest.dto.inventory.CreateInventoryRequest;
import com.inventory.product.rest.dto.inventory.UpdateInventoryRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InventoryPricingWriteAspect {

  @Autowired
  private InventoryPricingHandler handler;

  @Pointcut("execution(* com.inventory.product.service.InventoryService.create(..))")
  void serviceCreate() {}

  @Pointcut("execution(* com.inventory.product.service.InventoryService.update(..))")
  void serviceUpdate() {}

  @Around("serviceCreate()")
  public Object setCreateContext(ProceedingJoinPoint jp) throws Throwable {
    Object[] args = jp.getArgs();
    if (args.length >= 3 && args[0] instanceof CreateInventoryRequest req && args[2] instanceof String shopId) {
      InventoryPricingContext.setCreate(req, shopId);
    }
    try {
      return jp.proceed();
    } finally {
      InventoryPricingContext.clear();
    }
  }

  @Around("serviceUpdate()")
  public Object setUpdateContext(ProceedingJoinPoint jp) throws Throwable {
    Object[] args = jp.getArgs();
    if (args.length >= 2 && args[1] instanceof UpdateInventoryRequest req) {
      InventoryPricingContext.setUpdate(req);
    }
    try {
      return jp.proceed();
    } finally {
      InventoryPricingContext.clear();
    }
  }

  @Pointcut("bean(inventoryRepository)")
  void inventoryRepository() {}

  @Pointcut("execution(* save(..)) || execution(* saveAll(..))")
  void saveMethods() {}

  @Around("inventoryRepository() && saveMethods()")
  public Object persistOnSave(ProceedingJoinPoint jp) throws Throwable {
    Object[] args = jp.getArgs();
    if (args.length > 0) {
      Object arg = args[0];
      if (arg instanceof Inventory inv) {
        handler.persistFromContext(inv);
      } else if (arg instanceof Iterable<?> iter) {
        for (Object item : iter) {
          if (item instanceof Inventory inv) handler.persistFromContext(inv);
        }
      }
    }
    return jp.proceed(args);
  }
}
