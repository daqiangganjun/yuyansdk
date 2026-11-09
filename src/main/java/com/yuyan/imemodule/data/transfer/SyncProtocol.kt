package com.yuyan.imemodule.data.transfer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar

@Serializable
data class SyncRecord(
    val value: String? = null,
    val modified: Long,
    val device: String,
    val clock: Map<String, Long> = emptyMap()
)

@Serializable
data class SyncDocument(val format: Int = 1, val records: Map<String, SyncRecord> = emptyMap())

enum class ConflictPolicy { LATEST, LOCAL, REMOTE, ASK }

object SyncProtocol {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    data class Merge(val document: SyncDocument, val conflicts: List<String>)

    fun capture(previous: SyncDocument, values: Map<String, String>, device: String, now: Long, modified: Map<String, Long> = emptyMap()): SyncDocument {
        val records = previous.records.toMutableMap()
        (records.keys + values.keys).forEach { key ->
            val old = records[key]
            val value = values[key]
            if (old == null || old.value != value) {
                val clock = old?.clock.orEmpty().toMutableMap()
                clock[device] = (clock[device] ?: 0) + 1
                val changed = modified[key]?.takeIf { it in 1..now } ?: if (old == null && modified[key] == 0L) 0L else now
                records[key] = SyncRecord(value, changed, device, clock)
            }
        }
        return SyncDocument(records = records.toSortedMap())
    }

    private fun dominates(a: SyncRecord, b: SyncRecord): Boolean =
        (a.clock.keys + b.clock.keys).all { (a.clock[it] ?: 0) >= (b.clock[it] ?: 0) } && a.clock != b.clock

    fun merge(local: SyncDocument, remote: SyncDocument, policy: ConflictPolicy): Merge {
        require(local.format == 1 && remote.format == 1) { "Unsupported sync format" }
        val conflicts = mutableListOf<String>()
        val result = (local.records.keys + remote.records.keys).associateWith { key ->
            val a = local.records[key]
            val b = remote.records[key]
            when {
                a == null -> b!!
                b == null -> a
                dominates(a, b) -> a
                dominates(b, a) -> b
                a == b -> a
                else -> {
                    if (a.value != b.value) conflicts += key
                    val winner = when {
                        a.value == b.value -> if (compareValuesBy(a, b, { it.modified }, { it.device }) >= 0) a else b
                        policy == ConflictPolicy.LOCAL || policy == ConflictPolicy.ASK -> a
                        policy == ConflictPolicy.REMOTE -> b
                        else -> if (compareValuesBy(a, b, { it.modified }, { it.device }, { it.value.orEmpty() }) >= 0) a else b
                    }
                    winner.copy(clock = (a.clock.keys + b.clock.keys).associateWith {
                        maxOf(a.clock[it] ?: 0, b.clock[it] ?: 0)
                    })
                }
            }
        }
        return Merge(SyncDocument(records = result.mapValues { it.value.copy(clock = it.value.clock.toSortedMap()) }.toSortedMap()), conflicts)
    }

    fun period(frequency: String, now: Long): String? {
        val c = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            timeInMillis = now
        }
        return when (frequency) {
            "daily" -> "${c[Calendar.YEAR]}-${c[Calendar.DAY_OF_YEAR]}"
            "weekly" -> {
                val thursday = (c.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 3 - (get(Calendar.DAY_OF_WEEK) + 5) % 7) }
                "${thursday[Calendar.YEAR]}-${c[Calendar.WEEK_OF_YEAR]}"
            }
            "monthly" -> "${c[Calendar.YEAR]}-${c[Calendar.MONTH]}"
            else -> null
        }
    }
}
