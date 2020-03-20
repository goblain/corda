package net.corda.node.services.statemachine

import co.paralleluniverse.fibers.Suspendable
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.seconds
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.CHARLIE_NAME
import net.corda.testing.core.singleIdentity
import org.junit.Test
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("MaxLineLength") // Byteman rules cannot be easily wrapped
class StatemachineKillFlowErrorHandlingTest : StatemachineErrorHandlingTest() {

    /**
     * Triggers `killFlow` while the flow is suspended causing a [InterruptedException] to be thrown and passed through the hospital.
     *
     * The flow terminates and is not retried.
     *
     * No pass through the hospital is recorded. As the flow is marked as `isRemoved`.
     */
    @Test(timeout=300_000)
	fun `error during transition due to an InterruptedException (killFlow) will terminate the flow`() {
        startDriver {
            val alice = createBytemanNode(ALICE_NAME)

            val rules = """
                RULE Entering internal error staff member
                CLASS ${StaffedFlowHospital.TransitionErrorGeneralPractitioner::class.java.name}
                METHOD consult
                AT ENTRY
                IF true
                DO traceln("Reached internal transition error staff member")
                ENDRULE

                RULE Increment discharge counter
                CLASS ${StaffedFlowHospital.TransitionErrorGeneralPractitioner::class.java.name}
                METHOD consult
                AT READ DISCHARGE
                IF true
                DO traceln("Byteman test - discharging")
                ENDRULE
                
                RULE Increment observation counter
                CLASS ${StaffedFlowHospital.TransitionErrorGeneralPractitioner::class.java.name}
                METHOD consult
                AT READ OVERNIGHT_OBSERVATION
                IF true
                DO traceln("Byteman test - overnight observation")
                ENDRULE
                
                RULE Increment terminal counter
                CLASS ${StaffedFlowHospital.TransitionErrorGeneralPractitioner::class.java.name}
                METHOD consult
                AT READ TERMINAL
                IF true
                DO traceln("Byteman test - terminal")
                ENDRULE
            """.trimIndent()

            submitBytemanRules(rules)

            val aliceClient =
                CordaRPCClient(alice.rpcAddress).start(rpcUser.username, rpcUser.password).proxy

            val flow = aliceClient.startTrackedFlow(StatemachineKillFlowErrorHandlingTest::SleepFlow)

            var flowKilled = false
            flow.progress.subscribe {
                if (it == SleepFlow.STARTED.label) {
                    Thread.sleep(5000)
                    flowKilled = aliceClient.killFlow(flow.id)
                }
            }

            assertFailsWith<TimeoutException> { flow.returnValue.getOrThrow(20.seconds) }

            val output = getBytemanOutput(alice)

            assertTrue(flowKilled)
            // Check the stdout for the lines generated by byteman
            assertEquals(0, output.filter { it.contains("Byteman test - discharging") }.size)
            assertEquals(0, output.filter { it.contains("Byteman test - overnight observation") }.size)
            val numberOfTerminalDiagnoses = output.filter { it.contains("Byteman test - terminal") }.size
            assertEquals(1, numberOfTerminalDiagnoses)
            val (discharge, observation) = aliceClient.startFlow(StatemachineErrorHandlingTest::GetHospitalCountersFlow).returnValue.get()
            assertEquals(0, discharge)
            assertEquals(0, observation)
            assertEquals(0, aliceClient.stateMachinesSnapshot().size)
            // 1 for GetNumberOfCheckpointsFlow
            assertEquals(1, aliceClient.startFlow(StatemachineErrorHandlingTest::GetNumberOfUncompletedCheckpointsFlow).returnValue.get())
        }
    }

    /**
     * Triggers `killFlow` during user application code.
     *
     * The user application code is mimicked by a [Thread.sleep] which is importantly not placed inside the [Suspendable]
     * call function. Placing it inside a [Suspendable] function causes quasar to behave unexpectedly.
     *
     * Although the call to kill the flow is made during user application code. It will not be removed / stop processing
     * until the next suspension point is reached within the flow.
     *
     * The flow terminates and is not retried.
     *
     * No pass through the hospital is recorded. As the flow is marked as `isRemoved`.
     */
    @Test(timeout=300_000)
	fun `flow killed during user code execution stops and removes the flow correctly`() {
        startDriver {
            val alice = createBytemanNode(ALICE_NAME)

            val rules = """
                RULE Entering internal error staff member
                CLASS ${StaffedFlowHospital.TransitionErrorGeneralPractitioner::class.java.name}
                METHOD consult
                AT ENTRY
                IF true
                DO traceln("Reached internal transition error staff member")
                ENDRULE

                RULE Increment discharge counter
                CLASS ${StaffedFlowHospital.TransitionErrorGeneralPractitioner::class.java.name}
                METHOD consult
                AT READ DISCHARGE
                IF true
                DO traceln("Byteman test - discharging")
                ENDRULE
                
                RULE Increment observation counter
                CLASS ${StaffedFlowHospital.TransitionErrorGeneralPractitioner::class.java.name}
                METHOD consult
                AT READ OVERNIGHT_OBSERVATION
                IF true
                DO traceln("Byteman test - overnight observation")
                ENDRULE
                
                RULE Increment terminal counter
                CLASS ${StaffedFlowHospital.TransitionErrorGeneralPractitioner::class.java.name}
                METHOD consult
                AT READ TERMINAL
                IF true
                DO traceln("Byteman test - terminal")
                ENDRULE
            """.trimIndent()

            submitBytemanRules(rules)

            val aliceClient =
                CordaRPCClient(alice.rpcAddress).start(rpcUser.username, rpcUser.password).proxy

            val flow = aliceClient.startTrackedFlow(StatemachineKillFlowErrorHandlingTest::ThreadSleepFlow)

            var flowKilled = false
            flow.progress.subscribe {
                if (it == ThreadSleepFlow.STARTED.label) {
                    Thread.sleep(5000)
                    flowKilled = aliceClient.killFlow(flow.id)
                }
            }

            assertFailsWith<TimeoutException> { flow.returnValue.getOrThrow(30.seconds) }

            val output = getBytemanOutput(alice)

            assertTrue(flowKilled)
            // Check the stdout for the lines generated by byteman
            assertEquals(0, output.filter { it.contains("Byteman test - discharging") }.size)
            assertEquals(0, output.filter { it.contains("Byteman test - overnight observation") }.size)
            val numberOfTerminalDiagnoses = output.filter { it.contains("Byteman test - terminal") }.size
            println(numberOfTerminalDiagnoses)
            assertEquals(0, numberOfTerminalDiagnoses)
            val (discharge, observation) = aliceClient.startFlow(StatemachineErrorHandlingTest::GetHospitalCountersFlow).returnValue.get()
            assertEquals(0, discharge)
            assertEquals(0, observation)
            assertEquals(0, aliceClient.stateMachinesSnapshot().size)
            // 1 for GetNumberOfCheckpointsFlow
            assertEquals(1, aliceClient.startFlow(StatemachineErrorHandlingTest::GetNumberOfUncompletedCheckpointsFlow).returnValue.get())
        }
    }

    /**
     * Triggers `killFlow` after the flow has already been sent to observation. The flow is not running at this point and
     * all that remains is its checkpoint in the database.
     *
     * The flow terminates and is not retried.
     *
     * Killing the flow does not lead to any passes through the hospital. All the recorded passes through the hospital are
     * from the original flow that was put in for observation.
     */
    @Test(timeout=300_000)
	fun `flow killed when it is in the flow hospital for observation is removed correctly`() {
        startDriver {
            val alice = createBytemanNode(ALICE_NAME)
            val charlie = createNode(CHARLIE_NAME)

            val rules = """
                RULE Create Counter
                CLASS ${ActionExecutorImpl::class.java.name}
                METHOD executeSendMultiple
                AT ENTRY
                IF createCounter("counter", $counter)
                DO traceln("Counter created")
                ENDRULE

                RULE Throw exception on executeSendMultiple action
                CLASS ${ActionExecutorImpl::class.java.name}
                METHOD executeSendMultiple
                AT ENTRY
                IF readCounter("counter") < 4
                DO incrementCounter("counter"); traceln("Throwing exception"); throw new java.lang.RuntimeException("die dammit die")
                ENDRULE
                
                RULE Entering internal error staff member
                CLASS ${StaffedFlowHospital.TransitionErrorGeneralPractitioner::class.java.name}
                METHOD consult
                AT ENTRY
                IF true
                DO traceln("Reached internal transition error staff member")
                ENDRULE

                RULE Increment discharge counter
                CLASS ${StaffedFlowHospital.TransitionErrorGeneralPractitioner::class.java.name}
                METHOD consult
                AT READ DISCHARGE
                IF true
                DO traceln("Byteman test - discharging")
                ENDRULE
                
                RULE Increment observation counter
                CLASS ${StaffedFlowHospital.TransitionErrorGeneralPractitioner::class.java.name}
                METHOD consult
                AT READ OVERNIGHT_OBSERVATION
                IF true
                DO traceln("Byteman test - overnight observation")
                ENDRULE
                
                RULE Increment terminal counter
                CLASS ${StaffedFlowHospital.TransitionErrorGeneralPractitioner::class.java.name}
                METHOD consult
                AT READ TERMINAL
                IF true
                DO traceln("Byteman test - terminal")
                ENDRULE
            """.trimIndent()

            submitBytemanRules(rules)

            val aliceClient =
                CordaRPCClient(alice.rpcAddress).start(rpcUser.username, rpcUser.password).proxy

            val flow = aliceClient.startFlow(StatemachineErrorHandlingTest::SendAMessageFlow, charlie.nodeInfo.singleIdentity())

            assertFailsWith<TimeoutException> { flow.returnValue.getOrThrow(20.seconds) }

            aliceClient.killFlow(flow.id)

            val output = getBytemanOutput(alice)

            // Check the stdout for the lines generated by byteman
            assertEquals(3, output.filter { it.contains("Byteman test - discharging") }.size)
            assertEquals(1, output.filter { it.contains("Byteman test - overnight observation") }.size)
            val numberOfTerminalDiagnoses = output.filter { it.contains("Byteman test - terminal") }.size
            assertEquals(0, numberOfTerminalDiagnoses)
            val (discharge, observation) = aliceClient.startFlow(StatemachineErrorHandlingTest::GetHospitalCountersFlow).returnValue.get()
            assertEquals(3, discharge)
            assertEquals(1, observation)
            assertEquals(0, aliceClient.stateMachinesSnapshot().size)
            // 1 for GetNumberOfCheckpointsFlow
            assertEquals(1, aliceClient.startFlow(StatemachineErrorHandlingTest::GetNumberOfUncompletedCheckpointsFlow).returnValue.get())
        }
    }

    @StartableByRPC
    class SleepFlow : FlowLogic<Unit>() {

        object STARTED : ProgressTracker.Step("I am ready to die")

        override val progressTracker = ProgressTracker(STARTED)

        @Suspendable
        override fun call() {
            sleep(Duration.of(1, ChronoUnit.SECONDS))
            progressTracker.currentStep = STARTED
            sleep(Duration.of(2, ChronoUnit.MINUTES))
        }
    }

    @StartableByRPC
    class ThreadSleepFlow : FlowLogic<Unit>() {

        object STARTED : ProgressTracker.Step("I am ready to die")

        override val progressTracker = ProgressTracker(STARTED)

        @Suspendable
        override fun call() {
            sleep(Duration.of(1, ChronoUnit.SECONDS))
            progressTracker.currentStep = STARTED
            logger.info("Starting ${ThreadSleepFlow::class.qualifiedName} application sleep")
            sleep()
            logger.info("Finished ${ThreadSleepFlow::class.qualifiedName} application sleep")
            sleep(Duration.of(2, ChronoUnit.MINUTES))
        }

        // Sleep is moved outside of `@Suspendable` function to prevent issues with Quasar
        private fun sleep() {
            Thread.sleep(20000)
        }
    }
}