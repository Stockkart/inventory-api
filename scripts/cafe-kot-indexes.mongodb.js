// Cafe KOT indexes and migrations. Run against the target database before deploying the feature.

// --- Migration: tag pre-existing token counters with the bill scope -------------------------
//
// cafe_token_counters rows predate scoping and carry no `scope` field. The allocator tolerates
// that shape (a bill lookup also matches a row with no scope, or the day's numbering would
// restart at 1 and two tables would hold token "1"), but the old shape should stop existing.
// A row with no scope is the bill's: the bill was the only thing that ever allocated one.
db.cafe_token_counters.updateMany(
  { scope: { $exists: false } },
  { $set: { scope: "BILL" } }
);

db.cafe_token_counters.createIndex(
  { shopId: 1, businessDate: 1, scope: 1 },
  { unique: true, name: "shop_date_scope_unique" }
);

// --- Indexes -------------------------------------------------------------------------------

db.cafe_sequences.createIndex(
  { shopId: 1, businessDate: 1, series: 1 },
  { unique: true, name: "shop_date_series_unique" }
);

// A punch creates one ticket per department, so the punch id is not unique across them.
db.cafe_kots.createIndex({ shopId: 1, punchId: 1 }, { name: "shop_punch" });
