package com.inventory.metrics.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

@Configuration
public class HttpServerMetricsConfiguration {

  @Bean
  ServerRequestObservationConvention moduleServerRequestObservationConvention() {
    return new ModuleServerRequestObservationConvention();
  }
}
