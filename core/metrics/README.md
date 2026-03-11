# Metrics Module

Shared metrics infrastructure for all modules. Add `metrics` as a dependency and use annotations or `MetricsWrapper`.

## Annotations

### @Latency
Records latency (p50, p90, p95, p99) and request count. Place on controller class or method.

```java
@RestController
@Latency(module = "product")
@RecordStatusCodes(module = "product")
public class CheckoutController { ... }
```

### @RecordStatusCodes
Records HTTP response status codes (200, 400, 500, etc.). Place on controller class.

```java
@RestController
@RecordStatusCodes(module = "user")
public class AuthController { ... }
```

### @RecordRequestRate
Increments a counter per invocation. Use when you need a separate counter without timing (e.g. @Latency already provides count).

```java
@RecordRequestRate(module = "analytics")
public void trackEvent() { ... }
```

## MetricsWrapper

For custom metrics:

```java
@Autowired
private MetricsWrapper metrics;

// Record value (count + sum)
metrics.record("checkout_amount", 150.50);

// Record count of 1
metrics.record("cart_created");

// Record latency
metrics.recordLatency("my_operation", () -> doSomething());
```

## Dashboards

Each module gets a dedicated Grafana dashboard. Add `module="<name>"` in annotations to filter. Create `grafana/provisioning/dashboards/json/<module>-module.json` for new modules.
