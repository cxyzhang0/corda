package net.corda.core.internal

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.DeleteForDJVM
import net.corda.core.contracts.TransactionResolutionException
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.node.ServiceHub
import net.corda.core.node.StatesToRecord
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.ContractUpgradeWireTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.exactAdd
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.min

// TODO: This code is currently unit tested by TwoPartyTradeFlowTests, it should have its own tests.
/**
 * Resolves transactions for the specified [txHashes] along with their full history (dependency graph) from [otherSide].
 * Each retrieved transaction is validated and inserted into the local transaction storage.
 */
@DeleteForDJVM
class ResolveTransactionsFlow(txHashesArg: Set<SecureHash>,
                              private val otherSide: FlowSession,
                              private val statesToRecord: StatesToRecord = StatesToRecord.NONE) : FlowLogic<Unit>() {

    // Need it ordered in terms of iteration. Needs to be a variable for the check-pointing logic to work.
    private val txHashes = txHashesArg.toList()
    /** Transaction to fetch attachments for. */
    private var signedTransaction: SignedTransaction? = null

    /**
     * Resolves and validates the dependencies of the specified [SignedTransaction]. Fetches the attachments, but does
     * *not* validate or store the [SignedTransaction] itself.
     *
     * @return a list of verified [SignedTransaction] objects, in a depth-first order.
     */
    constructor(signedTransaction: SignedTransaction, otherSide: FlowSession) : this(dependencyIDs(signedTransaction), otherSide) {
        this.signedTransaction = signedTransaction
    }

    constructor(signedTransaction: SignedTransaction, otherSide: FlowSession, statesToRecord: StatesToRecord) : this(dependencyIDs(signedTransaction), otherSide, statesToRecord) {
        this.signedTransaction = signedTransaction
    }

    @DeleteForDJVM
    companion object {
        private fun dependencyIDs(stx: SignedTransaction) = stx.inputs.map { it.txhash }.toSet() + stx.references.map { it.txhash }.toSet()

        private const val RESOLUTION_PAGE_SIZE = 100

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
    
    class ExcessivelyLargeTransactionGraph : FlowException()

    // TODO: Figure out a more appropriate DOS limit here, 5000 is simply a very bad guess.
    /** The maximum number of transactions this flow will try to download before bailing out. */
    var transactionCountLimit = 5000
        set(value) {
            require(value > 0) { "$value is not a valid count limit" }
            field = value
        }

    @Suspendable
    @Throws(FetchDataFlow.HashNotFound::class, FetchDataFlow.IllegalTransactionRequest::class)
    override fun call() {
        val counterpartyPlatformVersion = serviceHub.networkMapCache.getNodeByLegalIdentity(otherSide.counterparty)?.platformVersion
                ?: throw FlowException("Couldn't retrieve party's ${otherSide.counterparty} platform version from NetworkMapCache")
        val newTxns = ArrayList<SignedTransaction>(txHashes.size)
        // Start fetching data.
        for (pageNumber in 0..(txHashes.size - 1) / RESOLUTION_PAGE_SIZE) {
            val page = page(pageNumber, RESOLUTION_PAGE_SIZE)

            newTxns += downloadDependencies(page)
            val txsWithMissingAttachments = if (pageNumber == 0) signedTransaction?.let { newTxns + it }
                    ?: newTxns else newTxns
            fetchMissingAttachments(txsWithMissingAttachments)
            // Fetch missing parameters flow was added in version 4. This check is needed so we don't end up with node V4 sending parameters
            // request to node V3 that doesn't know about this protocol.
            if (counterpartyPlatformVersion >= 4) {
                fetchMissingParameters(txsWithMissingAttachments)
            }
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
    }

    private fun page(pageNumber: Int, pageSize: Int): Set<SecureHash> {
        val offset = pageNumber * pageSize
        val limit = min(offset + pageSize, txHashes.size)
        // call toSet() is needed because sub-lists are not checkpoint-friendly.
        return txHashes.subList(offset, limit).toSet()
    }

    @Suspendable
    // TODO use paging here (we literally get the entire dependencies graph in memory)
    private fun downloadDependencies(depsToCheck: Set<SecureHash>): List<SignedTransaction> {
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
        nextRequests.addAll(depsToCheck)
        val resultQ = LinkedHashMap<SecureHash, SignedTransaction>()

        val limit = transactionCountLimit
        var limitCounter = 0
        while (nextRequests.isNotEmpty()) {
            // Don't re-download the same tx when we haven't verified it yet but it's referenced multiple times in the
            // graph we're traversing.
            val notAlreadyFetched: Set<SecureHash> = nextRequests - resultQ.keys
            nextRequests.clear()

            if (notAlreadyFetched.isEmpty())   // Done early.
                break

            // Request the standalone transaction data (which may refer to things we don't yet have).
            // TODO use paging here
            val downloads: List<SignedTransaction> = subFlow(FetchTransactionsFlow(notAlreadyFetched, otherSide)).downloaded

            for (stx in downloads)
                check(resultQ.putIfAbsent(stx.id, stx) == null)   // Assert checks the filter at the start.

            // Add all input states and reference input states to the work queue.
            val inputHashes = downloads.flatMap { it.inputs + it.references }.map { it.txhash }

            nextRequests.addAll(inputHashes)

            limitCounter = limitCounter exactAdd nextRequests.size
            if (limitCounter > limit)
                throw ExcessivelyLargeTransactionGraph()
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
        if (missingAttachments.isNotEmpty())
            subFlow(FetchAttachmentsFlow(missingAttachments.toSet(), otherSide))
    }

    // TODO This can also be done in parallel. See comment to [fetchMissingAttachments] above.
    @Suspendable
    private fun fetchMissingParameters(downloads: List<SignedTransaction>) {
        val parameters = downloads.mapNotNull { it.networkParametersHash }
        val missingParameters = parameters.filter { !(serviceHub.networkParametersService as NetworkParametersStorage).hasParameters(it) }
        if (missingParameters.isNotEmpty())
            subFlow(FetchNetworkParametersFlow(missingParameters.toSet(), otherSide))
    }
}
