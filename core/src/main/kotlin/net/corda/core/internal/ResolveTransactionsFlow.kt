package net.corda.core.internal

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.DeleteForDJVM
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.ContractUpgradeWireTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.exactAdd
import java.util.*
import kotlin.collections.LinkedHashSet

// TODO: This code is currently unit tested by TwoPartyTradeFlowTests, it should have its own tests.
/**
 * Resolves transactions for the specified [txHashes] along with their full history (dependency graph) from [otherSide].
 * Each retrieved transaction is validated and inserted into the local transaction storage.
 */
@DeleteForDJVM
class ResolveTransactionsFlow private constructor(
        private val initialTx: SignedTransaction?,
        private val txHashes: Set<SecureHash>,
        private val otherSide: FlowSession,
        private val statesToRecord: StatesToRecord
) : FlowLogic<Unit>() {

    constructor(txHashes: Set<SecureHash>, otherSide: FlowSession, statesToRecord: StatesToRecord = StatesToRecord.NONE)
            : this(null, txHashes, otherSide, statesToRecord)

    /**
     * Resolves and validates the dependencies of the specified [SignedTransaction]. Fetches the attachments, but does
     * *not* validate or store the [SignedTransaction] itself.
     *
     * @return a list of verified [SignedTransaction] objects, in a depth-first order.
     */
    constructor(initialTransaction: SignedTransaction, otherSide: FlowSession, statesToRecord: StatesToRecord = StatesToRecord.NONE)
            : this(initialTransaction, initialTransaction.dependencies, otherSide, statesToRecord)

    @DeleteForDJVM
    companion object {
        private val SignedTransaction.dependencies: Set<SecureHash>
            get() = (inputs.asSequence() + references.asSequence()).map { it.txhash }.toSet()

        /** Topologically sorts the given transactions such that dependencies are listed before dependers. */
        @JvmStatic
        fun topologicalSort(transactions: Collection<SignedTransaction>): List<SignedTransaction> {
            val sort = TopologicalSort()
            for (tx in transactions) {
                sort.add(tx)
            }
            return sort.complete()
        }
    }
    
    @Suspendable
    override fun call() {
        val start = System.currentTimeMillis()
        val counterpartyPlatformVersion = serviceHub.networkMapCache.getNodeByLegalIdentity(otherSide.counterparty)?.platformVersion
                ?: throw FlowException("Couldn't retrieve party's ${otherSide.counterparty} platform version from NetworkMapCache")

        val newTxns = downloadDependencies()
        val newTxnsPlusInitial = initialTx?.let { newTxns + it } ?: newTxns
        fetchMissingAttachments(newTxnsPlusInitial)
        // Fetch missing parameters flow was added in version 4. This check is needed so we don't end up with node V4 sending parameters
        // request to node V3 that doesn't know about this protocol.
        if (counterpartyPlatformVersion >= 4) {
            fetchMissingParameters(newTxnsPlusInitial)
        }
        otherSide.send(FetchDataFlow.Request.End)
        // Finish fetching data.

        val result = topologicalSort(newTxns)
        // If transaction resolution is performed for a transaction where some states are relevant, then those should be
        // recorded if this has not already occurred.
        val usedStatesToRecord = if (statesToRecord == StatesToRecord.NONE) StatesToRecord.ONLY_RELEVANT else statesToRecord
        result.forEach {
            // For each transaction, verify it and insert it into the database. As we are iterating over them in a
            // depth-first order, we should not encounter any verification failures due to missing data. If we fail
            // half way through, it's no big deal, although it might result in us attempting to re-download data
            // redundantly next time we attempt verification.
            it.verify(serviceHub)
            serviceHub.recordTransactions(usedStatesToRecord, listOf(it))
        }
        val end = System.currentTimeMillis()
        logger.info("Resolving ${result.size} backchain from ${otherSide.destination} took ${(end-start)/1000}secs")
    }

    @Suspendable
    private fun downloadDependencies(): List<SignedTransaction> {
        // Maintain a work queue of all hashes to load/download, initialised with our starting set. Then do a breadth
        // first traversal across the dependency graph.
        //
        // TODO: This approach has two problems. Analyze and resolve them:
        //
        // (1) This flow leaks private data. If you download a transaction and then do NOT request a
        // dependency, it means you already have it, which in turn means you must have been involved with it before
        // somehow, either in the tx itself or in any following spend of it. If there were no following spends, then
        // your peer knows for sure that you were involved ... this is bad! The only obvious ways to fix this are
        // something like onion routing of requests, secure hardware, or both.
        //
        // (2) If the identity service changes the assumed identity of one of the public keys, it's possible
        // that the "tx in db is valid" invariant is violated if one of the contracts checks the identity! Should
        // the db contain the identities that were resolved when the transaction was first checked, or should we
        // accept this kind of change is possible? Most likely solution is for identity data to be an attachment.

        val nextRequests = LinkedHashSet<SecureHash>()   // Keep things unique but ordered, for unit test stability.
        nextRequests.addAll(txHashes)
        val resultQ = LinkedHashMap<SecureHash, SignedTransaction>()

        var limitCounter = 0
        while (nextRequests.isNotEmpty()) {
            // Don't re-download the same tx when we haven't verified it yet but it's referenced multiple times in the
            // graph we're traversing.
            val notAlreadyFetched: Set<SecureHash> = nextRequests - resultQ.keys
            nextRequests.clear()

            if (notAlreadyFetched.isEmpty()) {
                // Done early.
                break
            }

            // Request the standalone transaction data (which may refer to things we don't yet have).

            val downloads: List<SignedTransaction> = subFlow(FetchTransactionsFlow(notAlreadyFetched, otherSide)).downloaded

            for (stx in downloads) {
                check(resultQ.putIfAbsent(stx.id, stx) == null)   // Assert checks the filter at the start.
                // Add all input states and reference input states to the work queue.
                nextRequests.addAll(stx.dependencies)
            }

            limitCounter = limitCounter exactAdd nextRequests.size
            logger.info("So far backchain to resolve is $limitCounter")
        }
        return resultQ.values.toList()
    }

    /**
     * Returns a list of all the dependencies of the given transactions, deepest first i.e. the last downloaded comes
     * first in the returned list and thus doesn't have any unverified dependencies.
     */
    // TODO: This could be done in parallel with other fetches for extra speed.
    @Suspendable
    private fun fetchMissingAttachments(downloads: List<SignedTransaction>) {
        val attachments = downloads.map(SignedTransaction::coreTransaction).flatMap { tx ->
            when (tx) {
                is WireTransaction -> tx.attachments
                is ContractUpgradeWireTransaction -> listOf(tx.legacyContractAttachmentId, tx.upgradedContractAttachmentId)
                else -> emptyList()
            }
        }
        val missingAttachments = attachments.filter { serviceHub.attachments.openAttachment(it) == null }
        if (missingAttachments.isNotEmpty()) {
            subFlow(FetchAttachmentsFlow(missingAttachments.toSet(), otherSide))
        }
    }

    // TODO This can also be done in parallel. See comment to [fetchMissingAttachments] above.
    @Suspendable
    private fun fetchMissingParameters(downloads: List<SignedTransaction>) {
        val networkParametersStorage = serviceHub.networkParametersService as NetworkParametersStorage
        val missingParameters = downloads.mapNotNull { it.networkParametersHash }.filterNot(networkParametersStorage::hasParameters)
        if (missingParameters.isNotEmpty()) {
            subFlow(FetchNetworkParametersFlow(missingParameters.toSet(), otherSide))
        }
    }
}
