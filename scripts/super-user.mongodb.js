/**
 * Operator script (mongosh): grant or revoke the platform-level SUPER_USER role by email.
 *
 * SUPER_USER is for internal Stockkart staff only (Stockkart HQ sign-in, shop approval). No API can
 * grant it; this script is the only way. A SUPER_USER account has no shop and no shop memberships,
 * so grant refuses any account that has a shopId or any user_shop_memberships row.
 *
 * Inputs come from environment variables; this file holds no credentials. The connection string
 * is the app's own DB_URI (it must include the database name, e.g. .../inventory?...), expanded by
 * your shell, never written here or in shell history files you commit:
 *
 *   SUPER_USER_EMAIL=staff@example.com SUPER_USER_ACTION=grant \
 *     mongosh "$DB_URI" --quiet scripts/super-user.mongodb.js
 *
 *   SUPER_USER_EMAIL=staff@example.com SUPER_USER_ACTION=revoke \
 *     mongosh "$DB_URI" --quiet scripts/super-user.mongodb.js
 *
 * Optional: SUPER_USER_DRY_RUN=true prints what would change and writes nothing.
 *
 * grant:  sets role SUPER_USER. Existing sessions keep working; the new role is seen within the
 *         login cache TTL (AUTH_CACHE_TTL_SECONDS, 60 s by default).
 * revoke: sets role CASHIER (the role the web signup uses) and drops every login token, so HQ and
 *         any other session must sign in again (cached logins end within AUTH_CACHE_TTL_SECONDS).
 */

const email = (process.env.SUPER_USER_EMAIL || '').trim();
const action = (process.env.SUPER_USER_ACTION || '').trim().toLowerCase();
const dryRun = (process.env.SUPER_USER_DRY_RUN || '').trim().toLowerCase() === 'true';

function fail(message) {
  print('REFUSED: ' + message);
  quit(1);
}

if (!email) {
  fail('set SUPER_USER_EMAIL to the account email');
}
if (action !== 'grant' && action !== 'revoke') {
  fail('set SUPER_USER_ACTION to grant or revoke');
}

print('Database: ' + db.getName() + (dryRun ? ' (dry run)' : ''));

const matches = db.users.find({ email: { $in: [email, email.toLowerCase()] } }).toArray();
if (matches.length === 0) {
  fail('no account with email ' + email + ' in database ' + db.getName()
    + ' (does DB_URI name the right database?)');
}
if (matches.length > 1) {
  fail('more than one account matches ' + email + '; fix the duplicates first');
}

const user = matches[0];
const userId = typeof user._id === 'string' ? user._id : user._id.toHexString();
print('Account: ' + userId + ' <' + user.email + '>, role ' + user.role
  + ', shopId ' + (user.shopId || '(none)') + ', active ' + user.active);

if (action === 'grant') {
  if (user.role === 'SUPER_USER') {
    print('Already SUPER_USER; nothing to do.');
    quit(0);
  }
  // SUPER_USER accounts are never shop people: refuse any trace of a shop.
  if (user.shopId !== undefined && user.shopId !== null && user.shopId !== '') {
    fail('account has a shopId (' + JSON.stringify(user.shopId) + '); use a dedicated staff account');
  }
  const memberships = db.user_shop_memberships.find({ userId: userId }).toArray();
  if (memberships.length > 0) {
    fail('account has ' + memberships.length + ' shop membership(s) ('
      + memberships.map((m) => m.shopId + (m.active ? '' : ' inactive')).join(', ')
      + '); use a dedicated staff account');
  }
  // Not blocking (accepting them is rejected by the API), but worth knowing
  const pendingInvites = db.invitations.countDocuments({ inviteeUserId: userId, status: 'PENDING' });
  const pendingJoins = db.join_requests.countDocuments({ userId: userId, status: 'PENDING' });
  if (pendingInvites > 0 || pendingJoins > 0) {
    print('WARNING: ' + pendingInvites + ' pending invitation(s) and ' + pendingJoins
      + ' pending join request(s); they can no longer be accepted');
  }
  if (user.active === false) {
    print('WARNING: account is deactivated; it cannot sign in until reactivated');
  }
  if (dryRun) {
    print('Would set role SUPER_USER.');
    quit(0);
  }
  const res = db.users.updateOne(
    { _id: user._id, role: user.role, $or: [{ shopId: null }, { shopId: '' }] },
    { $set: { role: 'SUPER_USER', updatedAt: new Date() } }
  );
  if (res.modifiedCount !== 1) {
    fail('account changed while the script ran; run it again');
  }
  print('Granted SUPER_USER to ' + user.email);
} else {
  if (user.role !== 'SUPER_USER') {
    fail('account is not SUPER_USER (role ' + user.role + '); nothing to revoke');
  }
  if (dryRun) {
    print('Would set role CASHIER and drop ' + (user.tokens || []).length + ' login token(s).');
    quit(0);
  }
  const res = db.users.updateOne(
    { _id: user._id, role: 'SUPER_USER' },
    { $set: { role: 'CASHIER', tokens: [], updatedAt: new Date() } }
  );
  if (res.modifiedCount !== 1) {
    fail('account changed while the script ran; run it again');
  }
  print('Revoked SUPER_USER from ' + user.email + ' and signed out all its sessions');
}
