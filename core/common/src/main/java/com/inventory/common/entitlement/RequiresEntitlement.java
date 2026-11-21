package com.inventory.common.entitlement;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller or handler method as available only to shops whose plan grants {@link #value()}.
 * A method-level annotation overrides the class-level one.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresEntitlement {

  PlanFeature value();
}
