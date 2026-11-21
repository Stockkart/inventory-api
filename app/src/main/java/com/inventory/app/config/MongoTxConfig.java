package com.inventory.app.config;

import com.inventory.common.tx.MongoTx;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for {@link MongoTx}: a driver listener that tells it about write conflicts a service may
 * have rethrown without their cause, and {@code inventory_tx_total{flow, outcome}} (committed,
 * retried, failed, busy) per money flow.
 */
@Configuration
public class MongoTxConfig {

  static final String TX_TOTAL = "inventory_tx_total";

  @Bean
  public MongoClientSettingsBuilderCustomizer transientTransactionErrorListener() {
    CommandListener listener = new CommandListener() {
      @Override
      public void commandFailed(CommandFailedEvent event) {
        MongoTx.noteCommandFailure(event.getThrowable());
      }
    };
    return builder -> builder.addCommandListener(listener);
  }

  @Bean
  public InitializingBean mongoTxMetrics(MongoTx tx, MeterRegistry registry) {
    return () -> {
      for (String flow : MongoTx.FLOWS) {
        MongoTx.Counts c = tx.counts().computeIfAbsent(flow, k -> new MongoTx.Counts());
        register(registry, flow, "committed", c, x -> x.committed);
        register(registry, flow, "retried", c, x -> x.retried);
        register(registry, flow, "failed", c, x -> x.failed);
        register(registry, flow, "busy", c, x -> x.busy);
      }
    };
  }

  private static void register(
      MeterRegistry registry, String flow, String outcome, MongoTx.Counts c, Function<MongoTx.Counts, AtomicLong> pick) {
    FunctionCounter.builder(TX_TOTAL, c, x -> pick.apply(x).get())
        .description("MongoDB transactions per money flow: committed, retried after a write conflict, failed, busy (no turn for the shop in time)")
        .tag("flow", flow)
        .tag("outcome", outcome)
        .register(registry);
  }
}
