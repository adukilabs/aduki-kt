# Skill: change a stored entity (ObjectBox "migration")

ObjectBox has no SQL migrations. The model is the `@Entity` classes in `store/src/main/kotlin/pro/aduki/store/entities/` plus `store/objectbox-models/default.json`, which maps names to stable ids.

1. Edit the entity (add a field with a default; renaming needs `@Uid` per the ObjectBox docs, otherwise it is a delete plus add).
2. `./gradlew --no-daemon :store:build` regenerates the model; commit the changed `default.json`. Never hand-edit ids in it; never commit `default.json.bak` (gitignored).
3. Check every code path that builds the entity (sync engines, outbox, repositories) and the fixtures/tests.
4. Local data is a cache of the server plus the outbox. If a change would lose outbox entries, write a data step and test it; otherwise the next sync refills the store (the 0.2.0 `Message` change relied on this).
5. Update `docs/store/entities.md` in the same PR.

Verify: `./gradlew --no-daemon :store:test :sync:test :state:test`.
