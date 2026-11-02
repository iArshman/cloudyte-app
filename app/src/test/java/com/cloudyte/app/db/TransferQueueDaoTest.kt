package com.cloudyte.app.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TransferQueueDaoTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = database.close()

    @Test fun leaseClaimIsExclusiveAndReleasedWorkCanBeClaimedAgain() = runBlocking {
        database.transferDao().insert(LocalTransfer(id = "t1", accountId = "a1", fileName = "sample.bin"))
        val queue = database.transferQueueDao()
        val first = queue.acquireNextRunnableLease("worker-1", 100L, 10_000L)
        assertNotNull(first)
        assertEquals("worker-1", first?.leaseOwner)
        assertNull(queue.acquireNextRunnableLease("worker-2", 101L, 10_001L))

        assertEquals(1, queue.returnToQueue("t1", "worker-1", 102L))
        val reclaimed = queue.acquireNextRunnableLease("worker-2", 103L, 10_003L)
        assertNotNull(reclaimed)
        assertEquals("worker-2", reclaimed?.leaseOwner)
    }

    @Test fun expiredLeaseIsReclaimedAfterProcessDeathAndFencesTheOldOwner() = runBlocking {
        database.transferDao().insert(LocalTransfer(id = "crashed", accountId = "a1", fileName = "large.bin"))
        val queue = database.transferQueueDao()

        val first = queue.acquireNextRunnableLease("process-1:worker-a", 100L, 200L)
        assertNotNull(first)
        assertEquals(200L, queue.nextLeaseExpiry(100L))

        // Simulate a process that disappeared without releasing its durable lease.
        assertEquals(1, queue.recoverExpiredLeases(200L))
        val recovered = queue.acquireNextRunnableLease("process-2:worker-b", 201L, 401L)
        assertNotNull(recovered)
        assertEquals("process-2:worker-b", recovered?.leaseOwner)

        assertEquals(0, queue.persistProgress("crashed", "process-1:worker-a", 5L, 20L, 1L, null, 202L))
        assertEquals(1, queue.persistProgress("crashed", "process-2:worker-b", 5L, 20L, 1L, null, 202L))
        assertEquals(401L, queue.nextLeaseExpiry(202L))
    }
}
