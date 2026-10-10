# ObjectBox Storage Architecture

The Aduki Android SDK completely replaces SQLite and Room with **ObjectBox** (version 4.0.3).

---

## 1. The Zero-Copy Principle

In traditional mobile databases (Room, SQLite, Realm), querying an entity incurs substantial overhead:

```text
SQLite Query Path:
SQL string → Query parser → B-Tree traversal → Cursor row window → JNI boundary allocation → Reflection/Constructor instantiation
(High GC pauses, ~3.5 MB allocation per 1,000 items)
```

In contrast, ObjectBox uses Google FlatBuffers with memory mapping (`mmap`):

```text
ObjectBox Zero-Copy Path:
Query filter → Native C++ B-Tree → mmap memory address → Direct FlatBuffers field offset
(Zero GC allocation, ~180 KB total overhead per 1,000 items, sub-millisecond return)
```

---

## 2. Hardware-Backed Encryption

The `BoxStore` is encrypted at the file-system block level using an AES-256 master key derived from the Android KeyStore:

```kotlin
val builder = Factory.create(context.filesDir, masterEncryptionKey)
val store = builder.build()
```

If the device is rooted or the flash memory extracted, the `.mdb` database file remains an unreadable ciphertext blob.
