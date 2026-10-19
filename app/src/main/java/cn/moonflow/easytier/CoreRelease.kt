package cn.moonflow.easytier

import org.json.JSONArray
import org.json.JSONObject

enum class CoreReleaseChannel(val label: String, val endpoint: String) {
    STABLE("正式发布", "releases/latest"),
    PRERELEASE("预发布", "releases?per_page=100"),
    NIGHTLY("每日构建", "releases/tags/nightly");

    companion object {
        fun fromValue(value: String): CoreReleaseChannel = entries.firstOrNull { it.name == value } ?: STABLE
    }
}

internal data class CoreRelease(val tag: String, val url: String, val version: String) {
    companion object {
        fun parse(body: String, channel: CoreReleaseChannel): CoreRelease {
            val release = if (channel == CoreReleaseChannel.PRERELEASE) {
                val list = JSONArray(body)
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    .filter { !it.optBoolean("draft") && it.optBoolean("prerelease") && it.optString("tag_name") != "nightly" }
                    .maxByOrNull { it.optString("published_at") }
                    ?: error("上游暂未提供预发布版本")
            } else JSONObject(body)
            require(!release.optBoolean("draft")) { "不能下载草稿版本" }
            val tag = release.getString("tag_name")
            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull {
                it.optString("name").matches(Regex("easytier-linux-aarch64-.*\\.zip"))
            } ?: error("该版本缺少 Linux aarch64 核心附件")
            val url = asset.getString("browser_download_url")
            require(url.startsWith("https://github.com/EasyTier/EasyTier/releases/download/")) { "核心下载地址无效" }
            // Nightly reuses its tag; retain the asset timestamp to distinguish builds.
            val version = if (channel == CoreReleaseChannel.NIGHTLY) "$tag · ${asset.optString("updated_at")}" else tag
            return CoreRelease(tag, url, version)
        }
    }
}
