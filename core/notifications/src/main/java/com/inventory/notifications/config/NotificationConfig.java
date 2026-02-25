package com.inventory.notifications.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configuration for notification async execution and retry.
 */
@Slf4j
@Configuration
public class NotificationConfig {

  @Value("${notification.executor.core-pool-size:2}")
  private int corePoolSize;

  @Value("${notification.executor.max-pool-size:5}")
  private int maxPoolSize;

  @Value("${notification.executor.queue-capacity:100}")
  private int queueCapacity;

  @Bean(name = "notificationExecutor")
  public Executor notificationExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(corePoolSize);
    executor.setMaxPoolSize(maxPoolSize);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("notification-");
    executor.setRejectedExecutionHandler((r, e) ->
        log.warn("Notification task rejected - queue full. Consider increasing queue capacity."));
    executor.initialize();
    return executor;
  }
}
