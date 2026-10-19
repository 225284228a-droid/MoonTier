package cn.moonflow.easytier

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoreReleaseTest {
    private fun release(tag: String, pre: Boolean = false, date: String = "2026-10-01", assetVersion: String = tag) = JSONObject()
        .put("tag_name", tag).put("prerelease", pre).put("published_at", date)
        .put("assets", JSONArray().put(JSONObject()
            .put("name", "easytier-linux-aarch64-$assetVersion.zip")
            .put("browser_download_url", "https://github.com/EasyTier/EasyTier/releases/download/$tag/easytier-linux-aarch64-$assetVersion.zip")
            .put("updated_at", date)))

    @Test fun selectsPrereleaseWithoutNightlyOrStable() {
        val releases = JSONArray().put(release("nightly", true, "2026-10-20"))
            .put(release("v2.6.4")).put(release("v2.7.0", true, "2026-10-06"))
            .put(release("v2.6.3", true, "2026-05-02"))
        assertEquals("v2.7.0", CoreRelease.parse(releases.toString(), CoreReleaseChannel.PRERELEASE).tag)
    }

    @Test fun nightlyUsesAssetUrlAndBuildTimestamp() {
        val found = CoreRelease.parse(release("nightly", true, "2026-10-20", "v2.7.0").toString(), CoreReleaseChannel.NIGHTLY)
        assertTrue(found.url.endsWith("/nightly/easytier-linux-aarch64-v2.7.0.zip"))
        assertEquals("nightly · 2026-10-20", found.version)
    }

    @Test fun stableUsesReleaseAssetAndSettingsRoundTrip() {
        assertEquals("v2.6.4", CoreRelease.parse(release("v2.6.4").toString(), CoreReleaseChannel.STABLE).version)
        for (channel in CoreReleaseChannel.entries) {
            assertEquals(channel, AppSettings.fromJson(AppSettings(coreReleaseChannel = channel).toJson()).coreReleaseChannel)
        }
        assertEquals(CoreReleaseChannel.STABLE, AppSettings.fromJson(JSONObject()).coreReleaseChannel)
    }

    @Test(expected = IllegalStateException::class) fun failsWhenArchitectureIsMissing() {
        CoreRelease.parse(release("v2.7.0").put("assets", JSONArray()).toString(), CoreReleaseChannel.STABLE)
    }
}
