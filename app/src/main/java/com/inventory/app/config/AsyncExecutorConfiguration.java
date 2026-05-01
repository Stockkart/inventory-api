package com.inventory.app.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncExecutorConfiguration {

  @Bean(name = "taskExecutor")
  ThreadPoolTaskExecutor taskExecutor(
      @Value("${async.core-pool-size:4}") int corePoolSize,
      @Value("${async.max-pool-size:16}") int maxPoolSize,
      @Value("${async.queue-capacity:500}") int queueCapacity) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(corePoolSize);
    executor.setMaxPoolSize(maxPoolSize);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("async-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }

  @Bean(name = "ocrTaskExecutor")
  ThreadPoolTaskExecutor ocrTaskExecutor(
      @Value("${ocr.executor.core-size:2}") int corePoolSize,
      @Value("${ocr.executor.max-size:4}") int maxPoolSize,
      @Value("${ocr.executor.queue-capacity:100}") int queueCapacity) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(corePoolSize);
    executor.setMaxPoolSize(maxPoolSize);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("ocr-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }

  @Bean
  AsyncConfigurer asyncConfigurer(
      @Qualifier("taskExecutor") ThreadPoolTaskExecutor taskExecutor) {
    return new AsyncConfigurer() {
      @Override
      public Executor getAsyncExecutor() {
        return taskExecutor;
      }
    };
  }
}
