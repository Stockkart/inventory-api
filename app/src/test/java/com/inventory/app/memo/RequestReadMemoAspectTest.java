package com.inventory.app.memo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Shop and plan reads are made once per request and handed out as independent copies. */
@SpringJUnitConfig(RequestReadMemoAspectTest.Config.class)
class RequestReadMemoAspectTest {

  /** Stand-in for a mapped entity. */
  public static class Thing {
    public String id;
    public String name;

    public Thing() {}

    Thing(String id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  /** Stand-in for ShopRepository / PlanRepository: same bean names, same method names. */
  public static class FakeRepository {
    final AtomicInteger reads = new AtomicInteger();
    String stored = "original";

    public Optional<Thing> findById(String id) {
      reads.incrementAndGet();
      return "missing".equals(id) ? Optional.empty() : Optional.of(new Thing(id, stored));
    }

    public Optional<Thing> findByPlanName(String name) {
      reads.incrementAndGet();
      return Optional.of(new Thing(name, stored));
    }

    public Thing save(Thing t) {
      stored = t.name;
      return t;
    }

    // accessed through the CGLIB proxy, so state is read via methods, not fields
    public int readCount() {
      return reads.get();
    }

    public void resetFake() {
      reads.set(0);
      stored = "original";
    }
  }

  @Configuration
  @EnableAspectJAutoProxy(proxyTargetClass = true)
  static class Config {
    @Bean
    MongoConverter mongoConverter() {
      MappingMongoConverter c = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, new MongoMappingContext());
      c.afterPropertiesSet();
      return c;
    }

    @Bean
    RequestReadMemoAspect requestReadMemoAspect(MongoConverter converter) {
      return new RequestReadMemoAspect(converter);
    }

    @Bean
    FakeRepository shopRepository() {
      return new FakeRepository();
    }

    @Bean
    FakeRepository planRepository() {
      return new FakeRepository();
    }

    @Bean
    FakeRepository customerRepository() {
      return new FakeRepository();
    }
  }

  @Autowired FakeRepository shopRepository;
  @Autowired FakeRepository planRepository;
  @Autowired FakeRepository customerRepository;

  private void inRequest() {
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  @AfterEach
  void reset() {
    RequestContextHolder.resetRequestAttributes();
    for (FakeRepository r : new FakeRepository[] {shopRepository, planRepository, customerRepository}) {
      r.resetFake();
    }
  }

  @Test
  void sameShopReadTwiceInOneRequestGoesToTheDatabaseOnce() {
    inRequest();
    Thing a = shopRepository.findById("s1").orElseThrow();
    Thing b = shopRepository.findById("s1").orElseThrow();

    assertThat(shopRepository.readCount()).isEqualTo(1);
    assertThat(b.name).isEqualTo("original");
    assertThat(b).isNotSameAs(a);
  }

  @Test
  void oneCallerChangingItsCopyDoesNotChangeTheNextCallersCopy() {
    inRequest();
    shopRepository.findById("s1").orElseThrow().name = "changed in memory";
    shopRepository.findById("s1").orElseThrow().name = "changed again";

    assertThat(shopRepository.findById("s1").orElseThrow().name).isEqualTo("original");
  }

  @Test
  void aSaveInTheRequestMakesTheNextReadGoToTheDatabase() {
    inRequest();
    shopRepository.findById("s1");
    shopRepository.save(new Thing("s1", "renamed"));

    assertThat(shopRepository.findById("s1").orElseThrow().name).isEqualTo("renamed");
    assertThat(shopRepository.readCount()).isEqualTo(2);
  }

  @Test
  void eachRequestReadsAfresh() {
    inRequest();
    shopRepository.findById("s1");
    inRequest(); // a new request
    shopRepository.findById("s1");

    assertThat(shopRepository.readCount()).isEqualTo(2);
  }

  @Test
  void outsideARequestEveryReadGoesToTheDatabase() {
    shopRepository.findById("s1");
    shopRepository.findById("s1");

    assertThat(shopRepository.readCount()).isEqualTo(2);
  }

  @Test
  void notFoundIsRememberedToo() {
    inRequest();
    assertThat(shopRepository.findById("missing")).isEmpty();
    assertThat(shopRepository.findById("missing")).isEmpty();
    assertThat(shopRepository.readCount()).isEqualTo(1);
  }

  @Test
  void planReadsAreRememberedSeparatelyFromShopReads() {
    inRequest();
    shopRepository.findById("x");
    planRepository.findById("x");
    planRepository.findById("x");
    planRepository.findByPlanName("Base");
    planRepository.findByPlanName("Base");
    shopRepository.save(new Thing("x", "y")); // a shop write keeps the plan memo

    planRepository.findById("x");
    assertThat(shopRepository.readCount()).isEqualTo(1);
    assertThat(planRepository.readCount()).isEqualTo(2);
  }

  @Test
  void otherRepositoriesAreNotTouched() {
    inRequest();
    customerRepository.findById("c1");
    customerRepository.findById("c1");
    assertThat(customerRepository.readCount()).isEqualTo(2);
  }
}
