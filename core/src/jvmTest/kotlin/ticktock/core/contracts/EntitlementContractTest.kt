// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.fakes.FakeEntitlement
import ticktock.core.testing.TEST_SUPPORT_MODULE
import java.io.File

/**
 * ENT-01: the entitlement seam exists, has one production implementation that
 * grants everything, and is referenced from at most the three gated features.
 * Decision 15 sells the whole app once; the seam is kept so the reserve plan
 * (a free listing with one unlock) stays a one-module change.
 */
class EntitlementContractTest {
    @Test
    fun `AlwaysEntitled grants every gated feature`() {
        GatedFeature.entries.forEach { feature ->
            assertTrue(feature.name, AlwaysEntitled.has(feature))
        }
    }

    @Test
    fun `the three gated features are exactly all-history, named sessions and export`() {
        assertEquals(
            listOf("ALL_HISTORY", "NAMED_SESSIONS", "EXPORT"),
            GatedFeature.entries.map { it.name },
        )
    }

    @Test
    fun `the fake grants what a test allows and nothing else`() {
        val entitlement = FakeEntitlement(GatedFeature.EXPORT)
        assertTrue(entitlement.has(GatedFeature.EXPORT))
        assertFalse(entitlement.has(GatedFeature.ALL_HISTORY))
        assertFalse(entitlement.has(GatedFeature.NAMED_SESSIONS))

        entitlement.granted += GatedFeature.ALL_HISTORY
        assertTrue(entitlement.has(GatedFeature.ALL_HISTORY))
    }

    /**
     * The grep half of ENT-01. Every main-source file in every module that
     * names the seam must be the contract itself or one of the call sites
     * listed here. The list fills up as the three features land (M6) and
     * never grows past three.
     */
    @Test
    fun `the seam has no call sites outside the three gated features`() {
        val root = File("..").canonicalFile
        assertTrue("test must run with the module directory as working directory", File(root, "core").isDirectory)

        val contract = "core/src/commonMain/kotlin/ticktock/core/contracts/Entitlement.kt"
        assertTrue("$contract is missing", File(root, contract).isFile)
        assertTrue("at most three call sites", allowedCallSites.size <= GatedFeature.entries.size)

        val seam = Regex("""\b(Entitlement|AlwaysEntitled|GatedFeature)\b""")
        val offenders = root.listFiles { f -> f.isDirectory && f.name != TEST_SUPPORT_MODULE }
            .orEmpty()
            .flatMap { module -> MAIN_SOURCE_ROOTS.map { File(module, it) }.filter { it.isDirectory } }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .filter { file -> file.readLines().any { line -> seam.containsMatchIn(line.substringBefore("//")) } }
            .map { it.relativeTo(root).path }
            .filterNot { it == contract || it in allowedCallSites }
        assertEquals("files that use the entitlement seam", emptyList<String>(), offenders)
    }

    private companion object {
        /** Production source roots: Android and JVM modules use src/main, :core uses its multiplatform source sets. */
        val MAIN_SOURCE_ROOTS = listOf("src/main/kotlin", "src/commonMain/kotlin", "src/jvmMain/kotlin")

        /** Repository-relative paths of the files allowed to call the seam; one per gated feature. */
        val allowedCallSites: Set<String> = setOf(
            "app/src/main/kotlin/ticktock/app/history/AllHistoryAccess.kt",
            "app/src/main/kotlin/ticktock/app/history/NamedSessionsAccess.kt",
            "app/src/main/kotlin/ticktock/app/history/TableExport.kt",
        )
    }
}
