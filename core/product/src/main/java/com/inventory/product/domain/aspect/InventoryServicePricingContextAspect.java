package com.inventory.product.domain.aspect;

import com.inventory.product.domain.context.InventoryPricingContext;
import com.inventory.product.rest.dto.inventory.CreateInventoryRequest;
import com.inventory.product.rest.dto.inventory.UpdateInventoryRequest;
import com.inventory.product.service.InventoryService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Sets pricing context before InventoryService create/update so the repository aspect
 * can persist pricing without any explicit calls from the service.
 */
@Slf4j
@Aspect
@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class InventoryServicePricingContextAspect {

  @Pointcut("execution(* com.inventory.product.service.InventoryService.create(..))")
  void createMethod() {}

  @Pointcut("execution(* com.inventory.product.service.InventoryService.update(..))")
  void updateMethod() {}

  @Around("createMethod()")
  public Object setCreateContext(ProceedingJoinPoint joinPoint) throws Throwable {
    Object[] args = joinPoint.getArgs();
    if (args.length >= 3 && args[0] instanceof CreateInventoryRequest request && args[2] instanceof String shopId) {
      InventoryPricingContext.setCreate(request, shopId);
    }
    try {
      return joinPoint.proceed();
    } finally {
      InventoryPricingContext.clear();
    }
  }

  @Around("updateMethod()")
  public Object setUpdateContext(ProceedingJoinPoint joinPoint) throws Throwable {
    Object[] args = joinPoint.getArgs();
    if (args.length >= 2 && args[1] instanceof UpdateInventoryRequest request) {
      InventoryPricingContext.setUpdate(request);
    }
    try {
      return joinPoint.proceed();
    } finally {
      InventoryPricingContext.clear();
    }
  }
}
