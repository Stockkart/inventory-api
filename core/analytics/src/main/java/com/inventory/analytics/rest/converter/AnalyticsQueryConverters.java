package com.inventory.analytics.rest.converter;

import com.inventory.analytics.domain.model.MisTxnType;
import com.inventory.analytics.domain.model.MoneyFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

/**
 * Query-parameter binding for report filter enums.
 *
 * <p>Spring's built-in enum converter rejects anything it does not recognise with a 400. These
 * filters are optional report controls where an unrecognised value previously just widened the
 * result set, so rejecting the whole request would be a behaviour change for any client sending a
 * stale filter name. Both converters therefore degrade instead of failing.
 */
@Configuration
public class AnalyticsQueryConverters {

  /** Unknown or blank filter means no filtering. */
  @Bean
  public Converter<String, MoneyFilter> moneyFilterConverter() {
    return new Converter<>() {
      @Override
      @NonNull
      public MoneyFilter convert(@NonNull String source) {
        return MoneyFilter.from(source);
      }
    };
  }

  /**
   * Unknown transaction types convert to null and are dropped by the service, so a request naming
   * one known and one stale type still filters on the known one.
   */
  @Bean
  public Converter<String, MisTxnType> misTxnTypeConverter() {
    return new Converter<>() {
      @Override
      @Nullable
      public MisTxnType convert(@NonNull String source) {
        return MisTxnType.parse(source).orElse(null);
      }
    };
  }
}
