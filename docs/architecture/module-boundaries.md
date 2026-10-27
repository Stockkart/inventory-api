# Module boundaries

The backend is one program split into modules (`core/<module>`, `pluginengine`, `plugins/<vertical>`,
`app`). Each module has one public front door, its `api` package. Everything else in a module is
private to it. Each MongoDB collection belongs to exactly one module.

The rules are data in [`module-boundaries.yml`](../../module-boundaries.yml) and are checked by
`app/src/test/java/com/inventory/app/boundaries/ModuleBoundariesTest.java`, which runs in every
build and in the **Module boundaries** workflow.

## Rules

- **B1. One public package.** Module `x` exposes `com.inventory.x.api`. Optional sub-packages:
  `api.port` (what `x` needs from a module above it) and `api.events` (facts `x` announces).
- **B2. Use other modules only through `api`.** Module `a` may use module `b` only if `dependsOn`
  of `a` lists `b` (it mirrors `pom.xml`), and only through `com.inventory.b.api..`.
- **B3. Libraries are public.** `common`, `metrics` and `pluginengine` are shared libraries; every
  package in them is public.
- **B4. What goes in `api`.** Interfaces, records and enums only (Lombok `@Builder` on a record is
  fine). No Spring, Mongo or Lombok types, and no use of the module's own private packages.
- **B5. Implementations stay home.** A class implementing a module's `api` interface lives in that
  module's `service/api/` package.
- **B6. Ports.** If a lower module needs something from a higher one, the lower module declares an
  interface in its `api.port`, named `<Thing>Port`, and the higher module implements it in its own
  `service/api/`. An interface implemented by another module must be in `api.port`.
- **B7. Shop data takes `shopId`.** Every `api` method that reads or changes shop data takes
  `shopId` as its first argument.
- **B8. One owner per collection.** `module-boundaries.yml` lists each collection under its owner.
  Only the owner maps it with `@Document` or names it in code (constants, `MongoTemplate`,
  `getCollection`, `$lookup`).
- **B9. Written exceptions.** A non-owner that must read a collection is listed under `readers`
  with a reason. A string that only looks like a collection name gets the comment
  `// boundaries:not-a-collection` on its line.
- **B10. `app` wires the program.** Packages listed under `compositionRoot` may reference any
  module. The rest of `app` follows B2.
- **B11. No optional cross-module beans.** Never `@Autowired(required = false)` on another
  module's type; a missing bean must stop startup, not skip work silently.

## Existing violations

Violations that existed when the check was introduced are listed in
`app/src/test/resources/boundaries/baseline.txt`. The check:

- passes a violation listed there,
- fails on any violation that is not listed (fix it; never add lines by hand),
- fails on a listed line that is no longer a violation (delete the line in the same change).

So the list only shrinks. To regenerate it after fixing violations:

```bash
mvn -pl app -am test -Dtest=ModuleBoundariesTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dboundaries.writeBaseline=true
```

Then check with `git diff` that the only change is removed lines.

## Running the check

```bash
mvn -pl app -am test -Dtest=ModuleBoundariesTest -Dsurefire.failIfNoSpecifiedTests=false
```

A failure prints each violation, for example:

```
NOT_API product -> user : com.inventory.product.service.BarcodeService -> com.inventory.user.domain.repository.VendorRepository
```

Fix it by using the other module's `api` package. If that package does not yet offer what you
need, add it there (B4, B5) in the same change.
