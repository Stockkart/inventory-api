// Cafe KOT indexes. Run against the target database before deploying the feature.
//
// Idempotency for a punch lives on cafe_order_punches, never on cafe_kots: one punch creates
// one ticket per department, so a key on the ticket could not be unique across them.

db.cafe_sequences.createIndex(
  { shopId: 1, businessDate: 1, series: 1 },
  { unique: true, name: "shop_date_series_unique" }
);

db.cafe_order_punches.createIndex(
  { shopId: 1, idempotencyKey: 1 },
  { unique: true, name: "shop_idempotency_unique" }
);
db.cafe_order_punches.createIndex({ shopId: 1, orderId: 1 }, { name: "shop_order" });

db.cafe_kots.createIndex({ shopId: 1, orderId: 1 }, { name: "shop_order" });
db.cafe_kots.createIndex({ shopId: 1, punchId: 1 }, { name: "shop_punch" });

db.cafe_orders.createIndex(
  { shopId: 1, purchaseId: 1 },
  { unique: true, sparse: true, name: "shop_purchase_unique" }
);
db.cafe_orders.createIndex({ shopId: 1, status: 1 }, { name: "shop_status" });
