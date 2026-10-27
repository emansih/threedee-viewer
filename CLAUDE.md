# CLAUDE.md

## Coding Style

### Null Safety

Frequent use of !! usually reveals bad state management or poor API design. 

Instead of forcing a value to be non-null, provide a fallback safe default value

```
val nonNullData = checkNotNull(data) { "Initialization failed: Data packet cannot be null." }
val id = nonNullData.id
```

DO NOT use !!

```
val id = data!!.id
```

### Types

Prefer type system instead of `Object`, `Any`, `Nothing` or `JSAny` types. 

## Code Intelligence

### Use LSP server

Prefer LSP over Grep/Read for code navigation — it's faster, precise, and avoids reading entire files:
- `workspaceSymbol` to find where something is defined
- `findReferences` to see all usages across the codebase
- `goToDefinition` / `goToImplementation` to jump to source
- `hover` for type info without reading the file

Use Grep only when LSP isn't available or for text/pattern searches (comments, strings, config).

After writing or editing code, check LSP diagnostics and fix errors before proceeding.

### Inspecting Library/Dependency Code

DO NOT extract `.jar`/`.aar` files to inspect a dependency's classes. kmp-lsp auto-mounts `*-sources.jar` files in-memory straight from `~/.gradle/caches` — use `hover` (declaration kind, signatures, docs) and `goToDefinition` on the symbol instead. Manual extraction is unnecessary; if hover/goToDefinition come back empty, the source jar is missing from the Gradle cache, not a reason to extract the jar by hand.


Prefer Java Docs MCP over Grep/Read for versioned documentation for the artifact. 

### Code Editing

Use Edit/Read tool to create / edit file