package com.inventory.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.common.advice.GlobalExceptionHandler;
import com.mongodb.MongoClientSettings;
import com.mongodb.connection.ConnectionId;
import com.mongodb.connection.ServerId;
import com.mongodb.connection.ClusterId;
import com.mongodb.ServerAddress;
import com.mongodb.event.ConnectionCheckOutFailedEvent;
import com.mongodb.event.ConnectionPoolListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** A request waits at most mongo.pool.max-wait-ms for a pooled connection (driver default: 120 s). */
class MongoPoolConfigTest {

  @AfterEach
  void reset() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static MongoClientSettings settings(SimpleMeterRegistry registry) {
    MongoClientSettings.Builder builder = MongoClientSettings.builder();
    new MongoPoolConfig().connectionPoolWait(2000, registry).customize(builder);
    return builder.build();
  }

  private static ConnectionCheckOutFailedEvent failed(ConnectionCheckOutFailedEvent.Reason reason) {
    ServerId server = new ServerId(new ClusterId(), new ServerAddress("localhost", 27017));
    return new ConnectionCheckOutFailedEvent(server, 1L, reason, 2_000_000_000L);
  }

  @Test
  void poolWaitIsCappedAtTheConfiguredTime() {
    assertThat(settings(new SimpleMeterRegistry()).getConnectionPoolSettings().getMaxWaitTime(TimeUnit.MILLISECONDS))
        .isEqualTo(2000);
  }

  @Test
  void driverDefaultIsTwoMinutes() {
    assertThat(MongoClientSettings.builder().build().getConnectionPoolSettings().getMaxWaitTime(TimeUnit.MILLISECONDS))
        .isEqualTo(120_000);
  }

  @Test
  void aWaitThatRunsOutIsCountedAndMarksTheRequest() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    List<ConnectionPoolListener> listeners = settings(registry).getConnectionPoolSettings().getConnectionPoolListeners();
    MockHttpServletRequest request = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    listeners.forEach(l -> l.connectionCheckOutFailed(failed(ConnectionCheckOutFailedEvent.Reason.CONNECTION_ERROR)));
    assertThat(request.getAttribute(GlobalExceptionHandler.MONGO_BUSY_ATTRIBUTE)).isNull();

    listeners.forEach(l -> l.connectionCheckOutFailed(failed(ConnectionCheckOutFailedEvent.Reason.TIMEOUT)));
    assertThat(request.getAttribute(GlobalExceptionHandler.MONGO_BUSY_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
    assertThat(registry.get(MongoPoolConfig.TIMEOUTS).counter().count()).isEqualTo(1.0);
  }

  @Test
  void aTimeoutOutsideARequestIsOnlyCounted() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    settings(registry).getConnectionPoolSettings().getConnectionPoolListeners()
        .forEach(l -> l.connectionCheckOutFailed(failed(ConnectionCheckOutFailedEvent.Reason.TIMEOUT)));

    assertThat(registry.get(MongoPoolConfig.TIMEOUTS).counter().count()).isEqualTo(1.0);
  }
}
