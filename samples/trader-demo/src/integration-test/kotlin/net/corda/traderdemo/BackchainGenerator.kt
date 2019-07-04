package net.corda.traderdemo

import net.corda.core.messaging.startFlow
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.DOLLARS
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.flows.CashPaymentFlow
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.internal.FINANCE_CORDAPPS
import java.time.Instant

fun main(args: Array<String>) {
    val chainLength = args[0].toInt()
    driver(DriverParameters(inMemoryDB = false, notarySpecs = listOf(NotarySpec(DUMMY_NOTARY_NAME, false)))) {
        val (alice, bob) = listOf(ALICE_NAME, BOB_NAME)
                .map { startNode(NodeParameters(providedName = it, additionalCordapps = FINANCE_CORDAPPS)) }
                .map { it.getOrThrow() }
        alice.rpc.startFlow(::CashIssueFlow, 1.DOLLARS, OpaqueBytes.of(1), defaultNotaryIdentity).returnValue.getOrThrow()
        repeat(chainLength / 2) {
            alice.rpc.startFlow(::CashPaymentFlow, 1.DOLLARS, bob.nodeInfo.singleIdentity(), false).returnValue.getOrThrow()
            bob.rpc.startFlow(::CashPaymentFlow, 1.DOLLARS, alice.nodeInfo.singleIdentity(), false).returnValue.getOrThrow()
            val current = (it + 1) * 2
            if (current % 100 == 0) {
                println("${Instant.now()} $current")
            }
        }
    }
}