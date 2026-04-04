/**
 * Optional manual migration (mongosh) — same logic runs automatically on app startup
 * via LotIdToVendorPurchaseInvoiceMigration unless SKIP_INVENTORY_MIGRATIONS=true.
 *
 * Usage (replace DB name):
 *   mongosh "mongodb://localhost:27017/inventory" migrate-lot-id-to-stock-in.mongodb.js
 *
 * Requires MongoDB 4.2+ (pipeline updates).
 */

const MIGRATION_KEY = 'lotId_to_vendor_purchase_invoice_v1';

const existing = db.app_migrations.findOne({ _id: MIGRATION_KEY });
if (existing) {
  print('Migration already applied: ' + MIGRATION_KEY);
  quit(0);
}

// 1) Align lotId with vendorPurchaseInvoiceId where both exist
const align = db.inventory.updateMany(
  {
    $expr: {
      $and: [
        { $ne: [{ $ifNull: ['$vendorPurchaseInvoiceId', ''] }, ''] },
        { $ne: [{ $ifNull: ['$lotId', ''] }, ''] },
        { $ne: ['$lotId', '$vendorPurchaseInvoiceId'] },
      ],
    },
  },
  [{ $set: { lotId: '$vendorPurchaseInvoiceId' } }]
);
print('Align lotId → vendorPurchaseInvoiceId:', align.matchedCount, align.modifiedCount);

// 2) Backfill: groups with lotId but no vendorPurchaseInvoiceId
const groups = db.inventory.aggregate([
  {
    $match: {
      lotId: { $exists: true, $nin: [null, ''] },
      $or: [
        { vendorPurchaseInvoiceId: { $exists: false } },
        { vendorPurchaseInvoiceId: null },
        { vendorPurchaseInvoiceId: '' },
      ],
    },
  },
  { $group: { _id: { shopId: '$shopId', lotId: '$lotId' }, vendorId: { $first: '$vendorId' } } },
]);

let created = 0;
groups.forEach((g) => {
  const shopId = g._id.shopId;
  const legacyLotId = g._id.lotId;
  let vendorId = g.vendorId;
  if (!vendorId) vendorId = 'UNKNOWN';
  const invoiceNo = 'MIGRATED-' + ObjectId().toString();
  const now = new Date();
  const ins = db.vendor_purchase_invoices.insertOne({
    shopId,
    vendorId,
    invoiceNo,
    synthetic: true,
    legacyLotId,
    lines: [],
    createdAt: now,
    createdByUserId: 'migration',
  });
  const newId = ins.insertedId.toString();
  const up = db.inventory.updateMany(
    {
      shopId,
      lotId: legacyLotId,
      $or: [
        { vendorPurchaseInvoiceId: { $exists: false } },
        { vendorPurchaseInvoiceId: null },
        { vendorPurchaseInvoiceId: '' },
      ],
    },
    { $set: { lotId: newId, vendorPurchaseInvoiceId: newId } }
  );
  if (up.modifiedCount > 0) created++;
});
print('Backfill invoice groups:', created);

// 3) Drop obsolete lotId on vendor_purchase_invoices documents
const unset = db.vendor_purchase_invoices.updateMany(
  { lotId: { $exists: true } },
  { $unset: { lotId: '' } }
);
print('Unset lotId on invoices:', unset.matchedCount, unset.modifiedCount);

db.app_migrations.insertOne({ _id: MIGRATION_KEY, appliedAt: new Date() });
print('Done:', MIGRATION_KEY);
