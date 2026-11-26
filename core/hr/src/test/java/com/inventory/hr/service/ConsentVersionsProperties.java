// Feature: HR-module, Property 21: Re-consent is required exactly when a newer re-consent version exists
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.service.ConsentVersions.ConsentVersion;
import com.inventory.hr.service.ConsentVersions.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * For any published consent history (increasing numbers, each {@code RECONSENT_REQUIRED} or
 * {@code WORDING_ONLY}) and any agreed version, {@code reconsentRequired(agreed)} is true iff
 * some {@code RECONSENT_REQUIRED} version is newer than the agreed one; a wording-only change
 * never asks a paired worker again, and agreeing to the latest version always settles it.
 *
 * <p><b>Validates: Requirements 9.12, 9.13</b>
 */
class ConsentVersionsProperties {

  private static final int MAX_VERSION = 60;

  /**
   * 1–12 versions with strictly increasing numbers in 1..60 and random kinds, oldest first. The
   * first version always requires consent (nobody agreed to anything before it), as in production.
   */
  @Provide
  Arbitrary<List<ConsentVersion>> histories() {
    Arbitrary<Set<Integer>> numbers = Arbitraries.integers().between(1, MAX_VERSION)
        .set().ofMinSize(1).ofMaxSize(12);
    Arbitrary<List<Kind>> kinds = Arbitraries.of(Kind.class).list().ofSize(12);
    return Combinators.combine(numbers, kinds).as((set, ks) -> {
      List<ConsentVersion> history = new ArrayList<>();
      int i = 0;
      for (int n : new TreeSet<>(set)) {
        Kind kind = i == 0 ? Kind.RECONSENT_REQUIRED : ks.get(i);
        history.add(new ConsentVersion(n, kind, List.of("recorded " + n),
            List.of("never " + n), List.of("seen by " + n)));
        i++;
      }
      return history;
    });
  }

  /** Never agreed (null), or any number around the published range, published or not. */
  @Provide
  Arbitrary<Integer> agreedVersions() {
    return Arbitraries.integers().between(-2, MAX_VERSION + 3).injectNull(0.1);
  }

  private static Optional<ConsentVersion> newestRequiring(List<ConsentVersion> history) {
    for (int i = history.size() - 1; i >= 0; i--) {
      if (history.get(i).kind() == Kind.RECONSENT_REQUIRED) {
        return Optional.of(history.get(i));
      }
    }
    return Optional.empty();
  }

  @Property(tries = 300)
  void reconsentIsRequiredIffARequiringVersionIsNewerThanTheAgreedOne(
      @ForAll("histories") List<ConsentVersion> history,
      @ForAll("agreedVersions") Integer agreed) {
    ConsentVersions versions = new ConsentVersions(history);
    int agreedNumber = agreed == null ? 0 : agreed;

    // the model (R9.13): some RECONSENT_REQUIRED version is newer than the agreed one
    boolean expected = history.stream()
        .filter(v -> v.kind() == Kind.RECONSENT_REQUIRED)
        .anyMatch(v -> v.version() > agreedNumber);

    assertEquals(expected, versions.reconsentRequired(agreed),
        "history " + history + " agreed " + agreed);
    assertEquals(versions.latestRequiringReconsent().version() > agreedNumber,
        versions.reconsentRequired(agreed));
  }

  @Property(tries = 300)
  void latestIsTheHighestNumberAndLatestRequiringIsTheNewestRequiringOrTheFirst(
      @ForAll("histories") List<ConsentVersion> history) {
    ConsentVersions versions = new ConsentVersions(history);

    ConsentVersion max = history.stream().max(java.util.Comparator.comparingInt(
        ConsentVersion::version)).orElseThrow();
    assertEquals(max, versions.latest());
    assertEquals(history.get(history.size() - 1), versions.latest());
    assertEquals(newestRequiring(history).orElse(history.get(0)),
        versions.latestRequiringReconsent());
  }

  @Property(tries = 300)
  void getReturnsTheExactPublishedEntryOrEmpty(@ForAll("histories") List<ConsentVersion> history,
      @ForAll("agreedVersions") Integer number) {
    ConsentVersions versions = new ConsentVersions(history);
    int n = number == null ? 0 : number;

    Optional<ConsentVersion> expected = history.stream().filter(v -> v.version() == n).findFirst();
    assertEquals(expected, versions.get(n));
    for (ConsentVersion v : history) {
      assertEquals(Optional.of(v), versions.get(v.version()));
    }
  }

  @Property(tries = 300)
  void wordingOnlyVersionsNeverTriggerReconsentAndAgreeingToTheLatestSettlesIt(
      @ForAll("histories") List<ConsentVersion> history) {
    ConsentVersions versions = new ConsentVersions(history);

    // a worker who agreed to a version is never asked again by wording-only versions after it
    for (int i = 0; i < history.size(); i++) {
      boolean requiringAfter = false;
      for (int j = i + 1; j < history.size(); j++) {
        requiringAfter |= history.get(j).kind() == Kind.RECONSENT_REQUIRED;
      }
      assertEquals(requiringAfter, versions.reconsentRequired(history.get(i).version()),
          "agreed to " + history.get(i).version() + " in " + history);
    }
    // agreed ≥ latest: never
    int latest = versions.latest().version();
    assertFalse(versions.reconsentRequired(latest));
    assertFalse(versions.reconsentRequired(latest + 1));
    // never agreed: always, whatever the kinds (the first version requires consent)
    assertTrue(versions.reconsentRequired(null));
    assertTrue(versions.reconsentRequired(0));
  }
}
