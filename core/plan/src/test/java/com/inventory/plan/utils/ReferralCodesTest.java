package com.inventory.plan.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ReferralCodesTest {

  @Test
  void codesUseThePrefixAndOnlyUnambiguousCharacters() {
    Random random = new Random(7);
    for (int i = 0; i < 500; i++) {
      String code = ReferralCodes.generate(random);
      assertThat(code).startsWith("SK-").hasSize(9);
      assertThat(code.substring(3)).doesNotContain("0", "O", "1", "I", "L");
    }
  }

  @Test
  void normaliseMakesCaseAndSpacingIrrelevant() {
    assertThat(ReferralCodes.normalise("  sk-ab2cd3 ")).isEqualTo("SK-AB2CD3");
    assertThat(ReferralCodes.normalise(null)).isEmpty();
  }
}
