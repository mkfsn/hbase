/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.security.Key;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.HBaseTestingUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.io.crypto.Encryption;
import org.apache.hadoop.hbase.io.crypto.MockAesKeyProvider;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.testclassification.RegionServerTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category({ RegionServerTests.class, MediumTests.class })
public class TestEncryptionRandomKeying {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestEncryptionRandomKeying.class);

  private static final HBaseTestingUtil TEST_UTIL = new HBaseTestingUtil();
  private static Configuration conf = TEST_UTIL.getConfiguration();
  private static TableDescriptorBuilder tdb;

  private static List<Path> findStorefilePaths(TableName tableName) throws Exception {
    List<Path> paths = new ArrayList<>();
    for (Region region : TEST_UTIL.getRSForFirstRegionInTable(tableName)
      .getRegions(tdb.build().getTableName())) {
      for (HStore store : ((HRegion) region).getStores()) {
        for (HStoreFile storefile : store.getStorefiles()) {
          paths.add(storefile.getPath());
        }
      }
    }
    return paths;
  }

  private static byte[] extractHFileKey(Path path) throws Exception {
    HFile.Reader reader =
      HFile.createReader(TEST_UTIL.getTestFileSystem(), path, new CacheConfig(conf), true, conf);
    try {
      Encryption.Context cryptoContext = reader.getFileContext().getEncryptionContext();
      assertNotNull("Reader has a null crypto context", cryptoContext);
      Key key = cryptoContext.getKey();
      if (key == null) {
        return null;
      }
      return key.getEncoded();
    } finally {
      reader.close();
    }
  }

  @BeforeClass
  public static void setUp() throws Exception {
    conf.setInt("hfile.format.version", 3);
    conf.set(HConstants.CRYPTO_KEYPROVIDER_CONF_KEY, MockAesKeyProvider.class.getName());
    conf.set(HConstants.CRYPTO_MASTERKEY_NAME_CONF_KEY, "hbase");

    // Create the table schema
    // Specify an encryption algorithm without a key
    tdb =
      TableDescriptorBuilder.newBuilder(TableName.valueOf("default", "TestEncryptionRandomKeying"));
    ColumnFamilyDescriptorBuilder columnFamilyDescriptorBuilder =
      ColumnFamilyDescriptorBuilder.newBuilder(Bytes.toBytes("cf"));
    String algorithm = conf.get(HConstants.CRYPTO_KEY_ALGORITHM_CONF_KEY, HConstants.CIPHER_AES);
    columnFamilyDescriptorBuilder.setEncryptionType(algorithm);
    tdb.setColumnFamily(columnFamilyDescriptorBuilder.build());

    // Start the minicluster
    TEST_UTIL.startMiniCluster(1);

    // Create the test table
    TEST_UTIL.getAdmin().createTable(tdb.build());
    TEST_UTIL.waitTableAvailable(tdb.build().getTableName(), 5000);

    // Create a store file
    Table table = TEST_UTIL.getConnection().getTable(tdb.build().getTableName());
    try {
      table.put(
        new Put(Bytes.toBytes("testrow")).addColumn(columnFamilyDescriptorBuilder.build().getName(),
          Bytes.toBytes("q"), Bytes.toBytes("value")));
    } finally {
      table.close();
    }
    TEST_UTIL.getAdmin().flush(tdb.build().getTableName());
  }

  @AfterClass
  public static void tearDown() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Test
  public void testRandomKeying() throws Exception {
    // Verify we have store file(s) with a random key
    final List<Path> initialPaths = findStorefilePaths(tdb.build().getTableName());
    assertTrue(initialPaths.size() > 0);
    for (Path path : initialPaths) {
      assertNotNull("Store file " + path + " is not encrypted", extractHFileKey(path));
    }
  }

}
