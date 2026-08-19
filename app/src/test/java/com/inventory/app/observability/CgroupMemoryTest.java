package com.inventory.app.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CgroupMemoryTest {

  @Test
  void workingSetSubtractsInactiveFile() {
    String stat = "anon 400\ninactive_file 100\nfile 100\n";
    assertEquals(300L, CgroupMemory.workingSet(400L, stat));
  }

  @Test
  void workingSetPrefersHierarchicalTotalInactiveFile() {
    String stat = "inactive_file 50\ntotal_inactive_file 80\n";
    assertEquals(20L, CgroupMemory.workingSet(100L, stat));
  }

  @Test
  void anonBytesReadsV2AnonAndV1Rss() {
    assertEquals(350L, CgroupMemory.anonBytes("anon 350\ninactive_file 100\n"));
    assertEquals(200L, CgroupMemory.anonBytes("rss 200\ncache 50\n"));
  }

  @Test
  void parseLimitTreatsMaxAndHugeV1AsUnlimited() {
    assertEquals(0L, CgroupMemory.parseLimit("max"));
    assertEquals(0L, CgroupMemory.parseLimit("9223372036854771712"));
    assertEquals(536870912L, CgroupMemory.parseLimit("536870912"));
  }
}
