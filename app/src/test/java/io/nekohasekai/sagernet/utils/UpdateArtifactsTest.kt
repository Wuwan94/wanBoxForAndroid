// @author 雾晚
package io.nekohasekai.sagernet.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateArtifactsTest {
    private fun release(vararg names: String): String {
        val assets = names.mapIndexed { index, name ->
            JSONObject()
                .put("name", name)
                .put("browser_download_url", "https://example.invalid/$name")
                .put("size", index + 1)
        }
        return JSONObject()
            .put("tag_name", "v3.0.12-preview.1")
            .put("assets", JSONArray(assets))
            .toString()
    }

    @Test
    fun selectsManagerModuleAndSumsFromOneRelease() {
        val assets = UpdateArtifacts.parseAssets(
            release(
                "wanBoxForAndroid-3.0.12-preview.1-arm64-v8a.apk",
                "wanbox-root-module-arm64-v8a.zip",
                "wanbox-root-module-with-manager-arm64-v8a.zip",
                "SHA256SUMS",
            )
        )
        assertEquals(4, assets.size)
        val bundle = UpdateArtifacts.selectBundle(assets)!!
        assertTrue(bundle.apk.name.endsWith(".apk"))
        assertTrue(bundle.module.name.contains("module"))
        assertEquals(UpdateArtifacts.SUMS_NAME, bundle.sums.name)
    }

    @Test
    fun selectsPlainModuleRegardlessOfAssetOrder() {
        val names = arrayOf(
            "wanbox-root-module-with-manager-arm64-v8a.zip",
            "foreign-module.zip",
            "wanBoxForAndroid-3.0.12-preview.1-arm64-v8a.apk",
            "wanbox-root-module-arm64-v8a.zip",
            "SHA256SUMS",
        )
        for (ordered in listOf(names.toList(), names.reversed())) {
            val bundle = UpdateArtifacts.selectBundle(UpdateArtifacts.parseAssets(release(*ordered.toTypedArray())))!!
            assertEquals("wanbox-root-module-arm64-v8a.zip", bundle.module.name)
        }
        assertNull(UpdateArtifacts.selectBundle(UpdateArtifacts.parseAssets(release(
            "wanbox-root-module-with-manager-arm64-v8a.zip",
            "wanBoxForAndroid-3.0.12-preview.1-arm64-v8a.apk", "SHA256SUMS",
        ))))
    }

    @Test
    fun refusesIncompleteRelease() {
        assertNull(
            UpdateArtifacts.selectBundle(
                UpdateArtifacts.parseAssets(
                    release("wanBoxForAndroid-3.0.12-preview.1-arm64-v8a.apk", "SHA256SUMS")
                )
            )
        )
        assertNull(
            UpdateArtifacts.selectBundle(
                UpdateArtifacts.parseAssets(
                    release("wanbox-root-module-arm64-v8a.zip", "SHA256SUMS")
                )
            )
        )
        assertNull(
            UpdateArtifacts.selectBundle(
                UpdateArtifacts.parseAssets(
                    release(
                        "wanBoxForAndroid-3.0.12-preview.1-arm64-v8a.apk",
                        "wanbox-root-module-arm64-v8a.zip",
                    )
                )
            )
        )
    }

    @Test
    fun verifiesOnlyListedDigest() {
        val file = File.createTempFile("wanbox-update", ".apk")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            val sums = UpdateArtifacts.parseSums("$digest  ${file.name}\n")
            assertEquals(digest, sums[file.name])
            assertTrue(UpdateArtifacts.verify(file, sums))
            assertFalse(UpdateArtifacts.verify(file, emptyMap()))
            file.appendBytes(byteArrayOf(5))
            assertFalse(UpdateArtifacts.verify(file, sums))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsDuplicateOrUnsafeAssetNames() {
        val apk = "wanBoxForAndroid-3.0.12-preview.2-arm64-v8a.apk"
        val module = UpdateArtifacts.MODULE_NAME
        val sums = UpdateArtifacts.SUMS_NAME
        assertNull(UpdateArtifacts.selectBundle(UpdateArtifacts.parseAssets(release(
            "../$apk", module, sums,
        ))))
        assertNull(UpdateArtifacts.selectBundle(UpdateArtifacts.parseAssets(release(
            apk, module, sums, module,
        ))))
    }

    @Test
    fun ignoresMalformedSumsLines() {
        val sums = UpdateArtifacts.parseSums(
            """
            not-a-hash  file.apk
            ${"0".repeat(64)} *module.zip
            ${"a".repeat(64)}  two  spaces.apk
            """.trimIndent()
        )
        assertEquals(2, sums.size)
        assertTrue(sums.containsKey("module.zip"))
        assertTrue(sums.containsKey("two  spaces.apk"))
    }
}
