/** Read-only. Dumps every document the legacy-plan removal will touch. */

const LEGACY_IDS = [
  '69a86d8c3933c8737397dca8', // Standard
  '69a86d8c3933c8737397dca9', // Silver
  '69a86d8c3933c8737397dcaa', // Gold
  '69bd6f92f5d5d64d62feaf19', // Platinum
  '69a86d8c3933c8737397dcab', // Diamond
  '69a98227ca3f490e2e36d059', // Extra User Plan
];

const backup = {
  takenFor: 'remove-legacy-plans',
  plans: db.plans.find({ _id: { $in: LEGACY_IDS.map((id) => ObjectId(id)) } }).toArray(),
  affectedShops: db.shops.find({ planId: { $in: LEGACY_IDS } }).toArray(),
};

print(JSON.stringify(backup, null, 2));
