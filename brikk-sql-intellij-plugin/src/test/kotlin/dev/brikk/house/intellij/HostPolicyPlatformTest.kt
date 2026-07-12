package dev.brikk.house.intellij

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.dialects.generic.GenericDialect
import com.intellij.sql.dialects.mysql.MysqlDialect
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The scratch-vs-console host policy: marker gutter + "go Generic SQL" banner behavior
 * across lenient (Generic SQL) and strict (vendor dialect) hosts.
 */
class HostPolicyPlatformTest : BasePlatformTestCase() {

    private val mixed = "-- dialect: clickhouse\nselect toString(1);\n"

    override fun tearDown() {
        try {
            SqlDialectMappings.getInstance(project).setMapping(null, null)
        } finally {
            super.tearDown()
        }
    }

    private fun brikkMarkers() =
        DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project)
            .filter { it.lineMarkerTooltip?.contains("Brikk SQL") == true }

    fun testGutterShownInLenientHost() {
        myFixture.configureByText("lenient.sql", mixed)
        SqlDialectMappings.getInstance(project).setMapping(myFixture.file.virtualFile, GenericDialect.INSTANCE)
        myFixture.doHighlighting()
        assertEquals(1, brikkMarkers().size)
    }

    fun testGutterHiddenInStrictVendorHost() {
        myFixture.configureByText("strict.sql", mixed)
        SqlDialectMappings.getInstance(project).setMapping(myFixture.file.virtualFile, MysqlDialect.INSTANCE)
        myFixture.doHighlighting()
        assertEquals("no gutter in vendor-dialect hosts", 0, brikkMarkers().size)
    }

    fun testBannerOfferedForForeignMarkersInVendorHost() {
        myFixture.configureByText("banner.sql", mixed)
        SqlDialectMappings.getInstance(project).setMapping(myFixture.file.virtualFile, MysqlDialect.INSTANCE)
        val data = MixedDialectNotificationProvider()
            .collectNotificationData(project, myFixture.file.virtualFile)
        assertNotNull("banner expected for clickhouse block in MySQL file", data)
    }

    fun testNoBannerWhenMarkerMatchesHostDialect() {
        myFixture.configureByText("native.sql", "-- dialect: mysql\nselect 1;\n")
        SqlDialectMappings.getInstance(project).setMapping(myFixture.file.virtualFile, MysqlDialect.INSTANCE)
        val data = MixedDialectNotificationProvider()
            .collectNotificationData(project, myFixture.file.virtualFile)
        assertNull("marker matching the host dialect needs no banner", data)
    }

    fun testNoBannerInGenericHost() {
        myFixture.configureByText("generic.sql", mixed)
        SqlDialectMappings.getInstance(project).setMapping(myFixture.file.virtualFile, GenericDialect.INSTANCE)
        val data = MixedDialectNotificationProvider()
            .collectNotificationData(project, myFixture.file.virtualFile)
        assertNull("Generic SQL host is already fine", data)
    }
}
