package com.inventory.plan.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.CampaignTheme;
import com.inventory.plan.rest.dto.request.CampaignActiveRequest;
import com.inventory.plan.rest.dto.request.CampaignRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CampaignValidatorTest {

  private final CampaignValidator validator = new CampaignValidator();

  private static CampaignRequest.CampaignRequestBuilder valid() {
    return CampaignRequest.builder()
        .code("MONSOON_2026")
        .headline("Monsoon sale")
        .ctaLabel("See plans")
        .ctaPath("/plans")
        .theme(CampaignTheme.MONSOON)
        .announceFrom(Instant.parse("2026-10-01T00:00:00Z"))
        .startsAt(Instant.parse("2026-10-10T00:00:00Z"))
        .endsAt(Instant.parse("2026-10-20T00:00:00Z"));
  }

  @Test
  void acceptsAValidCampaign() {
    assertThatCode(() -> validator.validateCreate(valid().build())).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://evil.example", "//evil.example", "plans", "/plans?x=1", "javascript:alert(1)"})
  void rejectsCtaPathsThatLeaveTheApp(String path) {
    assertThatThrownBy(() -> validator.validateCreate(valid().ctaPath(path).build()))
        .isInstanceOf(ValidationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ab", "monsoon", "MONSOON-2026", "A_VERY_LONG_CAMPAIGN_CODE_THAT_KEEPS_GOING_ON"})
  void rejectsBadCodes(String code) {
    assertThatThrownBy(() -> validator.validateCreate(valid().code(code).build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void rejectsBadDates() {
    assertThatThrownBy(() -> validator.validateCreate(valid()
        .endsAt(Instant.parse("2026-10-10T00:00:00Z")).build()))
        .hasMessageContaining("Start must be before end");
    assertThatThrownBy(() -> validator.validateCreate(valid()
        .announceFrom(Instant.parse("2026-10-11T00:00:00Z")).build()))
        .hasMessageContaining("Announce date");
    assertThatThrownBy(() -> validator.validateCreate(valid().startsAt(null).build()))
        .hasMessageContaining("Start and end are required");
  }

  @Test
  void rejectsOutOfRangeNumbersAndMissingFields() {
    assertThatThrownBy(() -> validator.validateCreate(valid().imminentThresholdDays(31).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> validator.validateCreate(valid().priority(-1).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> validator.validateCreate(valid().theme(null).build()))
        .hasMessageContaining("Theme");
    assertThatThrownBy(() -> validator.validateCreate(valid().headline(" ").build()))
        .hasMessageContaining("Headline");
    assertThatThrownBy(() -> validator.validateCreate(valid().ctaPath(null).build()))
        .hasMessageContaining("go together");
  }

  @Test
  void updateSkipsCodeButNeedsAnId() {
    assertThatCode(() -> validator.validateUpdate("id1", valid().code(null).build()))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validateUpdate(" ", valid().build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void activeFlagIsRequired() {
    assertThatThrownBy(() -> validator.validateActive("id1", new CampaignActiveRequest(null, null)))
        .isInstanceOf(ValidationException.class);
  }
}
