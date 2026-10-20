package com.inventory.app.config;

import com.inventory.common.advice.GlobalExceptionHandler;
import com.mongodb.event.ConnectionCheckOutFailedEvent;
import com.mongodb.event.ConnectionPoolListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * How long a request may wait for a free MongoDB connection ({@code mongo.pool.max-wait-ms}).
 *
 * <p>The driver's default is 120 s: when every pooled connection is busy (the load test filled the
 * pool at 50–200 users), requests queued for up to two minutes and the screens just spun. With a
 * short wait the request fails fast instead and is answered 503 "busy, please try again" with
 * {@code Retry-After}. A connection is normally free at once, so the wait only matters under
 * overload. Set through the code (not the {@code DB_URI} secret); a {@code waitQueueTimeoutMS} in
 * the URI is overridden by this.
 *
 * <p>A wait that runs out is recorded on the current request ({@link
 * GlobalExceptionHandler#MONGO_BUSY_ATTRIBUTE}), so the answer is 503 even where a service wraps
 * the error without its cause; and counted in {@code inventory_mongo_pool_wait_timeouts_total}.
 */
@Slf4j
@Configuration
public class MongoPoolConfig {

  static final String TIMEOUTS = "inventory_mongo_pool_wait_timeouts_total";

  @Bean
  public MongoClientSettingsBuilderCustomizer connectionPoolWait(
      @Value("${mongo.pool.max-wait-ms:2000}") long maxWaitMs, MeterRegistry registry) {
    log.info("Mongo connection pool: wait at most {} ms for a free connection", maxWaitMs);
    Counter timeouts = Counter.builder(TIMEOUTS)
        .description("Requests that found no free MongoDB connection within mongo.pool.max-wait-ms")
        .register(registry);
    ConnectionPoolListener onTimeout = new ConnectionPoolListener() {
      @Override
      public void connectionCheckOutFailed(ConnectionCheckOutFailedEvent event) {
        if (event.getReason() == ConnectionCheckOutFailedEvent.Reason.TIMEOUT) {
          timeouts.increment();
          markCurrentRequestBusy();
        }
      }
    };
    return builder -> builder.applyToConnectionPoolSettings(pool -> pool
        .maxWaitTime(maxWaitMs, TimeUnit.MILLISECONDS)
        .addConnectionPoolListener(onTimeout));
  }

  /** Check-out runs on the calling thread, so this is the request that waited (if any). */
  static void markCurrentRequestBusy() {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs != null) {
      attrs.setAttribute(GlobalExceptionHandler.MONGO_BUSY_ATTRIBUTE, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
    }
  }
}
