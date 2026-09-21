// Cafe KOT indexes. Run against the target database before deploying the feature.
//
// Idempotency for a punch lives on the punch record embedded on the Purchase, never on
// cafe_kots: one punch creates one ticket per department, so a key on the ticket could not be
// unique across them.

db.cafe_sequences.createIndex(
  { shopId: 1, businessDate: 1, series: 1 },
  { unique: true, name: "shop_date_series_unique" }
);

db.cafe_kots.createIndex({ shopId: 1, punchId: 1 }, { name: "shop_punch" });
