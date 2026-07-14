# Room Database Migration Guide

When modifying the ARIA Room database schema, you MUST write an explicit migration. Never rely on destructive migration — it wipes all user data.

## When a migration is required

Any change that affects the database schema requires a version bump and migration:
- Adding a new `@Entity` class (and listing it in `@Database.entities`)
- Adding, removing, or renaming a column on an existing entity
- Adding or changing an index
- Changing a column type or nullability

## Steps

1. **Make your schema change** (new entity, new column, etc.).

2. **Bump the `version` in `@Database`** in `AriaDatabase.kt`.

3. **Write a `Migration` object** in `AriaDatabase.companion`:

   ```kotlin
   val MIGRATION_N_N1 = object : Migration(N, N + 1) {
       override fun migrate(db: SupportSQLiteDatabase) {
           // For a new table:
           db.execSQL("CREATE TABLE IF NOT EXISTS `table_name` (...)")
           // For a new column:
           db.execSQL("ALTER TABLE `table_name` ADD COLUMN `col` TYPE NOT NULL DEFAULT value")
       }
   }
   ```

4. **Register it** in the `getInstance()` builder:
   ```kotlin
   .addMigrations(MIGRATION_9_10, MIGRATION_N_N1)
   ```

5. **Build and verify** — Room validates the migrated schema against the compiled `@Entity` definitions at runtime. A mismatch crashes on startup, so always test.

6. **Commit the exported schema** — `exportSchema = true` writes JSON to `schemas/`. The new version file must be committed alongside the migration.

## Common migration SQL

| Change | SQL |
|--------|-----|
| New table | `CREATE TABLE IF NOT EXISTS \`name\` (columns..., PRIMARY KEY(\`id\`))` |
| New column (non-null) | `ALTER TABLE \`name\` ADD COLUMN \`col\` TEXT NOT NULL DEFAULT ''` |
| New column (nullable) | `ALTER TABLE \`name\` ADD COLUMN \`col\` TEXT` |
| New index | `CREATE INDEX IF NOT EXISTS \`index_name\` ON \`table\`(\`col\`)` |

## How to find the correct SQL

Compare the exported schemas in `schemas/com.aria.launcher.aria.data.AriaDatabase/`:
```bash
diff <(python3 -c "..." N.json | sort) <(python3 -c "..." N+1.json | sort)
```
Or just read the `createSql` field for the changed entity in the new schema JSON — use that as the source of truth for your `CREATE TABLE` statement.

## What NOT to do

- Do not add `fallbackToDestructiveMigration()` — it was removed intentionally to protect user data.
- Do not skip the migration and assume "it's just debug builds" — users on the alpha track will lose predictions, rules, and usage history.
- Do not write migrations that drop and recreate tables unless truly necessary (e.g., SQLite lacks `DROP COLUMN` before API 35).
