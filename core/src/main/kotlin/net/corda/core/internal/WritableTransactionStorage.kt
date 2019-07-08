package net.corda.core.internal

import net.corda.core.node.services.TransactionStorage
import net.corda.core.transactions.SignedTransaction

/**
 * Thread-safe storage of transactions.
 */
interface WritableTransactionStorage : TransactionStorage {
    /**
     * Add a new transaction to the store. If the store already has a transaction with the same id it will be
     * overwritten.
     * @param transaction The transaction to be recorded.
     * @return true if the transaction was recorded successfully, false if it was already recorded.
     */
    // TODO: Throw an exception if trying to add a transaction with fewer signatures than an existing entry.
    fun addTransaction(transaction: SignedTransaction): Boolean
}