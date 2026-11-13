// @author 雾晚
package io.nekohasekai.sagernet

import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.utils.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import android.os.Parcel

/** Actual generator, legacy records and atomic restore integration. @author 雾晚 */
class PortableBackupCompatibilityTest {
    @Test fun contentResolverMissingNamesOctetStreamAndDeniedReadAreHandled() {
        val context=InstrumentationRegistry.getInstrumentation().context
        val resolver=context.contentResolver
        for(path in listOf("named","nameless","query-fails")) {
            val uri=android.net.Uri.parse("content://${context.packageName}.backup.fixture/$path")
            val metadata=BackupFiles.metadata(resolver,uri)
            assertEquals("application/octet-stream",metadata.mime)
            if(path=="named")assertEquals("fixture.JSON",metadata.name) else assertNull(metadata.name)
            val root=resolver.openInputStream(uri)!!.use { BackupFiles.read(it,metadata.name) }
            assertEquals(2,root.getInt("schemaVersion"))
        }
        val denied=android.net.Uri.parse("content://${context.packageName}.backup.fixture/denied")
        try { resolver.openInputStream(denied);fail("denied provider was read") } catch(_:SecurityException) {}
    }
    private fun snapshot() = BackupRestore.Plan(SagerDatabase.proxyDao.getAll(),SagerDatabase.groupDao.allGroups(),
        SagerDatabase.rulesDao.allRules(),PublicDatabase.kvPairDao.all())
    private fun sample() = BackupRestore.Plan(
        listOf(ProxyEntity(id=812,groupId=811,tx=22).putBean(SOCKSBean().apply {
            initializeDefaultValues();serverAddress="192.0.2.2";name="虚构节点";username="fictional";password="test-only"
        })),listOf(ProxyGroup(id=811,name="跨设备虚构分组",isSelector=true,frontProxy=812)),
        listOf(RuleEntity(id=813,enabled=true,outbound=812,domains="example.invalid",config="{\"invert\":true}")),
        listOf(KeyValuePair("fixture.text").put("中文虚构值")))
    @Test fun actualExportImportsIntoEmptyDestinationWithoutSourceDatabase() {
        val original=snapshot()
        try {
            BackupRestore.apply(sample(),true,true,true)
            val bytes=BackupHelper.doBackup()
            val root=BackupFiles.read(bytes.inputStream(),BackupFiles.fileName())
            assertEquals(PortableBackup.FORMAT,root.getString("format"));assertEquals(2,root.getInt("schemaVersion"))
            BackupRestore.apply(BackupRestore.Plan(emptyList(),emptyList(),emptyList(),emptyList()),true,true,true)
            BackupRestore.apply(BackupRestore.parse(root),true,true,true)
            val restored=snapshot()
            assertEquals(1,restored.profiles!!.size);assertEquals(1,restored.groups!!.size);assertEquals(sample().rules,restored.rules)
            assertEquals("test-only",restored.profiles.single().socksBean!!.password)
            assertEquals(812L,restored.groups.single().frontProxy);assertTrue(restored.groups.single().isSelector)
            assertEquals("中文虚构值",PublicDatabase.kvPairDao["fixture.text"]!!.string)
            val settingsOnly=BackupFiles.read(BackupHelper.doBackup(false,false,true).inputStream())
            assertFalse(settingsOnly.has("profiles"));assertFalse(settingsOnly.has("rules"));assertTrue(settingsOnly.has("settings"))
        } finally { BackupRestore.apply(original,true,true,true) }
    }
    private fun parcel(value:android.os.Parcelable):String {
        val p=Parcel.obtain()
        return try { value.writeToParcel(p,0);android.util.Base64.encodeToString(p.marshall(),android.util.Base64.NO_WRAP) }
        finally { p.recycle() }
    }
    @Test fun historicalManualAndAutomaticExportsAreUnambiguous() {
        val plan=sample()
        for(automatic in listOf(false,true)) {
            val root=JSONObject().put(if(automatic)"proxies" else "profiles",JSONArray().put(parcel(plan.profiles!!.single())))
                .put("groups",JSONArray().put(parcel(plan.groups!!.single())))
                .put("rules",JSONArray().put(parcel(plan.rules!!.single())))
                .put("settings",JSONArray().put(parcel(plan.settings!!.single())))
            if(!automatic)root.put("version",1)
            val migrated=BackupRestore.parse(BackupFiles.read(root.toString().byteInputStream(),"old.JSON"))
            assertEquals(812L,migrated.profiles!!.single().id)
            assertEquals("test-only",migrated.profiles.single().socksBean!!.password)
            assertEquals(plan.rules,migrated.rules)
        }
    }
    @Test fun historicalSettingsImportCompletesMissingCoreDefaults() {
        val root = JSONObject().put("version", 1)
            .put("settings", JSONArray().put(parcel(KeyValuePair(Key.MTU).put("1450"))))
        val settings = BackupRestore.parse(root).settings!!.associateBy { it.key }
        assertEquals("1450", settings[Key.MTU]?.string)
        assertEquals("https://dns.google/dns-query", settings[Key.REMOTE_DNS]?.string)
        assertEquals("https://223.5.5.5/dns-query", settings[Key.DIRECT_DNS]?.string)
        assertEquals(true, settings[Key.ENABLE_DNS_ROUTING]?.boolean)
        assertNull(settings[Key.INPUT_METHOD_DIRECT])

        val unselected = BackupRestore.parse(JSONObject().put("version", 1).put("rules", JSONArray()))
        assertNull(unselected.settings)
    }
    @Test fun cancelledOrFailedRestoreKeepsAllDatabasesIntact() {
        val original=snapshot()
        try {
            BackupRestore.apply(sample(),true,true,true)
            val before=snapshot();var calls=0
            try { BackupRestore.apply(BackupRestore.Plan(emptyList(),emptyList(),emptyList(),emptyList()),true,true,true) {
                if(++calls==4) throw kotlinx.coroutines.CancellationException("fixture cancellation")
            };fail("cancelled import committed") } catch(_:kotlinx.coroutines.CancellationException) {}
            val after=snapshot()
            assertEquals(before.profiles!!.map { it.id },after.profiles!!.map { it.id });assertEquals(before.rules,after.rules)
            assertEquals(before.groups!!.map { it.id },after.groups!!.map { it.id })
            assertArrayEquals(before.settings!!.single().value,after.settings!!.single().value)
            calls=0
            try { BackupRestore.apply(BackupRestore.Plan(emptyList(),emptyList(),emptyList(),listOf(KeyValuePair("fixture.text").put("new"))),true,true,true) {
                if(++calls==6)throw android.database.sqlite.SQLiteException("fixture late database failure")
            };fail("failed import committed") } catch(_:android.database.sqlite.SQLiteException) {}
            assertEquals(before.rules,SagerDatabase.rulesDao.allRules())
            assertEquals(before.profiles.map { it.id },SagerDatabase.proxyDao.getAll().map { it.id })
            assertArrayEquals(before.settings.single().value,PublicDatabase.kvPairDao["fixture.text"]!!.value)
            try { BackupFiles.read("{broken}".byteInputStream(),"bad.json");fail() } catch(_:Exception) {}
            assertEquals(before.rules,SagerDatabase.rulesDao.allRules())
        } finally { BackupRestore.apply(original,true,true,true) }
    }
    @Test fun damagedOrUnsupportedImportsKeepAllDatabasesIntact() {
        val original = snapshot()
        try {
            BackupRestore.apply(sample(), true, true, true)
            val before = PortableBackup.encode(snapshot())
            val valid = PortableBackup.encode(sample())
            val unsupported = JSONObject(valid.toString(Charsets.UTF_8)).put("schemaVersion", 999).toString().toByteArray()
            val unsupportedType = JSONObject(valid.toString(Charsets.UTF_8)).apply {
                getJSONArray("profiles").getJSONObject(0).put("type", -1)
            }.toString().toByteArray()
            for (bytes in listOf("{broken}".toByteArray(), valid.copyOf(valid.size - 1), unsupported, unsupportedType, valid + "{}".toByteArray())) {
                try {
                    BackupRestore.apply(BackupRestore.parse(BackupFiles.read(bytes.inputStream(), "fixture.json")), true, true, true)
                    fail("invalid backup committed")
                } catch (_: Exception) {}
                assertArrayEquals(before, PortableBackup.encode(snapshot()))
            }
            val profile = sample().profiles!!.single()
            val payload = io.nekohasekai.sagernet.fmt.KryoConverters.serialize(profile)
            fun changedInt(offset: Int, value: Int) = payload.copyOf().apply {
                // @author 雾晚: 手工按 Kryo 的小端序写 4 字节，不再 new ByteBufferOutput。
                // app 与 androidTest 各自经 R8，测试直接构造 app 未使用的三方构造函数会在
                // release 变体抛 NoSuchMethodError（run 42214710424 / job 127882502678）。
                // 字节序已用 kryo-5.2.1.jar 在本地 JVM 实证：writeInt(0x01020304) -> [4,3,2,1]。
                this[offset] = value.toByte()
                this[offset + 1] = (value ushr 8).toByte()
                this[offset + 2] = (value ushr 16).toByte()
                this[offset + 3] = (value ushr 24).toByte()
            }
            for (damagedPayload in listOf(payload.copyOf(payload.size - 1), payload + byteArrayOf(0), changedInt(0, 2), changedInt(20, -1))) {
                val payloadParcel = Parcel.obtain()
                val damaged = try {
                    payloadParcel.writeByteArray(damagedPayload)
                    android.util.Base64.encodeToString(payloadParcel.marshall(), android.util.Base64.NO_WRAP)
                } finally { payloadParcel.recycle() }
                val legacy = JSONObject().put("version", 1).put("profiles", JSONArray().put(damaged))
                    .put("groups", JSONArray().put(parcel(sample().groups!!.single())))
                try {
                    BackupRestore.apply(BackupRestore.parse(legacy), true, true, true)
                    fail("invalid profile committed")
                } catch (_: Exception) {}
                assertArrayEquals(before, PortableBackup.encode(snapshot()))
            }

            // @author 雾晚: 缺失 payload 的记录按真实导入路径拒绝，数据库保持不变。
            // 不要跨 APK 直接调 ProxyEntity.CREATOR.createFromParcel：release 变体下 R8 改写
            // CREATOR 匿名类的方法签名，独立 instrumentation APK 的调用会抛
            // NoSuchMethodError（run 42220586400 / job 127901997604）。
            // 生产 BackupRestore.parse 的 profile 解码走 KryoConverters.deserializeStrict，
            // 并不经过 CREATOR；后者对 null payload 抛 KryoException("Missing backup record payload")。
            val missingPayloadParcel = Parcel.obtain()
            val missingPayload = try {
                missingPayloadParcel.writeByteArray(null)
                android.util.Base64.encodeToString(missingPayloadParcel.marshall(), android.util.Base64.NO_WRAP)
            } finally { missingPayloadParcel.recycle() }
            val missing = JSONObject().put("version", 1).put("profiles", JSONArray().put(missingPayload))
                .put("groups", JSONArray().put(parcel(sample().groups!!.single())))
            try {
                BackupRestore.apply(BackupRestore.parse(missing), true, true, true)
                fail("profile with missing parcel payload committed")
            } catch (_: Exception) {}
            assertArrayEquals(before, PortableBackup.encode(snapshot()))
        } finally { BackupRestore.apply(original, true, true, true) }
    }
}
