---
synced_commit: c1838d6
---

# CodexMorphe Architecture & Context Blueprint

`CodexMorphe` is a modern, install-once Android client engine and **LSPosed API 102** module. Instead of baking patches into a monolithic APK, it dynamically streams, validates, and injects author-flavored Morphe patch bundles (`.apk`) compiled by `codexmorphe-builder`.

---

## 1. High-Level System Architecture

```
                          [ Remote GitHub Releases ]
                                       │
                              (bundle-manifest.json)
                                       │
                                       ▼
                             ┌───────────────────┐
                             │ ManifestRepository │
                             └─────────┬─────────┘
                                       │
                                       ▼
                             ┌───────────────────┐
                             │ BundleDownloader  │ (SHA-256 Verified)
                             └─────────┬─────────┘
                                       │
                         (/data/user/0/.../files/bundles/)
                                       │
                   ┌───────────────────┴───────────────────┐
                   │                                       │
                   ▼                                       ▼
        ┌─────────────────────┐                 ┌──────────────────────┐
        │ Jetpack Compose UI  │                 │ LSPosed Hook Engine  │
        │ - AppDashboard      │                 │ - MainHook (API 102) │
        │ - AuthorSelector    │                 │ - openRemoteFile()   │
        │ - InspectPatches    │                 │ - BundleResourceInj. │
        │ - VersionWarning    │                 │ - BundleClassLoader  │
        │ - PatchSettings     │                 │ - DexKitHookDispatch │
        └──────────┬──────────┘                 └──────────┬───────────┘
                   │                                       │
                   │ (RemotePreferences via IPC)           │
                   └───────────────────┬───────────────────┘
                                       │
                                       ▼
                           ┌───────────────────────┐
                           │ Target Hooked Process │
                           │ (e.g. YouTube/Reddit) │
                           └───────────────────────┘
```

---

## 2. Technology Stack & Operating Environment

* **Host Platform:** Android / Termux `linux-aarch64`
* **Target Environment:** Android 9.0 (API 28) through Android 15 / 16 (SDK 37)
* **Language & Runtime:** Kotlin 2.2.0, Java 21, Android ART Dalvik VM
* **Build System:** Gradle 9.7.1, AGP 9.3.1 via `fast-gradlew` wrapper
* **UI Framework:** Jetpack Compose (BOM `2026.08.00`), Material 3, Navigation Compose
* **Hook Framework:** Modern LibXposed API 102 (`io.github.libxposed:api:102.0.0`, `io.github.libxposed:service:102.0.0`)
* **Bytecode Search:** DexKit 2.3.0 (`org.luckypray:dexkit:2.3.0` + native `libdexkit.so`)
* **Networking:** OkHttp 4.12.0 with streaming SHA-256 digest validation

---

## 3. Key God Nodes & Contracts

| Contract / Entrypoint | Responsibility | Key Invariants |
| :--- | :--- | :--- |
| `CodexMorpheApp` | Module Application context & `XposedServiceHelper` | Binds to `XposedService` and broadcasts framework readiness |
| `MainHook` | `io.github.libxposed.api.XposedModule` | Registered in `META-INF/xposed/java_init.list`; handles `onPackageReady` |
| `ScopeManager` | Dynamic LSPosed scope querying & requests | Checks `mService.scope`; calls `requestScope` to prompt system notification |
| `VersionValidator` | Target app version compatibility engine | Compares installed `versionName` with `compatiblePackages[pkg].versions` |
| `BundleResourceInjector` | Injects AAPT2 `resources.arsc` & drawables | `ResourcesProvider.loadFromApk` (API 30+) or `AssetManager.addAssetPath` |
| `BundleClassLoader` | In-memory MultiDex runtime loader | Maps `classes.dex`, `classes2.dex`, `classes3.dex` into `InMemoryDexClassLoader` |
| `ExtensionClassLoading` | UI framework inflation hooks (from NexAlloy) | Intercepts `LayoutInflater`, `DrawableInflater`, and `Fragment.instantiate` |
| `DexKitHookDispatcher` | Cached fingerprint resolution & method hooking | Binds `before`/`after` hooks using `DexKitBridge` and user preferences |
| `PatchPreferenceManager` | Manages author and per-patch toggles | Excludes `custom-branding`; writes to `mService.getRemotePreferences(pkg)` |

---

## 4. Architectural Invariants & Learned Traps

* **TRAP:** Loading writable DEX files into ART triggers `java.lang.SecurityException: Writable dex file is not allowed` on Android 14+.
  * **FIX:** Always open bundles via read-only `ParcelFileDescriptor` or enforce strict read-only permissions (`chmod 444` / `chmod 555`).
* **TRAP:** Monolithic compile-time submodules (`morphe-patches`) bloating APK and blocking OTA updates.
  * **FIX:** Zero patches are baked into `CodexMorphe.apk`. All bytecode and resources are dynamically loaded from verified bundle APKs.
* **TRAP:** In-memory `CustomBrandingPatch` corrupting Android 12+ adaptive icon caches.
  * **FIX:** `custom-branding` is permanently excluded from the toggle UI and execution dispatcher.
* **TRAP:** Target app crashing on missing host Litho classes during pre-hook initialization.
  * **FIX:** Defer class resolution until target app context is attached in `inContext(param)`.
