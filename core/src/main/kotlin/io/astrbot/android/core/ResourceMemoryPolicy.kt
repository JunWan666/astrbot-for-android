package io.astrbot.android.core

data class MemoryReading(val totalKiB: Long?, val partialKiB: Long?, val measured: Int, val observed: Int, val complete: Boolean)

object ResourceMemoryPolicy {
    fun percentOfPhysical(pssKiB: Long?, physicalBytes: Long?): Double? {
        if (pssKiB == null || physicalBytes == null || pssKiB < 0 || physicalBytes <= 0) return null
        val percent = pssKiB.toDouble() * 1024 / physicalBytes * 100
        return percent.takeIf { it.isFinite() && it in 0.0..100.0 }
    }

    fun startTicks(pid: Int, stat: String): Long {
        require(stat.substringBefore(" (").toIntOrNull() == pid) { "Process identity mismatch" }
        val separator = stat.lastIndexOf(") ")
        require(separator > 0)
        val fields = stat.substring(separator + 2).trim().split(Regex("\\s+"))
        require(fields.size >= 20)
        return fields[19].toLong().also { require(it >= 0) }
    }

    fun pssKiB(lines: Sequence<String>, single: Boolean): Long {
        var count = 0
        var total = 0L
        lines.forEach { line ->
            if (line.startsWith("Pss:")) {
                val match = Regex("Pss:\\s+(\\d+)\\s+kB").matchEntire(line.trim()) ?: error("Invalid PSS")
                total = Math.addExact(total, match.groupValues[1].toLong())
                count++
            }
        }
        require(count > 0 && (!single || count == 1)) { "Missing or ambiguous PSS" }
        return total
    }

    fun summarize(before: Map<Int, Long>, after: Map<Int, Long>, readings: Map<Int, Long>, manager: Int): MemoryReading {
        require(readings.values.all { it >= 0 })
        val stable = readings.filterKeys { it in before && before[it] == after[it] }
        val sum = stable.values.fold(0L, Math::addExact)
        val complete = manager in before && before == after && before.keys == stable.keys
        return MemoryReading(if (complete) sum else null, sum.takeIf { stable.isNotEmpty() }, stable.size,
            (before.keys + after.keys).size, complete)
    }
}
