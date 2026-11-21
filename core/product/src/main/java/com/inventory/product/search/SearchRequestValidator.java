package com.inventory.product.search;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.labels.FieldUsage;
import com.inventory.product.labels.PrintableField;
import com.inventory.product.rest.dto.request.SearchRequest;
import com.inventory.product.rest.dto.request.SearchRequest.FilterGroup;
import com.inventory.product.search.SearchFieldCatalogService.ShopSearchContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Checks a {@link SearchRequest} against the shop's search catalog and turns it into a {@link
 * ValidatedSearch} (advanced-product-search R2.5, R2.7, R3.3, R4.4, R10.4, R10.6).
 *
 * <p>Every problem is collected and reported in one message, so a client learns everything wrong
 * with its request in one round trip. Pure apart from the clock.
 */
@Component
public class SearchRequestValidator {

  public static final int MAX_PAGE_SIZE = 200;
  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_OFFSET = 10_000;
  public static final int MAX_FACETS = 6;
  public static final int MAX_WITHIN_DAYS = 3650;

  private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

  private final Clock clock;

  public SearchRequestValidator() {
    this(Clock.systemUTC());
  }

  public SearchRequestValidator(Clock clock) {
    this.clock = clock;
  }

  public ValidatedSearch validate(SearchRequest req, ShopSearchContext ctx) {
    Set<String> errors = new LinkedHashSet<>();
    SearchRequest r = req == null ? new SearchRequest(null, null, null, null, null, null, null, null, null, null) : req;

    // ---- text ----------------------------------------------------------------------------------
    TextMode textMode = TextMode.PATTERN;
    if (StringUtils.hasText(r.textMode())) {
      Optional<TextMode> parsed = TextMode.parse(r.textMode());
      if (parsed.isPresent()) {
        textMode = parsed.get();
      } else {
        errors.add("textMode must be pattern or regex");
      }
    }
    TextPattern text = null;
    if (StringUtils.hasText(r.text())) {
      try {
        text = TextPattern.compile(r.text(), textMode);
      } catch (ValidationException e) {
        errors.add(e.getMessage());
      }
    }

    // ---- match ---------------------------------------------------------------------------------
    MatchMode match = MatchMode.ALL;
    if (StringUtils.hasText(r.match())) {
      Optional<MatchMode> parsed = MatchMode.parse(r.match());
      if (parsed.isPresent()) {
        match = parsed.get();
      } else {
        errors.add("match must be all or any");
      }
    }

    // ---- filters -------------------------------------------------------------------------------
    List<ValidatedSearch.Clause> clauses = new ArrayList<>();
    Set<String> seenFields = new LinkedHashSet<>();
    for (int i = 0; i < r.filters().size(); i++) {
      FilterGroup g = r.filters().get(i);
      String where = "filters[" + i + "]";
      if (g == null || !StringUtils.hasText(g.field())) {
        errors.add(where + ": field is required");
        continue;
      }
      String key = g.field().trim();
      Optional<PrintableField> fieldOpt = ctx.catalog().findForUsage(key, FieldUsage.SEARCH);
      if (fieldOpt.isEmpty()) {
        errors.add(where + ": unknown search field '" + key + "'");
        continue;
      }
      PrintableField field = fieldOpt.get();
      SearchSpec spec = field.searchSpec();
      Optional<FilterOp> opOpt = FilterOp.parse(g.op());
      if (opOpt.isEmpty()) {
        errors.add(where + " (" + key + "): operator is required (one of " + wireNames(spec.operators()) + ")");
        continue;
      }
      FilterOp op = opOpt.get();
      if (!spec.accepts(op)) {
        errors.add(where + " (" + key + "): operator '" + op.wireName() + "' is not allowed; use " + wireNames(spec.operators()));
        continue;
      }
      if (!seenFields.add(key + "/" + op.wireName())) {
        errors.add(where + " (" + key + "): the same field and operator appear twice; merge the values");
        continue;
      }
      clause(g, field, op, textMode, where, errors).ifPresent(clauses::add);
    }

    // ---- facets --------------------------------------------------------------------------------
    List<PrintableField> facets = new ArrayList<>();
    Set<String> seenFacets = new LinkedHashSet<>();
    for (String f : r.facets()) {
      if (!StringUtils.hasText(f) || !seenFacets.add(f.trim())) {
        continue;
      }
      Optional<PrintableField> fieldOpt = ctx.catalog().findForUsage(f.trim(), FieldUsage.SEARCH);
      if (fieldOpt.isEmpty()) {
        errors.add("facets: unknown search field '" + f.trim() + "'");
      } else if (!fieldOpt.get().searchSpec().facetable()) {
        errors.add("facets: '" + f.trim() + "' cannot show counts");
      } else {
        facets.add(fieldOpt.get());
      }
    }
    if (facets.size() > MAX_FACETS) {
      errors.add("facets: at most " + MAX_FACETS + " fields may show counts; " + facets.size() + " requested");
    }

    // ---- sort ----------------------------------------------------------------------------------
    ValidatedSearch.Sort sort = parseSort(StringUtils.hasText(r.sort()) ? r.sort() : ctx.defaultSort(), ctx, errors);

    // ---- paging --------------------------------------------------------------------------------
    int size = r.size() == null ? DEFAULT_PAGE_SIZE : r.size();
    if (size < 1 || size > MAX_PAGE_SIZE) {
      errors.add("size must be between 1 and " + MAX_PAGE_SIZE);
      size = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
    int page = r.page() == null ? 0 : r.page();
    if (page < 0) {
      errors.add("page must be 0 or more");
      page = 0;
    }
    if ((long) page * size > MAX_OFFSET) {
      errors.add("page × size must not exceed " + MAX_OFFSET + "; narrow the search instead");
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return new ValidatedSearch(
        text,
        clauses,
        match,
        facets,
        sort,
        page,
        size,
        r.includeZeroStock() == null || r.includeZeroStock(),
        r.surface());
  }

  // ---- one clause ------------------------------------------------------------------------------

  private Optional<ValidatedSearch.Clause> clause(
      FilterGroup g, PrintableField field, FilterOp op, TextMode textMode, String where, Set<String> errors) {
    SearchSpec spec = field.searchSpec();
    String name = where + " (" + field.fieldKey() + ")";
    List<String> values = g.values().stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
    switch (op) {
      case IN -> {
        if (values.isEmpty()) {
          errors.add(name + ": 'in' needs at least one value");
          return Optional.empty();
        }
        List<String> bad = values.stream().filter(v -> !spec.allowsValue(v)).toList();
        if (!bad.isEmpty()) {
          errors.add(name + ": values " + bad + " are not allowed; use " + spec.enumValues().stream().map(SearchSpec.EnumValue::value).toList());
          return Optional.empty();
        }
        return Optional.of(new ValidatedSearch.Clause(field, op, values, null, null, null, null, null));
      }
      case MATCHES -> {
        if (values.size() != 1) {
          errors.add(name + ": 'matches' needs exactly one pattern");
          return Optional.empty();
        }
        try {
          TextPattern p = TextPattern.compile(values.get(0), textMode);
          return Optional.of(new ValidatedSearch.Clause(field, op, values, p, null, null, null, null));
        } catch (ValidationException e) {
          errors.add(name + ": " + e.getMessage());
          return Optional.empty();
        }
      }
      case EXISTS -> {
        return Optional.of(new ValidatedSearch.Clause(field, op, List.of(), null, null, null, null, null));
      }
      case WITHIN_DAYS -> {
        if (values.size() != 1) {
          errors.add(name + ": 'withinDays' needs one number of days");
          return Optional.empty();
        }
        Integer days = parseInt(values.get(0));
        if (days == null || days < 0 || days > MAX_WITHIN_DAYS) {
          errors.add(name + ": 'withinDays' must be a whole number between 0 and " + MAX_WITHIN_DAYS);
          return Optional.empty();
        }
        Instant now = clock.instant();
        return Optional.of(
            new ValidatedSearch.Clause(field, op, values, null, null, null, now, now.plus(Duration.ofDays(days))));
      }
      case BETWEEN -> {
        boolean hasFrom = StringUtils.hasText(g.from());
        boolean hasTo = StringUtils.hasText(g.to());
        if (!hasFrom && !hasTo) {
          errors.add(name + ": 'between' needs 'from', 'to' or both");
          return Optional.empty();
        }
        if (spec.type() == SearchFieldType.NUMBER) {
          Double from = hasFrom ? parseDouble(g.from()) : null;
          Double to = hasTo ? parseDouble(g.to()) : null;
          if ((hasFrom && from == null) || (hasTo && to == null)) {
            errors.add(name + ": 'from' and 'to' must be numbers");
            return Optional.empty();
          }
          if (from != null && to != null && from > to) {
            errors.add(name + ": 'from' must not be greater than 'to'");
            return Optional.empty();
          }
          return Optional.of(new ValidatedSearch.Clause(field, op, List.of(), null, from, to, null, null));
        }
        Instant from = hasFrom ? parseDate(g.from(), false) : null;
        Instant to = hasTo ? parseDate(g.to(), true) : null;
        if ((hasFrom && from == null) || (hasTo && to == null)) {
          errors.add(name + ": 'from' and 'to' must be dates (YYYY-MM-DD or ISO date-time)");
          return Optional.empty();
        }
        if (from != null && to != null && from.isAfter(to)) {
          errors.add(name + ": 'from' must not be after 'to'");
          return Optional.empty();
        }
        return Optional.of(new ValidatedSearch.Clause(field, op, List.of(), null, null, null, from, to));
      }
    }
    return Optional.empty();
  }

  // ---- sort ------------------------------------------------------------------------------------

  private static ValidatedSearch.Sort parseSort(String raw, ShopSearchContext ctx, Set<String> errors) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    String[] parts = raw.trim().split(":", 2);
    String key = parts[0].trim();
    String dir = parts.length > 1 ? parts[1].trim().toLowerCase() : "asc";
    if (!dir.equals("asc") && !dir.equals("desc")) {
      errors.add("sort: direction must be asc or desc");
      return null;
    }
    Optional<PrintableField> field = ctx.catalog().findForUsage(key, FieldUsage.SEARCH);
    if (field.isEmpty()) {
      errors.add("sort: unknown search field '" + key + "'");
      return null;
    }
    if (!field.get().searchSpec().sortable()) {
      errors.add("sort: '" + key + "' cannot be sorted on");
      return null;
    }
    return new ValidatedSearch.Sort(field.get(), dir.equals("asc"));
  }

  // ---- parsing helpers -------------------------------------------------------------------------

  private static Integer parseInt(String raw) {
    try {
      return Integer.valueOf(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Double parseDouble(String raw) {
    try {
      double d = Double.parseDouble(raw.trim());
      return Double.isFinite(d) ? d : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Accepts {@code YYYY-MM-DD} (start or end of that day in IST) or a full ISO instant. */
  static Instant parseDate(String raw, boolean endOfDay) {
    String t = raw.trim();
    try {
      return Instant.parse(t);
    } catch (DateTimeParseException ignored) {
      // fall through
    }
    try {
      LocalDate d = LocalDate.parse(t);
      return endOfDay
          ? d.plusDays(1).atStartOfDay(ZONE).toInstant().minusMillis(1)
          : d.atStartOfDay(ZONE).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static List<String> wireNames(Set<FilterOp> ops) {
    return ops.stream().sorted().map(FilterOp::wireName).toList();
  }
}
