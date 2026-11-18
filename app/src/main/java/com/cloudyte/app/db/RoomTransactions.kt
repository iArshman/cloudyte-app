package com.cloudyte.app.db

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Runs [block] inside an immediate SQLite write transaction (`BEGIN IMMEDIATE`) so concurrent
 * transfer/sync workers acquire the writer lock upfront instead of hitting `SQLITE_BUSY`
 * deadlocks when upgrading a deferred read transaction.
 */
suspend fun <R> RoomDatabase.inImmediateTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor ->
        transactor.immediateTransaction {
            block()
        }
    }
