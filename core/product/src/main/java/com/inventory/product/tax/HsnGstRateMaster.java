package com.inventory.product.tax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * The GST rate a given HSN attracts, where someone has said so.
 *
 * <p>Exists to catch the one thing a shop's own records cannot: a rate keyed wrong the first time
 * and copied ever since. Comparing a product against its neighbours finds the odd one out, and
 * finds nothing at all when every product under an HSN carries the same wrong rate. Only a source
 * outside the shop can contradict a consensus.
 *
 * <p>Which is why entries carry their provenance and are not all equal:
 *
 * <ul>
 *   <li>{@code catalogue-unanimous} — taken from shop records where every product agreed. Evidence,
 *       not authority. It cannot overrule a shop, because it is only that shop's own belief
 *       written down, and a belief that is wrong everywhere agrees with itself perfectly.
 *   <li>{@code verified} — checked against the GST schedule by a person. This one can overrule a
 *       shop, and is the only kind that catches a rate that is wrong everywhere.
 * </ul>
 *
 * <p>The seed ships entirely as {@code catalogue-unanimous} deliberately. Populating it with rates
 * nobody checked would flag correct products as wrong and teach operators to ignore the warning,
 * which costs more than the errors it would catch. Entries are promoted as they are verified.
 */
@Service
@Slf4j
public class HsnGstRateMaster {

  static final String CLASSPATH_RESOURCE = "classpath:hsn/hsn-gst-rates.json";

  private static final String SOURCE_VERIFIED = "verified";

  private final Map<String, Entry> byHsn;

  /** A rate this HSN attracts, and how far it can be trusted. */
  public record Entry(BigDecimal ratePct, String source) {

    /** Whether this entry may contradict a shop whose own records all agree. */
    public boolean isAuthoritative() {
      return SOURCE_VERIFIED.equalsIgnoreCase(source);
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
   * The rate recorded for an HSN, matching the most specific prefix on file.
   *
   * <p>An eight-digit code falls back to its six- and four-digit parents, which is how the
   * schedule itself reads: a heading sets a rate and its subheadings inherit it unless they say
   * otherwise.
   */
  public Optional<Entry> rateFor(String hsn) {
    String digits = digitsOnly(hsn);
    if (!StringUtils.hasText(digits) || "0".equals(digits)) {
      return Optional.empty();
    }
    for (int length = digits.length(); length >= 4; length--) {
      Entry entry = byHsn.get(digits.substring(0, length));
      if (entry != null) {
        return Optional.of(entry);
      }
    }
    return Optional.empty();
  }

  private static String digitsOnly(String raw) {
    if (raw == null) return "";
    StringBuilder out = new StringBuilder();
    for (char c : raw.toCharArray()) {
      if (Character.isDigit(c)) out.append(c);
    }
    return out.toString();
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
      JsonNode rates = root.path("rates");
      Iterator<Map.Entry<String, JsonNode>> fields = rates.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        JsonNode value = field.getValue();
        if (!value.hasNonNull("ratePct")) continue;
        out.put(field.getKey(),
            new Entry(new BigDecimal(value.get("ratePct").asText()),
                value.path("source").asText("catalogue-unanimous")));
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
