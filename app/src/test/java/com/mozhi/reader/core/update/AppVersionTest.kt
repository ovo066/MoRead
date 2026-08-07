package com.mozhi.reader.core.update

import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun comparesReleaseAndPrereleaseVersions() {
        assertTrue(compareVersionNames("v0.10.0-beta5", "0.10.0-beta4") > 0)
        assertTrue(compareVersionNames("0.10.0", "0.10.0-beta9") > 0)
        assertTrue(compareVersionNames("0.11.0-alpha1", "0.10.9") > 0)
        assertTrue(compareVersionNames("1.0.0-rc.2", "1.0.0-rc.10") < 0)
    }
}
