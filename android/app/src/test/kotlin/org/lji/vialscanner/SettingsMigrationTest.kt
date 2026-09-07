package org.lji.vialscanner

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scan deadline comes from a saved setting, so a value saved under an older
 * default outlives the change. Four annotated Pixel scans on 2026-09-07 all
 * ended at 5.1 s with the label half unread because of exactly that.
 */
class SettingsMigrationTest {

    @Test fun `a deadline below the slider floor was never chosen and is replaced`() {
        val stale = AppSettings(maxScanSeconds = 4.0).migrated()
        assertEquals(AppSettings.MAX_SCAN_DEFAULT, stale.maxScanSeconds, 0.0)
        assertEquals(AppSettings.MAX_SCAN_DEFAULT, stale.engineConfig().maxScanDuration, 0.0)
    }

    @Test fun `a deliberately short deadline inside the slider range is kept`() {
        val chosen = AppSettings(maxScanSeconds = 5.0).migrated()
        assertEquals(5.0, chosen.maxScanSeconds, 0.0)
    }

    @Test fun `a longer deadline is kept`() {
        assertEquals(15.0, AppSettings(maxScanSeconds = 15.0).migrated().maxScanSeconds, 0.0)
    }

    @Test fun `the hard ceiling follows the deadline rather than capping it`() {
        val c = AppSettings(maxScanSeconds = 12.0).engineConfig()
        assertEquals(12.0, c.hardScanCeiling, 0.0)
    }
}
