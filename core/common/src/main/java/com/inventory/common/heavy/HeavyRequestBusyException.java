package com.inventory.common.heavy;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import lombok.Getter;

/** No free slot of this kind in time: answered as 503 with {@code Retry-After} (not an error in the code). */
@Getter
public class HeavyRequestBusyException extends BaseException {
  private final HeavyKind kind;

  public HeavyRequestBusyException(HeavyKind kind) {
    super(ErrorCode.SERVICE_BUSY, kind.busyMessage);
    this.kind = kind;
  }
}
