package com.inventory.metrics.http;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Spring MVC {@code http.server.requests} tags plus {@code module} from controller annotations.
 */
public class ModuleServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

  public static final String MODULE_TAG = "module";

  @Override
  public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
    return super.getLowCardinalityKeyValues(context)
        .and(KeyValue.of(MODULE_TAG, HttpServerModuleTags.module(context.getCarrier())));
  }
}
