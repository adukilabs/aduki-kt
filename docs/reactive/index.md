# Unidirectional Data Flow (UDF)

The SDK is built around unidirectional data flow: the UI observes state, and changes go through the local store:

```text
┌─────────────────────────────────────────────────────────────┐
│                    Jetpack Compose UI                       │
│    (Renders StateFlow; dispatches user action intents)      │
└──────────────┬──────────────────────────────▲───────────────┘
               │ User Action                  │ Reactive StateFlow
               ▼                              │
┌──────────────────────────────┐              │
│       Mutation Engine        │              │
│  (Marks dirty, inserts into  │              │
│   ObjectBox Outbox journal)  │              │
└──────────────┬───────────────┘              │
               │ ACID Transaction             │
               ▼                              │
┌─────────────────────────────────────────────┴───────────────┐
│                 ObjectBox Local Database                    │
│      (Single source of truth) │
└──────────────┬──────────────────────────────▲───────────────┘
               │ Background Dispatch          │ Reconcile Sync
               ▼                              │
┌──────────────────────────────┐┌─────────────┴───────────────┐
│        Outbox Worker         ││      CONDSTORE/MODSEQ       │
│  (Dispatches queued network  ││         Sync Engine         │
│     mutations with jitter)   ││    (Pulls delta updates)    │
└──────────────────────────────┘└─────────────────────────────┘
```

1. **State flows in one direction only**: from ObjectBox through hot `StateFlow` streams directly to the UI.
2. **UI never modifies in-memory state directly**: interactions trigger mutations that write atomically to the database.
3. **Database emissions update the UI**: ObjectBox data change observers trigger `StateFlow` updates reactively.
