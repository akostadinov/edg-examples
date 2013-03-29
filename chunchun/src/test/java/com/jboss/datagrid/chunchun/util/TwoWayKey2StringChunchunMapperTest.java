package com.jboss.datagrid.chunchun.util;

import org.infinispan.loaders.keymappers.TwoWayKey2StringMapper;
import org.infinispan.util.ByteArrayKey;
import org.testng.Reporter;
import org.testng.TestException;
import org.testng.annotations.Test;

import com.jboss.datagrid.chunchun.model.PostKey;

public class TwoWayKey2StringChunchunMapperTest {

  @Test
  public void twoWayKey2StringChunchunMapper() {
     TwoWayKey2StringMapper mapper = new TwoWayKey2StringChunchunMapper();
     Object[] sourceData = new Object[] {
        new PostKey("user5", 1l),
        new PostKey("user3", -1l),
        new PostKey("user7", System.currentTimeMillis()),
        -123124l,
        123,
        "hahaha",
        (short) 15,
        new ByteArrayKey(new byte[] {1, 2, 3})
     };

     for (Object orgKey: sourceData) {
        Reporter.log("testing " + orgKey.getClass().getName() + System.lineSeparator());
        assert mapper.isSupportedType(orgKey.getClass());
        String dbKey = mapper.getStringMapping(orgKey);
        if (! orgKey.equals(mapper.getKeyMapping(dbKey))) {
           throw new TestException("mismatch for " + orgKey.getClass().getName() + ":" +
                 "\n org value: " + orgKey.toString() +
                 "\n derived value: " + mapper.getKeyMapping(dbKey).toString());
        }
     }
  }
  
  @Test(enabled = false)
  public void fail() {
     assert false;
  }
}