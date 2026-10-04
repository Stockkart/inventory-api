package com.inventory.product.service;

import com.inventory.common.util.HsnCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The GST rates a given HSN attracts, from the CBIC rate notifications.
 *
 * <p>Exists to catch the one thing a shop's own records cannot: a rate keyed wrong the first time
 * and copied ever since. Comparing a product against its neighbours finds the odd one out, and
 * finds nothing at all when every product under an HSN carries the same wrong rate. Only a source
 * outside the shop can contradict a consensus.
 *
 * <p>An entry lists every rate the schedule allows for the code, because an entry there is limited
 * by its description as well as its code (toothpaste 5% and other oral-care goods 18% under 3306).
 * Only {@code verified} entries, taken from the notification, may overrule a shop; anything else
 * on file is ignored by the check.
 */
@Service
@Slf4j
public class HsnGstRateMaster {

  static final String CLASSPATH_RESOURCE = "classpath:hsn/hsn-gst-rates.json";

  private static final String SOURCE_VERIFIED = "verified";

  private final Map<String, Entry> byHsn;

  /** The rates this HSN attracts, and how far they can be trusted. */
  public record Entry(List<BigDecimal> rates, String source) {

    public Entry {
      rates = List.copyOf(rates);
    }

    /** Whether this entry may contradict a shop whose own records all agree. */
    public boolean isAuthoritative() {
      return SOURCE_VERIFIED.equalsIgnoreCase(source);
    }

    /** Whether {@code ratePct} is one of the rates on file for this HSN. */
    public boolean allows(BigDecimal ratePct) {
      return ratePct != null && rates.stream().anyMatch(rate -> rate.compareTo(ratePct) == 0);
    }
  }

  @Autowired
  public HsnGstRateMaster(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
    this(load(objectMapper, resourceLoader.getResource(CLASSPATH_RESOURCE)));
  }

  HsnGstRateMaster(Map<String, Entry> byHsn) {
    this.byHsn = Map.copyOf(byHsn);
  }

  /**
   * The rates recorded for an HSN, matching the most specific prefix on file.
   *
   * <p>An eight-digit code falls back to its six- and four-digit parents, which is how the
   * schedule itself reads: a heading sets a rate and its subheadings inherit it unless they say
   * otherwise.
   */
  public Optional<Entry> rateFor(String hsn) {
    return HsnCodes.mostSpecific(hsn, byHsn::get, 4);
  }

  private static Map<String, Entry> load(ObjectMapper objectMapper, Resource resource) {
    Map<String, Entry> out = new HashMap<>();
    if (resource == null || !resource.exists()) {
      log.warn("No HSN GST rate master at {}; rates will only be checked against a shop's own "
          + "catalogue", CLASSPATH_RESOURCE);
      return out;
    }
    try (InputStream in = resource.getInputStream()) {
      JsonNode root = objectMapper.readTree(in);
      JsonNode table = root.path("rates");
      Iterator<Map.Entry<String, JsonNode>> fields = table.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        JsonNode value = field.getValue();
        List<BigDecimal> rates = new ArrayList<>();
        value.path("rates").forEach(rate -> rates.add(new BigDecimal(rate.asText())));
        if (rates.isEmpty()) continue;
        out.put(field.getKey(), new Entry(rates, value.path("source").asText("")));
      }
      log.info("Loaded {} HSN GST rates ({} verified)", out.size(),
          out.values().stream().filter(Entry::isAuthoritative).count());
    } catch (Exception e) {
      // A rate table that will not load is a lost check, not a reason to refuse to start.
      log.error("Could not read the HSN GST rate master at {}", CLASSPATH_RESOURCE, e);
    }
    return out;
  }
}
