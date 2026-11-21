package com.inventory.taxation.gstin;

import com.inventory.metrics.MetricsWrapper;
import com.inventory.taxation.utils.constants.TaxationMetricsConstants;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the GSTIN lookup provider from {@code gstin.provider}:
 *
 * <ul>
 *   <li>{@code gstinapi} — gstinapi.in with {@code gstin.gstinapi.api-key}
 *   <li>{@code none} (default) — offline checks only; lookups report "could not verify"
 * </ul>
 */
@Slf4j
@Configuration
public class GstinProviderConfig {

  @Value("${gstin.gstinapi.base-url:https://www.gstinapi.in}")
  private String gstinApiBaseUrl;

  @Value("${gstin.gstinapi.api-key:}")
  private String gstinApiKey;

  @Value("${gstin.lookup-timeout-seconds:8}")
  private long timeoutSeconds;

  @Bean
  @ConditionalOnProperty(name = "gstin.provider", havingValue = "gstinapi")
  public GstinLookupProvider gstinApiInLookupProvider(MetricsWrapper metrics) {
    if (gstinApiKey == null || gstinApiKey.isBlank()) {
      log.warn("[gstin] gstin.provider=gstinapi but gstin.gstinapi.api-key is empty; lookups will fail until it is set");
    }
    return new GstinApiInLookupProvider(
        gstinApiBaseUrl,
        gstinApiKey,
        Duration.ofSeconds(timeoutSeconds),
        credits -> metrics.record(TaxationMetricsConstants.GSTIN_CREDITS_REMAINING, credits, "module", TaxationMetricsConstants.MODULE, "provider", GstinApiInLookupProvider.NAME));
  }

  @Bean
  @ConditionalOnProperty(name = "gstin.provider", havingValue = "none", matchIfMissing = true)
  public GstinLookupProvider noopGstinLookupProvider() {
    log.info("[gstin] no lookup provider configured; GSTINs are checked offline only");
    return new NoopGstinLookupProvider();
  }
}
