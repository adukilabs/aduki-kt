# Proven Fastest Algorithms & Performance Specification

> Design note (internal). Written before parts of it were built; it states intent, and its performance numbers are unmeasured targets. Where it disagrees with the code (database encryption, StrongBox, circuit breaker use, gRPC use), the code and `progress.md` section 3.3 win. gRPC was dropped (D-HOST-5, 2026-10-10): every mention of it below is history.

This document details the algorithmic foundations and micro-optimizations that give the Aduki Android Kotlin SDK its sub-millisecond execution speeds and minimal battery footprint.

---

## 1. Algorithm Selection Matrix

| Problem Space | Chosen Algorithm | Replaced Alternative | Speedup Factor |
| :--- | :--- | :--- | :--- |
| **Cache Key & Change Hashing** | **xxHash64** | SHA-256 / MD5 | **48x faster** (11 GB/s vs 0.23 GB/s) |
| **Serialization & Deserialization** | **FlatBuffers / Protobuf** | JSON (Moshi / Jackson) | **35x faster** (Zero-copy offsets) |
| **Network Retry & Jitter** | **Decorrelated Jitter** | Standard Exponential Backoff | Eliminates 100% of thundering herds |
| **Outbox Action Ring Buffer** | **Lock-Free SPSC Ring** | `LinkedBlockingQueue` | **8.5x faster** (Zero lock contention) |
| **Paging & List Navigation** | **ObjectBox Native Cursor** | SQLite `LIMIT / OFFSET` | **$O(1)$ vs $O(N)$** seek time |

---

## 2. Fast Hashing: xxHash64

When computing cache keys, ETag comparisons, or detecting message body mutations, cryptographic hashes (like SHA-256) waste substantial CPU cycles and battery.

The SDK integrates **xxHash64**, an extremely fast non-cryptographic hash algorithm executing near RAM bandwidth limits.

```kotlin
package pro.aduki.core.memory

object FastHash {
    private const val PRIME64_1 = -7046029254386353131L
    private const val PRIME64_2 = -4417276706814115161L
    private const val PRIME64_3 = 1609587929392839161L
    private const val PRIME64_4 = -8796714831682220699L
    private const val PRIME64_5 = 2870177440012608261L

    fun hash64(data: ByteArray, offset: Int = 0, length: Int = data.size, seed: Long = 0L): Long {
        var hash = seed + PRIME64_5 + length
        var index = offset
        val end = offset + length

        while (index <= end - 8) {
            val k1 = getLongLE(data, index) * PRIME64_2
            val rotated = java.lang.Long.rotateLeft(k1, 31) * PRIME64_1
            hash = hash xor rotated
            hash = java.lang.Long.rotateLeft(hash, 27) * PRIME64_1 + PRIME64_4
            index += 8
        }

        while (index < end) {
            hash = hash xor ((data[index].toLong() and 0xFF) * PRIME64_5)
            hash = java.lang.Long.rotateLeft(hash, 11) * PRIME64_1
            index++
        }

        hash = hash xor (hash ushr 33)
        hash *= PRIME64_2
        hash = hash xor (hash ushr 29)
        hash *= PRIME64_3
        return hash xor (hash ushr 32)
    }

    private fun getLongLE(b: ByteArray, i: Int): Long {
        return (b[i].toLong() and 0xFF) or
            ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or
            ((b[i + 3].toLong() and 0xFF) shl 24) or
            ((b[i + 4].toLong() and 0xFF) shl 32) or
            ((b[i + 5].toLong() and 0xFF) shl 40) or
            ((b[i + 6].toLong() and 0xFF) shl 48) or
            ((b[i + 7].toLong() and 0xFF) shl 56)
    }
}
```

---

## 3. Network Backoff: Decorrelated Jitter

Standard exponential backoff causes synchronized client retry waves ("thundering herds") when a server recovers. Aduki uses the proven **Decorrelated Jitter** algorithm (developed by AWS Architecture research), which breaks synchronization while minimizing total wait duration.

### Mathematical Definition

$$t_i = \min(t_{\max}, \text{Uniform}(t_{\text{base}}, t_{i-1} \times 3))$$

### Kotlin Implementation

```kotlin
package pro.aduki.net.retry

import kotlin.math.min
import kotlin.random.Random

class DecorrelatedJitter(
    private val baseDelayMs: Long = 100,
    private val maxDelayMs: Long = 30_000
) {
    private var currentDelay: Long = baseDelayMs

    fun nextDelay(): Long {
        val next = Random.nextLong(baseDelayMs, currentDelay * 3)
        currentDelay = min(maxDelayMs, next)
        return currentDelay
    }

    fun reset() {
        currentDelay = baseDelayMs
    }
}
```

---

## 4. Zero-Copy FlatBuffers Binary Wire

Traditional mobile clients spend up to 40% of their CPU time converting JSON strings to POJOs.

1. **Direct Offset Pointers**: With FlatBuffers, data is accessed via fixed byte offsets in memory.
2. **Zero GC Footprint**: Reading the `subject` of an email does not parse the rest of the 50KB message payload.
3. **Hardware Alignment**: Numbers and byte arrays match mobile CPU word boundaries directly.

```text
FlatBuffers Binary Layout:
[vtable_offset: 2B][data_offset: 4B] ──> [uid: 8B][flags: 4B][string_len: 4B][utf8_bytes]
(Reads directly into register via pointer arithmetic: *reinterpret_cast<const uint64_t*>(ptr + offset))
```

---

## 5. Lock-Free SPSC Ring Buffer for Outbox Events

To dispatch high-frequency telemetry, analytical events, and local outbox actions without thread locks:

- Utilizes a Single-Producer Single-Consumer (SPSC) circular ring buffer.
- Head and tail pointers are updated via atomic CAS (`AtomicLong.lazySet`), avoiding kernel lock escalation and JVM `synchronized` monitors.

---

## 6. Cursor-Based Pagination vs SQLite LIMIT/OFFSET

In SQLite/Room:

```sql
-- Disaster for deep lists: scans and discards 10,000 rows
SELECT * FROM messages ORDER BY date DESC LIMIT 50 OFFSET 10000;
```

Execution cost: $O(N)$ time complexity.

In Aduki ObjectBox:

- ObjectBox queries use native B-Tree index positions.
- Navigating to page 200 executes via direct B-Tree traversal in $O(\log N)$ or cursor advancement in $O(1)$ time.
- Zero cursor window buffer allocations.
