---
synced_commit: 1d9392f
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
| `BundleBridge` | Extension method reflection dispatcher | Dispatches calls to bundle extensions, resolving Kotlin singleton `INSTANCE` objects and context injection |
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
* **TRAP:** AAPT2 default application package ID `0x7f` collisions when injecting bundle resources into host apps via `ResourcesLoader` on Android 11+ (API 30+).
  * **FIX:** Always compile bundle resources with `aapt2 link --shared-lib` (`package id=00`). Android then allocates a non-colliding dynamic runtime package ID (`0x02` or `0x80`), preventing resource type mismatches (`Resources$NotFoundException`).
* **TRAP:** Third-party untrusted app SELinux sandbox blocks direct filesystem reads of `/data/adb/...`.
  * **FIX:** Never probe `/data/adb/` directly from the hooked process. Always query available bundles via LibXposed API 102 `listRemoteFiles()` and stream them via `openRemoteFile()`, routing through LSPosed daemon's privileged IPC bridge.
* **TRAP:** Hardcoding author or source package names in the client engine violates engine-bundle decoupling and breaks multi-author extensibility.
  * **FIX:** Keep `CodexMorphe` 100% vendor-agnostic. All initializers (`SettingsEntry#onApplicationCreate`, `AddOnApi#initialize`, `Utils#setContext`) and resource fallback targets (`ResourceUtils`) are discovered by `codexmorphe-builder` and passed via `manifest.entrypoints` and `manifest.resourceFallbacks`. The client engine dynamically resolves and invokes them at runtime with zero hardcoded author names.
* **TRAP:** Unconstrained / wildcard DexKit queries matching entire classes cause fatal ART crashes (e.g. YouTube NPE in `aiat.e()`) and flood logs.
  * **FIX:** Strict query guards in `DexKitHookDispatcher` and discriminator validation in `recipe_builder.py` reject any fingerprint query lacking method name, strings, or literals.
* **TRAP:** Target app Activities failing to inflate Morphe layouts or custom views with `ResourceNotFoundException`.
  * **FIX:** Register `Application.ActivityLifecycleCallbacks` (`onActivityCreated`, `onActivityResumed`) in `BundleResourceInjector` to dynamically attach bundle resources to Activity contexts with a `WebView` guard.
* **TRAP:** Main-thread bytecode scanning via DexKit during `Application.onCreate` blocks app startup for 10–20s and triggers ANRs.
  * **FIX:** `DexKitCacheBridge` caches resolved `Method`/`Constructor` signatures in `SharedPreferences` keyed by target app package and `versionCode` for instant reflection on subsequent boots.
* **TRAP:** Intercepting Activity lifecycle hooks (`GoogleApiActivity.onCreate`) via `METHOD_REPLACE` without calling `super.onCreate` crashes Android with `SuperNotCalledException`.
  * **FIX:** Always classify lifecycle injection methods as `METHOD_AFTER` in the transpiler and dispatcher so `chain.proceed()` runs first before initializing settings UI.
* **TRAP:** Target apps (like Instagram) omitting `android:name` in `<application>` manifest cause `param.applicationInfo.className` to be null or generic, missing the real application subclass.
  * **FIX:** Query multiple candidate application classes (`com.instagram.app.InstagramAppShell`, `com.google.android.apps.youtube.app.YouTubeApplication`, `param.applicationInfo.className`, and `Application`) with an `AtomicBoolean` single-execution guard.
* **TRAP:** `RETURN_TRANSFORM` casting an Object or String extension result to a primitive return type (e.g. `Boolean` or `int`), causing ART to throw `java.lang.ClassCastException`.
  * **FIX:** Check `isBoxedTypeOf(returnType, transformed)` before replacing result. If incompatible, log warning and preserve `originalResult`.
* **TRAP:** Force-updating bundle releases changes SHA-256 checksums, causing stale local cache conflicts.
  * **FIX:** `BundleDownloader.isBundleUpToDate()` compares local SHA-256 against remote manifest hashes, enabling the UI to surface "Update Available" badges and atomic replacements via `.tmp` staging files.
* **TRAP:** Target app APKs bundling stripped stubs of shared runtime libraries (e.g. YouTube's `classes3.dex` defining `com.google.protobuf.ExtensionRegistryLite` without `getEmptyRegistry()`) shadow complete library implementations in the bundle due to parent-first ClassLoader delegation, causing `NoSuchMethodError`.
  * **FIX:** Wrap the host parent ClassLoader with a `BundleParentClassLoader` that intercepts requests for shadowed runtime packages (e.g. `com.google.protobuf.ExtensionRegistry`) and throws `ClassNotFoundException`, forcing `InMemoryDexClassLoader` to resolve the full implementation from its own DEX containers.
* **TRAP:** Target app custom view inflation frameworks (e.g. Meta / Instagram `PreinflateManager`) calling `viewStub.getLayoutInflater().getContext()` crash with `NullPointerException` on cache misses because Android's `ViewStub.getLayoutInflater()` returns `null` by default if `setLayoutInflater()` was not explicitly called.
  * **FIX:** In `ExtensionClassLoading`, hook `ViewStub.getLayoutInflater()`. If `result == null`, fall back to `LayoutInflater.from(viewStub.context)`.
* **TRAP:** In `REPLACE` / `METHOD_REPLACE`, extension methods returning `null` or throwing for non-void host methods corrupt host dependency injection (e.g. YouTube `akrh.e()` NPE attempting to read `bfco.aN` on a null reference).
  * **FIX:** `DexKitHookDispatcher` checks if `res == null` for non-void methods or if executable is a `Constructor<*>`, falling back to `chain.proceed()` instead of returning `null`.
* **TRAP:** Invoking extension entrypoints or methods on Kotlin singleton classes (e.g. `object SettingsEntry`) via `Method.invoke(null, ...)` throws `NullPointerException` when `@JvmStatic` is omitted.
  * **FIX:** `BundleBridge` checks `Modifier.isStatic(method.modifiers)`; if non-static, reflects the declared `INSTANCE` field and passes the singleton instance to `method.invoke(instance, *args)`.
* **TRAP:** `ARG_REWRITE` hooks on instance or Litho component methods expecting `(thisObject, originalArg)` fail or rewrite incorrectly when only `originalArg` is passed to the extension.
  * **FIX:** `DexKitHookDispatcher` prepends `chain.thisObject` to rewrite arguments, verifies type compatibility via `isBoxedTypeOf`, and falls back to `chain.args`.
