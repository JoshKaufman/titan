package com.thinkaurelius.titan.diskstorage;

import com.thinkaurelius.titan.diskstorage.idmanagement.ConsistentKeyIDManager;
import com.thinkaurelius.titan.diskstorage.idmanagement.TransactionalIDManager;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.*;
import com.thinkaurelius.titan.diskstorage.locking.LockingException;
import com.thinkaurelius.titan.diskstorage.locking.PermanentLockingException;
import com.thinkaurelius.titan.diskstorage.locking.consistentkey.ConsistentKeyLockConfiguration;
import com.thinkaurelius.titan.diskstorage.locking.consistentkey.ConsistentKeyLockStore;
import com.thinkaurelius.titan.diskstorage.locking.consistentkey.ConsistentKeyLockTransaction;
import com.thinkaurelius.titan.diskstorage.locking.consistentkey.LocalLockMediators;
import com.thinkaurelius.titan.diskstorage.locking.transactional.TransactionalLockStore;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;
import com.thinkaurelius.titan.graphdb.database.idassigner.IDBlockSizer;
import org.apache.commons.configuration.BaseConfiguration;
import org.apache.commons.configuration.Configuration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeyColumnValueStore.NO_DELETIONS;

public abstract class LockKeyColumnValueStoreTest {

    public final int concurrency = 8;
    public final int numTx = 2;
    public KeyColumnValueStoreManager[] manager;
    public StoreTransaction[][] tx;
    public KeyColumnValueStore[] store;

    public IDAuthority[] idAuthorities;

    public static final String dbName = "test";

    protected final byte[][] rid1 = new byte[][]{{'a'}, {'b'}};
    protected static final long EXPIRE_MS = 1000;

    private ByteBuffer k, c1, c2, v1, v2;
    
    private static final Logger log =
    		LoggerFactory.getLogger(LockKeyColumnValueStoreTest.class);

    @Before
    public void setUp() throws Exception {
        for (int i = 0; i < concurrency; i++)
            openStorageManager(i).clearStorage();
        
        open();
        k = strToByteBuffer("key");
        c1 = strToByteBuffer("col1");
        c2 = strToByteBuffer("col2");
        v1 = strToByteBuffer("val1");
        v2 = strToByteBuffer("val2");
    }

    private ByteBuffer strToByteBuffer(String s) throws UnsupportedEncodingException {
        byte[] raw = s.getBytes("UTF-8");
        ByteBuffer b = ByteBuffer.allocate(raw.length);
        b.put(raw).rewind();
        return b;
    }

    public abstract KeyColumnValueStoreManager openStorageManager(int id) throws StorageException;

    public void open() throws StorageException {
        manager = new KeyColumnValueStoreManager[concurrency];
        tx = new StoreTransaction[concurrency][numTx];
        store = new KeyColumnValueStore[concurrency];
        idAuthorities = new IDAuthority[concurrency];

        for (int i = 0; i < concurrency; i++) {
            manager[i] = openStorageManager(i);
            StoreFeatures storeFeatures = manager[i].getFeatures();
            store[i] = manager[i].openDatabase(dbName);
            for (int j = 0; j < numTx; j++) {
            	tx[i][j] = manager[i].beginTransaction(ConsistencyLevel.DEFAULT);
            	log.debug("Began transaction of class {}", tx[i][j].getClass().getCanonicalName());
            }

            Configuration sc = new BaseConfiguration();
            sc.addProperty(ConsistentKeyLockStore.LOCAL_LOCK_MEDIATOR_PREFIX_KEY, "store" + i);
            sc.addProperty(GraphDatabaseConfiguration.INSTANCE_RID_SHORT_KEY, (short) i);
            sc.addProperty(GraphDatabaseConfiguration.LOCK_EXPIRE_MS, EXPIRE_MS);
            sc.addProperty(GraphDatabaseConfiguration.IDAUTHORITY_RETRY_COUNT_KEY,50);
            sc.addProperty(GraphDatabaseConfiguration.IDAUTHORITY_WAIT_MS_KEY,100);

            if (!storeFeatures.supportsLocking()) {
                if (storeFeatures.supportsTransactions()) {
                    store[i] = new TransactionalLockStore(store[i]);
                } else if (storeFeatures.supportsConsistentKeyOperations()) {
                    ConsistentKeyLockConfiguration lockConfiguration = new ConsistentKeyLockConfiguration(sc, "store" + i);
                    store[i] = new ConsistentKeyLockStore(store[i], manager[i].openDatabase(dbName + "_lock_"), lockConfiguration);
                    for (int j = 0; j < numTx; j++)
                        tx[i][j] = new ConsistentKeyLockTransaction(tx[i][j], manager[i].beginTransaction(ConsistencyLevel.KEY_CONSISTENT));
                } else throw new IllegalArgumentException("Store needs to support some form of locking");
            }

            KeyColumnValueStore idStore = manager[i].openDatabase("ids");
            if (storeFeatures.supportsTransactions())
                idAuthorities[i] = new TransactionalIDManager(idStore, manager[i], sc);
            else if (storeFeatures.supportsConsistentKeyOperations())
                idAuthorities[i] = new ConsistentKeyIDManager(idStore, manager[i], sc);
            else throw new IllegalArgumentException("Cannot open id store");
        }
    }

    public StoreTransaction newTransaction(KeyColumnValueStoreManager manager) throws StorageException {
        StoreTransaction transaction = manager.beginTransaction(ConsistencyLevel.DEFAULT);
        if (!manager.getFeatures().supportsLocking() && manager.getFeatures().supportsConsistentKeyOperations()) {
            transaction = new ConsistentKeyLockTransaction(transaction, manager.beginTransaction(ConsistencyLevel.KEY_CONSISTENT));
        }
        return transaction;
    }

    @After
    public void tearDown() throws Exception {
        close();
    }

    public void close() throws StorageException {
        for (int i = 0; i < concurrency; i++) {
            store[i].close();
            idAuthorities[i].close();

            for (int j = 0; j < numTx; j++) {
            	log.debug("Committing tx[{}][{}] = {}", new Object[] {i, j, tx[i][j]});
                if (tx[i][j] != null) tx[i][j].commit();
            }

            manager[i].close();
        }
        LocalLockMediators.INSTANCE.clear();
    }

    @Test
    public void singleLockAndUnlock() throws StorageException {
        store[0].acquireLock(k, c1, null, tx[0][0]);
        store[0].mutate(k, Arrays.asList(SimpleEntry.of(c1, v1)), NO_DELETIONS, tx[0][0]);
        tx[0][0].commit();

        tx[0][0] = newTransaction(manager[0]);
        Assert.assertEquals(v1, KCVSUtil.get(store[0],k, c1, tx[0][0]));
    }

    @Test
    public void transactionMayReenterLock() throws StorageException {
        store[0].acquireLock(k, c1, null, tx[0][0]);
        store[0].acquireLock(k, c1, null, tx[0][0]);
        store[0].acquireLock(k, c1, null, tx[0][0]);
        store[0].mutate(k, Arrays.asList(SimpleEntry.of(c1, v1)), NO_DELETIONS, tx[0][0]);
        tx[0][0].commit();

        tx[0][0] = newTransaction(manager[0]);
        Assert.assertEquals(v1, KCVSUtil.get(store[0],k, c1, tx[0][0]));
    }

    @Test(expected = PermanentLockingException.class)
    public void expectedValueMismatchCausesMutateFailure() throws StorageException {
        store[0].acquireLock(k, c1, v1, tx[0][0]);
        store[0].mutate(k, Arrays.asList(SimpleEntry.of(c1, v1)), NO_DELETIONS, tx[0][0]);
    }

    @Test
    public void testLocalLockContention() throws StorageException {
        store[0].acquireLock(k, c1, null, tx[0][0]);

        try {
            store[0].acquireLock(k, c1, null, tx[0][1]);
            Assert.fail("Lock contention exception not thrown");
        } catch (StorageException e) {
            Assert.assertTrue(e instanceof LockingException);
        }

        try {
            store[0].acquireLock(k, c1, null, tx[0][1]);
            Assert.fail("Lock contention exception not thrown (2nd try)");
        } catch (StorageException e) {
            Assert.assertTrue(e instanceof LockingException);
        }
    }

    @Test
    public void testRemoteLockContention() throws InterruptedException, StorageException {
        // acquire lock on "host1"
        store[0].acquireLock(k, c1, null, tx[0][0]);

        Thread.sleep(50L);

        try {
            // acquire same lock on "host2"
            store[1].acquireLock(k, c1, null, tx[1][0]);
        } catch (StorageException e) {            /* Lock attempts between hosts with different LocalLockMediators,
             * such as tx[0][0] and tx[1][0] in this example, should
			 * not generate locking failures until one of them tries
			 * to issue a mutate or mutateMany call.  An exception
			 * thrown during the acquireLock call above suggests that
			 * the LocalLockMediators for these two transactions are
			 * not really distinct, which would be a severe and fundamental
			 * bug in this test.
			 */
            Assert.fail("Contention between remote transactions detected too soon");
        }

        Thread.sleep(50L);

        try {
            // This must fail since "host1" took the lock first
            store[1].mutate(k, Arrays.asList(SimpleEntry.of(c1, v2)), NO_DELETIONS, tx[1][0]);
            Assert.fail("Expected lock contention between remote transactions did not occur");
        } catch (StorageException e) {
            Assert.assertTrue(e instanceof LockingException);
        }

        // This should succeed
        store[0].mutate(k, Arrays.asList(SimpleEntry.of(c1, v1)), NO_DELETIONS, tx[0][0]);

        tx[0][0].commit();
        tx[0][0] = newTransaction(manager[0]);
        Assert.assertEquals(v1, KCVSUtil.get(store[0],k, c1, tx[0][0]));
    }

    @Test
    public void singleTransactionWithMultipleLocks() throws StorageException {
        tryWrites(store[0], manager[0], tx[0][0], store[0], tx[0][0]);
        /*
         * tryWrites commits transactions. set committed tx references to null
         * to prevent a second commit attempt in close().
         */
        tx[0][0] = null;
    }

    @Test
    public void twoLocalTransactionsWithIndependentLocks() throws StorageException {
        tryWrites(store[0], manager[0], tx[0][0], store[0], tx[0][1]);
        /*
         * tryWrites commits transactions. set committed tx references to null
         * to prevent a second commit attempt in close().
         */
        tx[0][0] = null;
        tx[0][1] = null;
    }

    @Test
    public void twoTransactionsWithIndependentLocks() throws StorageException {
        tryWrites(store[0], manager[0], tx[0][0], store[1], tx[1][0]);
        /*
         * tryWrites commits transactions. set committed tx references to null
         * to prevent a second commit attempt in close().
         */
        tx[0][0] = null;
        tx[1][0] = null;
    }

    @Test
    public void expiredLocalLockIsIgnored() throws StorageException, InterruptedException {
        tryLocks(store[0], tx[0][0], store[0], tx[0][1], true);
    }

    @Test
    public void expiredRemoteLockIsIgnored() throws StorageException, InterruptedException {
        tryLocks(store[0], tx[0][0], store[1], tx[1][0], false);
    }

    @Test
    public void repeatLockingDoesNotExtendExpiration() throws StorageException, InterruptedException {
		/*
		 * This test is intrinsically racy and unreliable. There's no guarantee
		 * that the thread scheduler will put our test thread back on a core in
		 * a timely fashion after our Thread.sleep() argument elapses.
		 * Theoretically, Thread.sleep could also receive spurious wakeups that
		 * alter the timing of the test.
		 */
        long start = System.currentTimeMillis();
        long gracePeriodMS = 50L;
        long loopDurationMS = (EXPIRE_MS - gracePeriodMS);
        long targetMS = start + loopDurationMS;
        int steps = 20;

        // Initial lock acquisition by tx[0][0]
        store[0].acquireLock(k, k, null, tx[0][0]);
        
        // Repeat lock acquistion until just before expiration
        for (int i = 0; i <= steps; i++) {
            if (targetMS <= System.currentTimeMillis()) {
                break;
            }
            store[0].acquireLock(k, k, null, tx[0][0]);
            Thread.sleep(loopDurationMS / steps);
        }
        
        // tx[0][0]'s lock is about to expire (or already has)
        Thread.sleep(gracePeriodMS * 2);
        // tx[0][0]'s lock has expired (barring spurious wakeup)
        
        try {
        	// Lock (k,k) with tx[0][1] now that tx[0][0]'s lock has expired
            store[0].acquireLock(k, k, null, tx[0][1]);
            // If acquireLock returns without throwing an Exception, we're OK
        } catch (StorageException e) {
            log.debug("Relocking exception follows", e);
        	Assert.fail("Relocking following expiration failed");
        }
    }

    private void tryWrites(KeyColumnValueStore store1, KeyColumnValueStoreManager checkmgr,
                           StoreTransaction tx1, KeyColumnValueStore store2,
                           StoreTransaction tx2) throws StorageException {
        Assert.assertNull(KCVSUtil.get(store1,k, c1, tx1));
        Assert.assertNull(KCVSUtil.get(store2,k, c2, tx2));

        store1.acquireLock(k, c1, null, tx1);
        store2.acquireLock(k, c2, null, tx2);

        store1.mutate(k, Arrays.asList(SimpleEntry.of(c1, v1)), NO_DELETIONS, tx1);
        store2.mutate(k, Arrays.asList(SimpleEntry.of(c2, v2)), NO_DELETIONS, tx2);

        tx1.commit();
        if (tx2 != tx1)
            tx2.commit();

        StoreTransaction checktx = newTransaction(checkmgr);
        Assert.assertEquals(v1, KCVSUtil.get(store1,k, c1, checktx));
        Assert.assertEquals(v2, KCVSUtil.get(store2,k, c2, checktx));
        checktx.commit();
    }

    private void tryLocks(KeyColumnValueStore s1,
                          StoreTransaction tx1, KeyColumnValueStore s2,
                          StoreTransaction tx2, boolean detectLocally) throws StorageException, InterruptedException {

        s1.acquireLock(k, k, null, tx1);

        // Require local lock contention, if requested by our caller
        // Remote lock contention is checked by separate cases
        if (detectLocally) {
            try {
                s2.acquireLock(k, k, null, tx2);
                Assert.fail("Expected lock contention between transactions did not occur");
            } catch (StorageException e) {
                Assert.assertTrue(e instanceof LockingException);
            }
        }

        // Let the original lock expire
        Thread.sleep(EXPIRE_MS + 100L);

        // This should succeed now that the original lock is expired
        s2.acquireLock(k, k, null, tx2);

        // Mutate to check for remote contention
        s2.mutate(k, Arrays.asList(SimpleEntry.of(c2, v2)), NO_DELETIONS, tx2);

    }

    @Test
    public void testSimpleIDAcquisition() throws StorageException {
        final int blockSize = 400;
        final IDBlockSizer blockSizer = new IDBlockSizer() {
            @Override
            public long getBlockSize(int partitionID) {
                return blockSize;
            }
        };
        idAuthorities[0].setIDBlockSizer(blockSizer);
        long[] block = idAuthorities[0].getIDBlock(0);
        Assert.assertEquals(1,block[0]);
        Assert.assertEquals(block[1], block[0] + blockSize);
        block = idAuthorities[0].getIDBlock(0);
        Assert.assertEquals(1+blockSize,block[0]);
        Assert.assertEquals(block[1], block[0] + blockSize);
    }

    @Test
    public void testMultiIDAcquisition() throws StorageException, InterruptedException {
        final int numPartitions = 4;
        final int numAcquisitionsPerThreadPartition = 300;
        final int blockSize = 250;
        final IDBlockSizer blockSizer = new IDBlockSizer() {
            @Override
            public long getBlockSize(int partitionID) {
                return blockSize;
            }
        };
        for (int i = 0; i < concurrency; i++) idAuthorities[i].setIDBlockSizer(blockSizer);
        final List<List<Long>> ids = new ArrayList<List<Long>>(numPartitions);
        for (int i = 0; i < numPartitions; i++) {
            ids.add(Collections.synchronizedList(new ArrayList<Long>(numAcquisitionsPerThreadPartition * concurrency)));
        }

        Thread[] threads = new Thread[concurrency];
        for (int i = 0; i < concurrency; i++) {
            final IDAuthority idAuthority = idAuthorities[i];
            threads[i] = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        for (int j = 0; j < numAcquisitionsPerThreadPartition; j++) {
                            for (int p = 0; p < numPartitions; p++) {
                                long nextId = idAuthority.peekNextID(p);
                                long[] block = idAuthority.getIDBlock(p);
                                Assert.assertTrue(nextId <= block[0]);
                                Assert.assertEquals(block[0] + blockSize, block[1]);
                                Assert.assertFalse(ids.get(p).contains(block[0]));
                                ids.get(p).add(block[0]);
                            }
                        }
                    } catch (StorageException e) {
                        log.error("Unexpected exception when testing multi-thread ID acqusition", e);
                        throw new RuntimeException(e);
                    }
                }
            });
            threads[i].start();
        }

        for (int i = 0; i < concurrency; i++) {
            threads[i].join();
        }

        for (int i = 0; i < numPartitions; i++) {
            List<Long> list = ids.get(i);
            Assert.assertEquals(numAcquisitionsPerThreadPartition * concurrency, list.size());
            Collections.sort(list);
            int pos = 0;
            int id = 1;
            while (pos < list.size()) {
                Assert.assertEquals(id, list.get(pos).longValue());
                id += blockSize;
                pos++;
            }
        }
    }


    @Test
    public void testLocalPartitionAcquisition() throws StorageException {
        for (int c = 0; c < concurrency; c++) {
            if (manager[c].getFeatures().hasLocalKeyPartition()) {
                try {
                    ByteBuffer[] partition = idAuthorities[c].getLocalIDPartition();
                    Assert.assertEquals(partition[0].remaining(), partition[1].remaining());
                    for (int i = 0; i < 2; i++) {
                        Assert.assertTrue(partition[i].remaining() >= 4);
                    }
                } catch (UnsupportedOperationException e) {
                    Assert.fail();
                }
            }
        }
    }

}