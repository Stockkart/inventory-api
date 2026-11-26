package com.inventory.hr.service;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The consent texts the Attendance app shows before pairing (R2.8) and on re-consent (R9.12–R9.13),
 * kept in code so the app, the server and the one-page privacy note (R9.14) cannot drift.
 *
 * <p>Every change to the text is a new version with a higher number. A version is marked {@link
 * Kind#RECONSENT_REQUIRED} when it adds a recorded data item or a new use of recorded data; a
 * {@link Kind#WORDING_ONLY} version does not ask paired workers to agree again.
 */
@Component
public class ConsentVersions {

  /** Why a version was published (R9.12). */
  public enum Kind {
    RECONSENT_REQUIRED,
    WORDING_ONLY
  }

  /**
   * One published version: three plain-word lists (what is recorded, what is never recorded, who
   * can see it).
   */
  public record ConsentVersion(
      int version,
      Kind kind,
      List<String> recorded,
      List<String> neverRecorded,
      List<String> visibility) {}

  /** Oldest first; the number of each entry is greater than the one before it. */
  static final List<ConsentVersion> VERSIONS = List.of(
      new ConsentVersion(
          1,
          Kind.RECONSENT_REQUIRED,
          List.of(
              "Your check-in and check-out time",
              "How far from the shop you were",
              "That your fingerprint or face check passed"),
          List.of("Your fingerprint", "Your face", "Your exact location"),
          List.of("The shop's managers can see your attendance")));

  private final List<ConsentVersion> versions;

  /** The published versions ({@link #VERSIONS}). */
  public ConsentVersions() {
    this(VERSIONS);
  }

  /**
   * An arbitrary published history, for tests ({@code ConsentVersionsProperties}); production
   * always uses {@link #VERSIONS}.
   *
   * @param versions oldest first, at least one entry, strictly increasing numbers
   */
  ConsentVersions(List<ConsentVersion> versions) {
    if (versions == null || versions.isEmpty()) {
      throw new IllegalArgumentException("at least one consent version is required");
    }
    for (int i = 1; i < versions.size(); i++) {
      if (versions.get(i).version() <= versions.get(i - 1).version()) {
        throw new IllegalArgumentException("consent version numbers must increase");
      }
    }
    this.versions = List.copyOf(versions);
  }

  /** The newest version, the one shown at pairing. */
  public ConsentVersion latest() {
    return versions.get(versions.size() - 1);
  }

  /** The newest version that asks paired workers to agree again. */
  public ConsentVersion latestRequiringReconsent() {
    for (int i = versions.size() - 1; i >= 0; i--) {
      if (versions.get(i).kind() == Kind.RECONSENT_REQUIRED) {
        return versions.get(i);
      }
    }
    // the first version always requires consent
    return versions.get(0);
  }

  /** The version with that number, if it was ever published. */
  public Optional<ConsentVersion> get(int version) {
    return versions.stream().filter(v -> v.version() == version).findFirst();
  }

  /**
   * True when a worker who agreed to {@code agreedVersion} (null = never) must agree again before
   * punching (R9.13).
   */
  public boolean reconsentRequired(Integer agreedVersion) {
    int agreed = agreedVersion != null ? agreedVersion : 0;
    return latestRequiringReconsent().version() > agreed;
  }
}
