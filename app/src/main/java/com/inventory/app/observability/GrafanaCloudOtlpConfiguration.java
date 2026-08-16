package com.inventory.app.observability;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(GrafanaCloudProperties.class)
public class GrafanaCloudOtlpConfiguration {

  @Bean
  MeterRegistryCustomizer<MeterRegistry> grafanaCloudCommonTags(
      @Value("${spring.profiles.active:local}") String activeProfiles) {
    String env = envLabel(activeProfiles);
    return registry -> registry.config().commonTags("service", "inventory-api", "env", env);
  }

  @Bean
  @ConditionalOnExpression(
      "T(org.springframework.util.StringUtils).hasText('${grafana.cloud.otlp.url:}')")
  OtlpMeterRegistry otlpMeterRegistry(
      GrafanaCloudProperties props,
      Clock clock,
      @Value("${spring.profiles.active:local}") String activeProfiles) {
    String user = props.getOtlp().getUser();
    String token = props.getApiToken();
    String env = envLabel(activeProfiles);
    String basic =
        Base64.getEncoder()
            .encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
    OtlpConfig config =
        new OtlpConfig() {
          @Override
          public String get(String key) {
            return null;
          }

          @Override
          public String url() {
            return props.getOtlp().getUrl();
          }

          @Override
          public Duration step() {
            // Grafana Cloud Mimir: 75 req/s per tenant. 5s export + JVM/HTTP meters
            // bursts that budget (HTTP 429 err-mimir-tenant-max-request-rate).
            return Duration.ofSeconds(60);
          }

          @Override
          public int batchSize() {
            return 10_000;
          }

          @Override
          public Map<String, String> resourceAttributes() {
            return Map.of("service.name", "inventory-api", "deployment.environment", env);
          }

          @Override
          public Map<String, String> headers() {
            return Map.of("Authorization", "Basic " + basic);
          }
        };
    return new OtlpMeterRegistry(config, clock);
  }

  /** Loki/OTLP label; first profile only (commas break Loki streams). */
  static String envLabel(String activeProfiles) {
    if (!StringUtils.hasText(activeProfiles)) {
      return "local";
    }
    String first = activeProfiles.trim().split(",")[0].trim();
    return StringUtils.hasText(first) ? first : "local";
  }
}
