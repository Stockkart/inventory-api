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
    String env = activeProfiles.contains(",") ? activeProfiles.split(",")[0] : activeProfiles;
    if (!StringUtils.hasText(env)) {
      env = "local";
    }
    String envTag = env;
    return registry -> registry.config().commonTags("service", "inventory-api", "env", envTag);
  }

  @Bean
  @ConditionalOnExpression(
      "T(org.springframework.util.StringUtils).hasText('${grafana.cloud.otlp.url:}')")
  OtlpMeterRegistry otlpMeterRegistry(GrafanaCloudProperties props, Clock clock) {
    String user = props.getOtlp().getUser();
    String token = props.getApiToken();
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
            return Duration.ofSeconds(60);
          }

          @Override
          public Map<String, String> headers() {
            return Map.of("Authorization", "Basic " + basic);
          }
        };
    return new OtlpMeterRegistry(config, clock);
  }
}
