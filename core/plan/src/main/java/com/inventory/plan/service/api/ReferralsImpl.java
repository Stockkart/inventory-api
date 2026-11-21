package com.inventory.plan.service.api;

import com.inventory.plan.api.Referrals;
import com.inventory.plan.service.referral.ReferralAttributionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReferralsImpl implements Referrals {

  @Autowired
  private ReferralAttributionService referralAttributionService;

  @Override
  public void checkReferral(String referredByCode, String referredByName) {
    referralAttributionService.checkReferral(referredByCode, referredByName);
  }

  @Override
  public void attribute(String shopId, String registeringUserId, String referredByCode, String referredByName) {
    referralAttributionService.attribute(shopId, registeringUserId, referredByCode, referredByName);
  }
}
