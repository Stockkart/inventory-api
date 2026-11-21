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
 * <p>Shop reads also go through {@link ShopReadCache}, which keeps them across requests for 60 s
 * (cleared on any shop write on this instance); that one is cross-request state and is listed in
 * the engineering handbook's IN_MEMORY_STATE.md. The per-request memo itself adds none.
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
  private final ShopReadCache shopReadCache;

  public RequestReadMemoAspect(MongoConverter converter, ShopReadCache shopReadCache) {
    this.converter = converter;
    this.shopReadCache = shopReadCache;
  }

  @Pointcut("execution(* findById(..)) || execution(* findByPlanName(..))")
  void singleReads() {}

  @Pointcut("execution(* save*(..)) || execution(* insert*(..)) || execution(* delete*(..))")
  void writes() {}

  @Around("bean(shopRepository) && singleReads()")
  public Object rememberShopRead(ProceedingJoinPoint jp) throws Throwable {
    return remember(SHOP, jp, shopReadCache);
  }

  @Around("bean(planRepository) && singleReads()")
  public Object rememberPlanRead(ProceedingJoinPoint jp) throws Throwable {
    return remember(PLAN, jp, null);
  }

  @AfterReturning("bean(shopRepository) && writes()")
  public void forgetShopsAfterWrite(JoinPoint jp) {
    shopReadCache.clear();
    forget(SHOP);
  }

  @AfterReturning("bean(planRepository) && writes()")
  public void forgetPlansAfterWrite(JoinPoint jp) {
    forget(PLAN);
  }

  /**
   * Request memo first, then (shops only) the cross-request cache, then MongoDB. What is stored is
   * a pristine copy; callers always get a copy of it.
   */
  private Object remember(String prefix, ProceedingJoinPoint jp, ShopReadCache shared) throws Throwable {
    Map<String, Optional<Object>> memo = memo(true);
    String key = prefix + jp.getSignature().getName() + Arrays.toString(jp.getArgs());
    Optional<Object> remembered = memo != null ? memo.get(key) : null;
    if (remembered == null && shared != null) {
      remembered = shared.get(key);
      if (remembered != null && memo != null) {
        memo.put(key, remembered);
      }
    }
    if (remembered != null) {
      return remembered.map(this::copy);
    }
    if (memo == null && shared == null) {
      return jp.proceed();
    }
    long generation = shared != null ? shared.generation() : 0;
    Object result = jp.proceed();
    if (result instanceof Optional<?> opt) {
      Optional<Object> pristine = opt.map(this::copy);
      if (memo != null) {
        memo.put(key, pristine);
      }
      if (shared != null) {
        shared.put(key, pristine, generation);
      }
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
