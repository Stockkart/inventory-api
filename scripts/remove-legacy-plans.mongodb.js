/**
 * Reassigns shops off the legacy plans and removes those plans.
 *
 * Mapping is by nearest tier on price. Legacy plans ran ₹4,200–6,500 (arcPrice);
 * the new ladder is Starter ₹4,999 / Professional ₹6,999 / Enterprise ₹9,999.
 *
 *   Standard  4200 -> Starter
 *   Silver    4500 -> Starter
 *   Gold      5000 -> Professional
 *   Platinum  5500 -> Professional
 *   Diamond   6500 -> Professional
 *   Extra User Plan -> Additional User (add-on, not a tier)
 *
 * Shops keep their existing planExpiryDate — this re-points the subscription, it
 * does not extend or shorten it.
 *
 * plan_transactions are deliberately left alone: they store a planName snapshot,
 * so payment history stays readable even though planId becomes dangling. Rewriting
 * historical records to name a plan the customer never bought would be worse.
 *
 * Back up first with backup-legacy.mongodb.js. Safe to re-run.
 */

const LEGACY = {
  '69a86d8c3933c8737397dca8': { name: 'Standard', to: 'Starter' },
  '69a86d8c3933c8737397dca9': { name: 'Silver', to: 'Starter' },
  '69a86d8c3933c8737397dcaa': { name: 'Gold', to: 'Professional' },
  '69bd6f92f5d5d64d62feaf19': { name: 'Platinum', to: 'Professional' },
  '69a86d8c3933c8737397dcab': { name: 'Diamond', to: 'Professional' },
  '69a98227ca3f490e2e36d059': { name: 'Extra User Plan', to: 'Additional User' },
};

// Resolve targets up front — abort rather than strand shops on a deleted plan.
const targets = {};
for (const cfg of Object.values(LEGACY)) {
  if (targets[cfg.to]) continue;
  const t = db.plans.findOne({ planName: cfg.to });
  if (!t) {
    print('ABORT: target plan "' + cfg.to + '" not found. Seed the catalog first.');
    quit(1);
  }
  targets[cfg.to] = String(t._id);
}
print('resolved targets: ' + JSON.stringify(targets));

// 1) Re-point shops.
let moved = 0;
for (const [legacyId, cfg] of Object.entries(LEGACY)) {
  const shops = db.shops.find({ planId: legacyId }, { _id: 1, name: 1 }).toArray();
  if (shops.length === 0) continue;
  const targetId = targets[cfg.to];
  shops.forEach((s) => print('  ' + (s.name || s._id) + ': ' + cfg.name + ' -> ' + cfg.to));
  const res = db.shops.updateMany({ planId: legacyId }, { $set: { planId: targetId } });
  moved += res.modifiedCount;
}
print('shops re-pointed: ' + moved);

// 2) Clear inbound links so no surviving plan points at a deleted one.
const legacyIds = Object.keys(LEGACY);
const unlinked = db.plans.updateMany(
  { linkedId: { $in: legacyIds } },
  { $set: { linkedId: null } },
);
print('dangling linkedId cleared on ' + unlinked.modifiedCount + ' plan(s)');

// 3) Remove the legacy plans.
const del = db.plans.deleteMany({ _id: { $in: legacyIds.map((id) => ObjectId(id)) } });
print('legacy plans deleted: ' + del.deletedCount);

// 4) Verify.
print('\n--- remaining catalog ---');
db.plans
  .find({}, { planName: 1, arcPrice: 1, linkedId: 1, kind: 1, tierRank: 1 })
  .sort({ kind: 1, tierRank: 1, arcPrice: 1 })
  .forEach((p) => {
    const next = p.linkedId ? db.plans.findOne({ _id: ObjectId(p.linkedId) }, { planName: 1 }) : null;
    print(
      '  [' + (p.kind || 'PLAN') + '] ' +
        p.planName + ' ₹' + p.arcPrice +
        ' -> ' + (next ? next.planName : p.linkedId ? 'DANGLING ' + p.linkedId : 'null'),
    );
  });

print('\nplans remaining: ' + db.plans.countDocuments({}));
const stranded = db.shops.countDocuments({ planId: { $in: legacyIds } });
print('shops still on a legacy plan: ' + stranded + (stranded === 0 ? ' OK' : ' PROBLEM'));
