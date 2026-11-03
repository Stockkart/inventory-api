package com.inventory.user.service;

import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserShopMembership;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.context.ApplicationListener;
import org.springframework.data.mongodb.core.mapping.event.AfterDeleteEvent;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.data.mongodb.core.mapping.event.MongoMappingEvent;
import org.springframework.stereotype.Component;

/**
 * Keeps {@link AuthCache} honest: any save or delete of a user or a shop membership through the
 * repositories or the template's save/insert/remove evicts what the cache knows about that user
 * (logout, login, deactivation, shop switch, role or membership change all go through here).
 *
 * <p>Not seen: {@code updateFirst}/{@code updateMulti}/{@code findAndModify} on these collections
 * (none exist today except the expired-token cleanup, whose tokens the cache already rejects), and
 * changes made outside this instance. Those are bounded by the cache's 60 s expiry.
 */
@Component
@RequiredArgsConstructor
public class AuthCacheEvictionListener implements ApplicationListener<MongoMappingEvent<?>> {

  private final AuthCache authCache;

  @Override
  public void onApplicationEvent(MongoMappingEvent<?> event) {
    if (event instanceof AfterSaveEvent<?> saved) {
      Object source = saved.getSource();
      if (source instanceof UserAccount account) {
        authCache.evictUser(account.getUserId());
      } else if (source instanceof UserShopMembership membership) {
        authCache.evictUser(membership.getUserId());
      }
    } else if (event instanceof AfterDeleteEvent<?> deleted) {
      Class<?> type = deleted.getType();
      if (type == UserAccount.class) {
        String id = idOf(deleted.getSource());
        if (id != null) {
          authCache.evictUser(id);
        } else {
          authCache.clear();
        }
      } else if (type == UserShopMembership.class) {
        // the delete names the membership, not its user
        authCache.evictAllShopAccess();
      }
    }
  }

  private static String idOf(Document query) {
    Object id = query != null ? query.get("_id") : null;
    return id instanceof String s ? s : id != null && !(id instanceof Document) ? id.toString() : null;
  }
}
