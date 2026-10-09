package dev.valnook.data.portability

import dev.valnook.domain.portability.PortabilityErrorCode
import dev.valnook.domain.portability.PortabilityException
import org.json.JSONArray
import org.json.JSONObject

data class PayloadFileInfo(
    val path: String,
    val recordCount: Long,
    val uncompressedBytes: Long,
    val sha256: String
)

data class ArchiveManifest(
    val backupId: String,
    val createdAtUtc: String,
    val snapshotAtUtc: String,
    val snapshotId: String,
    val accountCount: Long,
    val cashAccountCount: Long,
    val tradeCount: Long,
    val auditEventCount: Long,
    val producer: AppBuildInfo,
    val files: List<PayloadFileInfo>,
    val requiredFeatures: List<String>,
    val dataSchemaVersion: Int = BackupContract.DATA_SCHEMA_VERSION
) {
    fun toJson(): String = JSONObject().apply {
        put("format", BackupContract.FORMAT)
        put("formatVersion", BackupContract.FORMAT_VERSION)
        put("dataSchemaVersion", dataSchemaVersion)
        put("backupId", backupId)
        put("producer", JSONObject().apply {
            put("applicationFamily", producer.applicationFamily)
            put("appVersion", producer.appVersion)
            put("appVersionCode", producer.appVersionCode)
            put("internalBuildRevision", producer.internalBuildRevision)
            put("internalBuildLabel", producer.internalBuildLabel)
            put("databaseSchemaVersion", producer.databaseSchemaVersion)
        })
        put("creation", JSONObject().apply {
            put("createdAtUtc", createdAtUtc)
            put("snapshotAtUtc", snapshotAtUtc)
            put("timeSource", "DEVICE_CLOCK")
            put("source", "MANUAL_LOCAL")
        })
        put("snapshot", JSONObject().apply {
            put("snapshotId", snapshotId)
            put("accountCount", accountCount)
            put("cashAccountCount", cashAccountCount)
            put("tradeCount", tradeCount)
            put("auditEventCount", auditEventCount)
        })
        put("rules", JSONObject().apply {
            put("positionCost", BackupContract.POSITION_COST_RULE)
            put("depositInterest", BackupContract.DEPOSIT_INTEREST_RULE)
            put("historicalValuation", BackupContract.HISTORICAL_VALUATION_RULE)
        })
        put("requiredFeatures", JSONArray(requiredFeatures))
        put("files", JSONArray().apply {
            files.forEach { file -> put(JSONObject().apply {
                put("path", file.path)
                put("recordCount", file.recordCount)
                put("uncompressedBytes", file.uncompressedBytes)
                put("sha256", file.sha256)
            }) }
        })
    }.toString(2)

    companion object {
        fun parse(text: String, limits: BackupReadLimits): ArchiveManifest {
            val root = PortableJson.parse(text, limits.maxJsonDepth).asObject()
            requireKeys(root, setOf("format", "formatVersion", "dataSchemaVersion", "backupId", "producer",
                "creation", "snapshot", "rules", "requiredFeatures", "files"))
            val dataSchemaVersion = root.number("dataSchemaVersion").toIntExact()
            if (root.string("format") != BackupContract.FORMAT ||
                root.number("formatVersion") != BackupContract.FORMAT_VERSION.toLong() ||
                dataSchemaVersion !in 1..BackupContract.DATA_SCHEMA_VERSION) incompatible()
            val producerValue = root.objectValue("producer")
            requireKeys(producerValue, setOf("applicationFamily", "appVersion", "appVersionCode",
                "internalBuildRevision", "internalBuildLabel", "databaseSchemaVersion"))
            val producer = AppBuildInfo(
                producerValue.string("applicationFamily"),
                producerValue.string("appVersion"),
                producerValue.number("appVersionCode"),
                producerValue.string("internalBuildRevision"),
                producerValue.string("internalBuildLabel"),
                producerValue.number("databaseSchemaVersion").toIntExact()
            )
            val creation = root.objectValue("creation")
            requireKeys(creation, setOf("createdAtUtc", "snapshotAtUtc", "timeSource", "source"))
            if (creation.string("timeSource") != "DEVICE_CLOCK" || creation.string("source") != "MANUAL_LOCAL") fail()
            val snapshot = root.objectValue("snapshot")
            requireKeys(snapshot, setOf("snapshotId", "accountCount", "cashAccountCount", "tradeCount", "auditEventCount"))
            val rules = root.objectValue("rules")
            requireKeys(rules, setOf("positionCost", "depositInterest", "historicalValuation"))
            if (rules.number("positionCost") != BackupContract.POSITION_COST_RULE.toLong() ||
                rules.number("depositInterest") != BackupContract.DEPOSIT_INTEREST_RULE.toLong() ||
                rules.number("historicalValuation") != BackupContract.HISTORICAL_VALUATION_RULE.toLong()) incompatible()
            val features = root.list("requiredFeatures").map { it as? String ?: fail() }
            if (features.toSet().size != features.size) fail()
            if (dataSchemaVersion >= 6 && "wallet-cards-v1" !in features) incompatible()
            if (dataSchemaVersion >= 2 && "credit-accounts-v1" !in features) incompatible()
            if (dataSchemaVersion >= 3 && "account-order-v1" !in features) incompatible()
            if (dataSchemaVersion >= 5 && "account-presentation-v1" !in features) incompatible()
            if (dataSchemaVersion >= 4 && "account-icons-v1" !in features) incompatible()
            val files = root.list("files").map { item ->
                val value = item.asObject()
                requireKeys(value, setOf("path", "recordCount", "uncompressedBytes", "sha256"))
                PayloadFileInfo(value.string("path"), value.number("recordCount"),
                    value.number("uncompressedBytes"), value.string("sha256").also {
                        if (!SHA256.matches(it)) fail()
                    })
            }
            if (files.map { it.path }.toSet().size != files.size || files.any {
                    it.recordCount < 0 || it.uncompressedBytes < 0
                }) fail()
            return ArchiveManifest(
                dataSchemaVersion = dataSchemaVersion,
                backupId = root.string("backupId"),
                createdAtUtc = creation.string("createdAtUtc"),
                snapshotAtUtc = creation.string("snapshotAtUtc"),
                snapshotId = snapshot.string("snapshotId"),
                accountCount = snapshot.number("accountCount"),
                cashAccountCount = snapshot.number("cashAccountCount"),
                tradeCount = snapshot.number("tradeCount"),
                auditEventCount = snapshot.number("auditEventCount"),
                producer = producer,
                files = files,
                requiredFeatures = features
            )
        }

        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal object CompatibilityRules {
    fun validate(source: ArchiveManifest, reader: AppBuildInfo) {
        if (source.producer.applicationFamily != reader.applicationFamily) incompatible()
        if (source.producer.appVersionCode > reader.appVersionCode ||
            compareVersion(source.producer.appVersion, reader.appVersion) > 0 ||
            compareVersion(source.producer.internalBuildRevision, reader.internalBuildRevision) > 0 ||
            source.producer.databaseSchemaVersion > reader.databaseSchemaVersion) incompatible()
        if (!BackupContract.requiredFeatures.containsAll(source.requiredFeatures)) {
            throw PortabilityException(PortabilityErrorCode.UNKNOWN_FEATURE)
        }
    }

    private fun compareVersion(left: String, right: String): Int {
        fun parse(value: String): List<Long> {
            if (!VERSION.matches(value)) incompatible()
            return value.split('.', '-').map { it.toLongOrNull() ?: incompatible() }
        }
        val a = parse(left)
        val b = parse(right)
        repeat(maxOf(a.size, b.size)) { index ->
            val comparison = (a.getOrElse(index) { 0L }).compareTo(b.getOrElse(index) { 0L })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private val VERSION = Regex("[0-9]+(?:[.-][0-9]+)*")
}

private fun Any?.asObject(): Map<*, *> = this as? Map<*, *> ?: fail()
private fun Map<*, *>.string(key: String): String = this[key] as? String ?: fail()
private fun Map<*, *>.number(key: String): Long =
    ((this[key] as? PortableJson.JsonNumber)?.raw ?: fail()).toLongOrNull() ?: fail()
private fun Map<*, *>.objectValue(key: String): Map<*, *> = this[key].asObject()
private fun Map<*, *>.list(key: String): List<*> = this[key] as? List<*> ?: fail()
private fun requireKeys(value: Map<*, *>, expected: Set<String>) {
    if (value.keys != expected) fail()
}
private fun Long.toIntExact(): Int = if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else fail()
private fun fail(): Nothing = throw PortabilityException(PortabilityErrorCode.INVALID_ARCHIVE)
private fun incompatible(): Nothing = throw PortabilityException(PortabilityErrorCode.INCOMPATIBLE_VERSION)
