package com.inventory.taxation.gstin;

import com.inventory.common.gst.Gstin;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * gstinapi.in — {@code GET /v1/gstin/{gstin}} with an {@code x-api-key} header. A reseller over a
 * licensed GST Suvidha Provider; answers are normalised snake_case JSON.
 *
 * <p>Response shape (the parts read here):
 *
 * <pre>
 * { "success": true, "credits_remaining": 487,
 *   "data": { "gstin", "legal_name", "trade_name", "status", "taxpayer_type",
 *             "registration_date", "cancellation_date", "state_code",
 *             "address", "city", "pincode", "address_details": { … } } }
 * </pre>
 *
 * Everything provider-specific stays in this class.
 */
@Slf4j
public class GstinApiInLookupProvider implements GstinLookupProvider {

  public static final String NAME = "gstinapi.in";
  static final String DEFAULT_BASE_URL = "https://www.gstinapi.in";

  private final RestClient client;
  private final String apiKey;
  private final Consumer<Long> creditsRemaining;

  public GstinApiInLookupProvider(String baseUrl, String apiKey, Duration timeout, Consumer<Long> creditsRemaining) {
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.creditsRemaining = creditsRemaining == null ? c -> {} : creditsRemaining;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    this.client =
        RestClient.builder()
            .baseUrl(StringUtils.hasText(baseUrl) ? baseUrl.trim() : DEFAULT_BASE_URL)
            .requestFactory(factory)
            .build();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isConfigured() {
    return StringUtils.hasText(apiKey);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Optional<GstinRecord> lookup(Gstin gstin) {
    if (!isConfigured()) {
      throw new GstinLookupException("gstinapi.in API key is not configured (gstin.gstinapi.api-key)");
    }
    Map<String, Object> body;
    try {
      body =
          client
              .get()
              .uri("/v1/gstin/{gstin}", gstin.value())
              .header("x-api-key", apiKey)
              .header("Accept", "application/json")
              .retrieve()
              .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {}) // decided below
              .body(Map.class);
    } catch (RestClientResponseException e) {
      throw new GstinLookupException("gstinapi.in answered HTTP " + e.getStatusCode().value(), e);
    } catch (ResourceAccessException e) {
      throw new GstinLookupException("gstinapi.in could not be reached: " + e.getMessage(), e);
    }
    if (body == null) {
      throw new GstinLookupException("gstinapi.in returned an empty response");
    }
    Object credits = body.get("credits_remaining");
    if (credits instanceof Number n) {
      creditsRemaining.accept(n.longValue());
    }
    Object data = body.get("data");
    if (!Boolean.TRUE.equals(body.get("success")) || !(data instanceof Map<?, ?> dataMap)) {
      // 404 / not found: {"success": false, "code": "GSTIN_NOT_FOUND", ...}
      String code = String.valueOf(body.getOrDefault("code", body.getOrDefault("error", "")));
      if (code.toUpperCase().contains("NOT_FOUND") || code.toUpperCase().contains("INVALID")) {
        return Optional.empty();
      }
      throw new GstinLookupException("gstinapi.in refused the lookup: " + code);
    }
    return Optional.of(toRecord(gstin, (Map<String, Object>) dataMap));
  }

  /** The anti-corruption step: provider names in, our names out. */
  static GstinRecord toRecord(Gstin gstin, Map<String, Object> d) {
    Instant now = Instant.now();
    String stateCode = text(d.get("state_code"));
    GstinRecord.AddressDetails details = null;
    if (d.get("address_details") instanceof Map<?, ?> ad) {
      Map<?, ?> a = ad;
      details =
          GstinRecord.AddressDetails.builder()
              .buildingNumber(text(a.get("building_number")))
              .buildingName(text(a.get("building_name")))
              .floor(text(a.get("floor")))
              .street(text(a.get("street")))
              .locality(text(a.get("locality")))
              .district(text(a.get("district")))
              .city(text(a.get("city")))
              .state(text(a.get("state")))
              .landmark(text(a.get("landmark")))
              .pincode(text(a.get("pincode")))
              .build();
    }
    String city = text(d.get("city"));
    if (!StringUtils.hasText(city) && details != null) {
      city = StringUtils.hasText(details.getCity()) ? details.getCity() : details.getLocality();
    }
    String pincode = text(d.get("pincode"));
    if (!StringUtils.hasText(pincode) && details != null) {
      pincode = details.getPincode();
    }
    return GstinRecord.builder()
        .gstin(gstin.value())
        .legalName(text(d.get("legal_name")))
        .tradeName(text(d.get("trade_name")))
        .status(text(d.get("status")))
        .taxpayerType(text(d.get("taxpayer_type")))
        .registrationDate(date(d.get("registration_date")))
        .cancellationDate(date(d.get("cancellation_date")))
        // the registry's own state wins; the GSTIN's first two digits are the fallback
        .stateCode(StringUtils.hasText(stateCode) ? stateCode : gstin.stateCode())
        .address(text(d.get("address")))
        .city(city)
        .pincode(pincode)
        .addressDetails(details)
        .provider(NAME)
        .fetchedAt(now)
        .lastCheckedAt(now)
        .raw(new LinkedHashMap<>(d))
        .build();
  }

  private static String text(Object o) {
    if (o == null) return null;
    String s = String.valueOf(o).trim();
    return s.isEmpty() || "null".equals(s) ? null : s;
  }

  private static LocalDate date(Object o) {
    String s = text(o);
    if (s == null) return null;
    try {
      return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    } catch (DateTimeParseException e) {
      log.debug("[gstin] unreadable date '{}' from gstinapi.in", s);
      return null;
    }
  }
}
