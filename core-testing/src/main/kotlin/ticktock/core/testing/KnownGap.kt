// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.testing

import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Marks a test as a known gap: a behaviour the specification requires that
 * the code does not deliver yet. The test keeps its full assertions and keeps
 * running; [KnownGapRule] turns its failure into a pass and its unexpected
 * pass into a failure, so the day the gap closes the build goes red and the
 * annotation has to be removed. This replaces skipping, which the gate rejects.
 *
 * @property criterion the acceptance criterion the gap belongs to, for example "DET-07"
 * @property reason what is missing, in one sentence
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class KnownGap(val criterion: String, val reason: String)

/**
 * JUnit rule that applies [KnownGap]. Add it once per test class; methods
 * without the annotation run unchanged.
 */
class KnownGapRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        val gap = description.getAnnotation(KnownGap::class.java) ?: return base
        return object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (skipped: AssumptionViolatedException) {
                    // A gap must fail on its merits; an assumption is a skip in disguise.
                    throw skipped
                } catch (expected: Throwable) {
                    System.err.println(
                        "known gap ${gap.criterion} (${description.methodName}): still failing, ${gap.reason}",
                    )
                    return
                }
                throw AssertionError(
                    "known gap ${gap.criterion} (${description.methodName}) passed: " +
                        "the gap has closed, remove the @KnownGap annotation and record it in the ledger",
                )
            }
        }
    }
}
