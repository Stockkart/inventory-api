package com.inventory.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.bson.types.Decimal128;

/**
 * MongoDB configuration to properly handle BigDecimal conversion.
 * Converts BigDecimal to MongoDB's Decimal128 type for proper storage and retrieval.
 */
@Configuration
public class MongoConfig {

  @Bean
  public MongoCustomConversions customConversions() {
    List<Converter<?, ?>> converters = new ArrayList<>();
    converters.add(new BigDecimalToDecimal128Converter());
    converters.add(new Decimal128ToBigDecimalConverter());
    return new MongoCustomConversions(converters);
  }

  /**
   * Converter from BigDecimal to Decimal128 (for writing to MongoDB).
   */
  @WritingConverter
  static class BigDecimalToDecimal128Converter implements Converter<BigDecimal, Decimal128> {
    @Override
    public Decimal128 convert(BigDecimal source) {
      return source == null ? null : Decimal128.parse(source.toString());
    }
  }

  /**
   * Converter from Decimal128 to BigDecimal (for reading from MongoDB).
   */
  @ReadingConverter
  static class Decimal128ToBigDecimalConverter implements Converter<Decimal128, BigDecimal> {
    @Override
    public BigDecimal convert(Decimal128 source) {
      return source == null ? null : source.bigDecimalValue();
    }
  }
}

