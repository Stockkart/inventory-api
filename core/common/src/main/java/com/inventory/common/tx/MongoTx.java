package com.inventory.common.tx;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.mongodb.MongoException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real MongoDB transactions for the flows that must be all-or-nothing (a bill, a refund, a vendor
 * invoice, a credit settlement): every write in {@link #run} commits together or not at all.
 *
 * <p>Opt-in by call, not by annotation. {@code @Transactional} stays a no-op in this code base
 * (AGENTS.md P8): no transaction manager is a bean, so switching on the ~200 annotations at once
 * (read-only ones included, which would put minute-long reports inside a transaction MongoDB
 * aborts after 60 s) cannot happen by accident. Repository and {@code MongoTemplate} calls made
 * inside {@link #run} on this thread join the transaction, raw {@code getCollection()} calls
 * included (Spring binds the session to the database it hands out).
 *
 * <p>One shop at a time: every money flow of a shop writes the same few documents (its Cash and
 * Sales accounts, invoice counter, month's usage), and MongoDB lets only one open transaction
 * write a document. Run side by side, they mostly abort each other (20 bills at once in one shop:
 * 2 completed, 547 attempts lost). So a shop's flows take turns in this container (a fair lock per
 * shop, waiting up to {@code mongo.transactions.shop-wait-ms}, then 503 "busy"); different shops
 * still run in parallel. A waiting request holds its Tomcat thread and no database connection.
 *
 * <p>Write conflicts that still happen (another container, or a flow not run through here) make
 * MongoDB abort one transaction with {@code TransientTransactionError}. The whole flow is then run
 * again from its first read, up to {@code mongo.transactions.max-attempts} times, with a short
 * random back-off. The flow must therefore only change the database (and
 * local objects) inside the callback; anything that leaves the process (an SSE push, a metric, an
 * email) goes through {@link #afterCommit} so it happens once, and only if the data was saved.
 *
 * <p>{@code mongo.transactions.enabled=false} runs the same callbacks without a transaction (the
 * behaviour before), as a quick way back.
 */
@Slf4j
@Component
public class MongoTx {

  static final String TRANSIENT = MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL;

  /** Flow names (logs, and the {@code flow} tag of {@code inventory_tx_total}). */
  public static final String BILL_COMPLETE = "bill_complete";
  public static final String REFUND = "refund";
  public static final String VENDOR_INVOICE = "vendor_invoice";
  public static final String VENDOR_RETURN = "vendor_return";
  public static final String CREDIT_CHARGE = "credit_charge";
  public static final String CREDIT_SETTLEMENT = "credit_settlement";
  public static final java.util.List<String> FLOWS = java.util.List.of(
      BILL_COMPLETE, REFUND, VENDOR_INVOICE, VENDOR_RETURN, CREDIT_CHARGE, CREDIT_SETTLEMENT);

  private final boolean enabled;
  private final int maxAttempts;
  private final long shopWaitMs;
  private final TransactionTemplate template;
  private final Map<String, Counts> counts = new ConcurrentHashMap<>();
  /** One lock per shop: a shop's money flows take turns in this container (see the class note). */
  private final Map<String, ReentrantLock> shopLocks = new ConcurrentHashMap<>();

  /**
   * Set when a MongoDB command on this thread failed with {@code TransientTransactionError} (see
   * {@link #noteCommandFailure}). Some services catch a database error and throw a new one
   * without its cause; the transaction is aborted all the same and must be run again.
   */
  private static final ThreadLocal<Boolean> TRANSIENT_SEEN = new ThreadLocal<>();

  /** Per flow: committed, retried (an attempt ended in a write conflict), failed, busy (no turn in time). */
  public static final class Counts {
    public final AtomicLong committed = new AtomicLong();
    public final AtomicLong retried = new AtomicLong();
    public final AtomicLong failed = new AtomicLong();
    public final AtomicLong busy = new AtomicLong();
  }

  @Autowired
  public MongoTx(
      MongoDatabaseFactory factory,
      @Value("${mongo.transactions.enabled:true}") boolean enabled,
      @Value("${mongo.transactions.max-attempts:5}") int maxAttempts,
      @Value("${mongo.transactions.max-commit-attempts:3}") int maxCommitAttempts,
      @Value("${mongo.transactions.shop-wait-ms:15000}") long shopWaitMs) {
    this(new TransactionTemplate(new RetryingCommitMongoTransactionManager(factory, maxCommitAttempts)),
        enabled, maxAttempts, shopWaitMs);
  }

  /** For tests: any template that runs the callback (and decides how a transaction is made). */
  MongoTx(TransactionTemplate template, boolean enabled, int maxAttempts, long shopWaitMs) {
    this.enabled = enabled;
    this.maxAttempts = Math.max(1, maxAttempts);
    this.shopWaitMs = Math.max(0, shopWaitMs);
    this.template = template;
    this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    log.info("[tx] MongoDB transactions {} (max {} attempts per flow, a shop's flows wait up to {} ms for their turn)",
        enabled ? "on" : "OFF", this.maxAttempts, this.shopWaitMs);
  }

  /**
   * Runs {@code work} for shop {@code shopId} in one transaction named {@code flow} (for logs and
   * metrics), after the shop's other money flows in this container (one at a time per shop). Inside
   * another {@code run} it simply joins that transaction (the outer one holds the turn and retries).
   *
   * @throws BaseException {@code SERVICE_BUSY} (503) when the shop's turn does not come within
   *     {@code mongo.transactions.shop-wait-ms}; nothing was changed
   */
  public <T> T run(String flow, String shopId, Supplier<T> work) {
    if (!enabled) {
      return work.get();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      return work.get();
    }
    Counts c = counts(flow);
    ReentrantLock turn = shopLocks.computeIfAbsent(shopId == null ? "" : shopId, k -> new ReentrantLock(true));
    boolean mine;
    try {
      mine = turn.tryLock(shopWaitMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      mine = false;
    }
    if (!mine) {
      c.busy.incrementAndGet();
      log.warn("[tx] {}: shop {} had {} flows waiting; no turn within {} ms", flow, shopId, turn.getQueueLength(), shopWaitMs);
      throw new BaseException(ErrorCode.SERVICE_BUSY, ErrorCode.SERVICE_BUSY.getMessage());
    }
    try {
      return attempts(flow, c, work);
    } finally {
      turn.unlock();
    }
  }

  private <T> T attempts(String flow, Counts c, Supplier<T> work) {
    for (int attempt = 1; ; attempt++) {
      TRANSIENT_SEEN.remove();
      try {
        T result = template.execute(status -> work.get());
        c.committed.incrementAndGet();
        return result;
      } catch (RuntimeException e) {
        boolean transientError = hasLabel(e, TRANSIENT) || Boolean.TRUE.equals(TRANSIENT_SEEN.get());
        if (attempt < maxAttempts && transientError) {
          c.retried.incrementAndGet();
          log.info("[tx] {}: write conflict, attempt {} of {} again: {}", flow, attempt + 1, maxAttempts, rootMessage(e));
          backOff(attempt);
          continue;
        }
        c.failed.incrementAndGet();
        throw e;
      } finally {
        TRANSIENT_SEEN.remove();
      }
    }
  }

  /**
   * Called by the driver's command listener (on the thread that ran the command) for every failed
   * command; remembers a {@code TransientTransactionError} for {@link #run}.
   */
  public static void noteCommandFailure(Throwable failure) {
    if (failure != null && hasLabel(failure, TRANSIENT)) {
      TRANSIENT_SEEN.set(Boolean.TRUE);
    }
  }

  /** As {@link #run(String, String, Supplier)} for work without a result. */
  public void run(String flow, String shopId, Runnable work) {
    run(flow, shopId, () -> {
      work.run();
      return null;
    });
  }

  /**
   * Runs {@code action} once the current transaction has committed, or now when there is none.
   * Not run if the transaction rolls back or is retried (the retry registers its own).
   */
  public static void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          try {
            action.run();
          } catch (RuntimeException e) {
            log.error("[tx] after-commit action failed (data is saved): {}", e.getMessage(), e);
          }
        }
      });
    } else {
      action.run();
    }
  }

  /** True while a transaction started by {@link #run} is open on this thread. */
  public static boolean inTransaction() {
    return TransactionSynchronizationManager.isActualTransactionActive();
  }

  public boolean enabled() {
    return enabled;
  }

  /** Counters per flow (for metrics). */
  public Map<String, Counts> counts() {
    return counts;
  }

  private Counts counts(String flow) {
    return counts.computeIfAbsent(flow, k -> new Counts());
  }

  /**
   * True when the error is a write conflict that outlived the retries (or happened outside {@link
   * #run}): the data is unchanged and trying again shortly will work, so it is answered as busy.
   */
  public static boolean isWriteConflict(Throwable e) {
    return hasLabel(e, TRANSIENT);
  }

  /** True when any cause is a MongoDB error carrying {@code label} (services wrap driver errors). */
  static boolean hasLabel(Throwable e, String label) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof MongoException m && m.hasErrorLabel(label)) {
        return true;
      }
    }
    return false;
  }

  private static String rootMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    return t.getMessage();
  }

  /** 10, 20, 40, … ms plus up to the same again at random, so two retrying flows drift apart. */
  private static void backOff(int attempt) {
    long base = 10L << Math.min(attempt - 1, 6);
    try {
      Thread.sleep(base + ThreadLocalRandom.current().nextLong(base + 1));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }
}
