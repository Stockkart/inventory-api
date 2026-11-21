package com.inventory.plan.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

/** Plans are granted only by the verified payment flow, so the shop controller has no write route. */
class PlanControllerRoutesTest {

  @Test
  void thePlanControllerHasNoPostRoute() {
    List<String> postRoutes = Arrays.stream(PlanController.class.getDeclaredMethods())
        .map(m -> m.getAnnotation(PostMapping.class))
        .filter(a -> a != null)
        .flatMap(a -> Arrays.stream(a.value()))
        .toList();

    assertEquals(List.of(), postRoutes);
  }

  @Test
  void noHandlerTakesAnAssignRequest() {
    for (Method m : PlanController.class.getDeclaredMethods()) {
      for (Class<?> p : m.getParameterTypes()) {
        assertEquals(false, p.getSimpleName().equals("AssignPlanRequest"), m.getName());
      }
    }
  }
}
