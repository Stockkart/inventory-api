package com.inventory.app.memo;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.bson.Document;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Reads of the shop and the plan are remembered for the rest of the current HTTP request.
 *
 * <p>Completing one bill used to read the same shop eight times (plan expiry, usage check, usage
 * record, GST place of supply, vertical workflow, completion plugin, metrics) and the plan twice.
 * Now the first {@code findById} (or {@code findByPlanName}) goes to MongoDB and the rest reuse it.
 *
 * <ul>
 *   <li>Request-scoped only: kept in the request's attributes, gone when the request ends. Nothing
 *       is shared between requests or instances, so nothing can go stale across them.
 *   <li>Every caller gets its own copy (MongoConverter round trip), so one caller changing its
 *       object cannot change what another sees.
 *   <li>Any save, insert or delete on the same repository during the request drops that
 *       repository's memo, so a read after a write sees the write.
 *   <li>Outside a request (schedulers, async workers) calls go straight to the repository.
 * </ul>
 *
 * <p>Per-request only, so it adds no cross-instance state; still listed in the engineering
 * handbook's IN_MEMORY_STATE.md.
 *
 * <p>Writes through {@code MongoTemplate} are not seen; none exist for shops or plans today.
 */
@Aspect
@Component
public class RequestReadMemoAspect {

  static final String ATTRIBUTE = RequestReadMemoAspect.class.getName();
  static final String SHOP = "shop#";
  static final String PLAN = "plan#";

  private final MongoConverter converter;

  public RequestReadMemoAspect(MongoConverter converter) {
    this.converter = converter;
  }

  @Pointcut("execution(* findById(..)) || execution(* findByPlanName(..))")
  void singleReads() {}

  @Pointcut("execution(* save*(..)) || execution(* insert*(..)) || execution(* delete*(..))")
  void writes() {}

  @Around("bean(shopRepository) && singleReads()")
  public Object rememberShopRead(ProceedingJoinPoint jp) throws Throwable {
    return remember(SHOP, jp);
  }

  @Around("bean(planRepository) && singleReads()")
  public Object rememberPlanRead(ProceedingJoinPoint jp) throws Throwable {
    return remember(PLAN, jp);
  }

  @AfterReturning("bean(shopRepository) && writes()")
  public void forgetShopsAfterWrite(JoinPoint jp) {
    forget(SHOP);
  }

  @AfterReturning("bean(planRepository) && writes()")
  public void forgetPlansAfterWrite(JoinPoint jp) {
    forget(PLAN);
  }

  private Object remember(String prefix, ProceedingJoinPoint jp) throws Throwable {
    Map<String, Optional<Object>> memo = memo(true);
    if (memo == null) {
      return jp.proceed();
    }
    String key = prefix + jp.getSignature().getName() + Arrays.toString(jp.getArgs());
    Optional<Object> remembered = memo.get(key);
    if (remembered != null) {
      return remembered.map(this::copy);
    }
    Object result = jp.proceed();
    if (result instanceof Optional<?> opt) {
      memo.put(key, opt.map(this::copy));
    }
    return result;
  }

  private static void forget(String prefix) {
    Map<String, Optional<Object>> memo = memo(false);
    if (memo != null) {
      memo.keySet().removeIf(k -> k.startsWith(prefix));
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Optional<Object>> memo(boolean create) {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
      return null;
    }
    Object m = attrs.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (m == null && create) {
      m = new HashMap<String, Optional<Object>>();
      attrs.setAttribute(ATTRIBUTE, m, RequestAttributes.SCOPE_REQUEST);
    }
    return (Map<String, Optional<Object>>) m;
  }

  /** A deep, independent copy of a mapped entity. */
  private Object copy(Object entity) {
    Document doc = new Document();
    converter.write(entity, doc);
    return converter.read(entity.getClass(), doc);
  }
}
