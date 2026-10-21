# unity-bridge — Bridge for Unity (Android library)

English | [日本語](README.ja.md)

Exposes the `VoiceAssistant` from `core` (speech-to-text + chat) as a static API callable from Unity C# via `AndroidJavaClass`. Results are returned to C# through `UnityPlayer.UnitySendMessage`.
It was built for MiRZA (Unity on AQUOS / Snapdragon Spaces), but the device-specific parts are isolated in `com.example.flxr.unity.mirza`, so it also works as-is in Unity apps for Quest 3 / Galaxy XR.

- Package: `com.example.flxr.unity`
- minSdk 33 (required by Foundry Local for Android and MiRZA Library)
- No dependency on Unity's `classes.jar` (`UnityPlayer` is resolved via reflection). Hosts other than Unity can receive messages with `setListener(BridgeListener)`.

For the C# wrapper and integration steps, see [`../unity-sample/README.md`](../unity-sample/README.md).

## Build

```powershell
.\gradlew.bat :unity-bridge:dist
```

The following files are collected in `unity-bridge/dist/`.

```
dist/
├─ Plugins/Android/
│   ├─ flxr-unity-bridge.aar     ← required (this module)
│   └─ flxr-core.aar             ← required (:core)
├─ runtime-embedded/foundry-local-embedded-sdk-0.1.6.aar   ← pick one
├─ runtime-ipc/foundry-local-ipc-sdk-0.1.6.aar             ←
├─ unity-gradle/
│   ├─ mainTemplate.dependencies.gradle    ← Maven dependencies to paste into mainTemplate.gradle
│   └─ launcherTemplate.packaging.gradle   ← only needed if libc++_shared.so is duplicated
└─ foundry-local-android-sdk-license-0.1.6.txt
```

Place the two files in `Plugins/Android/` plus one runtime AAR in Unity's `Assets/Plugins/Android/`.

## Integrating with Unity

Multiple AARs are placed side by side, and Maven dependencies are resolved in `mainTemplate.gradle`. A single combined AAR (fat AAR) is not used.

| Approach | Adopted | Reason |
|---|---|---|
| Combine bridge, core, and Foundry Local into one AAR | No | The Foundry Local AAR is signed and distributed by Microsoft and is used unmodified. Repackaging would mean rebuilding every time the SDK is updated |
| Bundle core inside bridge | No | core is shared with the other apps (app-panel / Rokid / Even G2). Keeping it as a separate AAR means it can simply be swapped |
| **Place bridge, core, and runtime as separate AARs** | **Yes** | Every AAR is used unmodified. Switching between embedded and ipc is just a matter of swapping the runtime AAR |
| Resolve transitive dependencies with EDM4U | Optional | The default is pasting a few lines into the Custom Main Gradle Template. Projects already using EDM4U can put the same coordinates in Dependencies.xml instead |

### embedded or ipc

| Runtime | AAR | Prerequisites | Best for |
|---|---|---|---|
| embedded | `foundry-local-embedded-sdk-0.1.6.aar` (46.6MB, includes arm64 native libraries) | None (the inference runtime is bundled in the APK) | Quest 3 (no Play Store). Works reliably on other devices too |
| ipc | `foundry-local-ipc-sdk-0.1.6.aar` (0.44MB) | The Foundry Local app (`com.microsoft.foundrylocal.app`) from the Play Store must be installed on the device. Also requires the `androidx.core` dependency | AQUOS (MiRZA) / Galaxy XR when you want a smaller APK |

Do not include both runtimes at the same time. If both are present, embedded is used.

### Maven dependencies required on the Unity side (mainTemplate.gradle)

When AARs are added as plain files there is no POM, so Unity cannot resolve transitive dependencies. Add the following lines manually (same content as `dist/unity-gradle/mainTemplate.dependencies.gradle`).

```gradle
dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar'])
    // ↓ add
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.4.10'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2'
    // only when using the ipc runtime
    // implementation 'androidx.core:core:1.13.1'
**DEPS**}
```

- `kotlin-stdlib:2.4.10`: bridge and core are compiled with this Kotlin version. An older version may cause `NoSuchMethodError`.
- `kotlinx-coroutines-android:1.10.2`: an `api` dependency of core. The Foundry Local SDK API is also coroutine-based.
- `androidx.core`: only needed for the ipc runtime. Set to 1.13.1 so it also works with Unity 2022.3 (AGP 7.4.2).
- bridge and core are compiled with Java 17.

### minCompileSdk

AGP 9 writes the library's `compileSdk` (37 here) into the AAR's `minCompileSdk`. Unity's compileSdk is 34–36, so as-is the build fails at `checkReleaseAarMetadata`.
For this reason, the build.gradle.kts of bridge and core sets `aarMetadata { minCompileSdk = 33 }`.

### Verified build environments

An app using the dist AARs (embedded) and the Maven dependencies above has been confirmed to build successfully with R8 enabled, using the Gradle setup bundled with Unity.

| Unity | Gradle / AGP / JDK | compileSdk | Notes |
|---|---|---|---|
| 2022.3 | Gradle 7.5.1 / AGP 7.4.2 / OpenJDK 11 | 34 | During lint, an exception is thrown because the `androidx annotation-experimental` lint jar cannot be read, but it does not affect the APK |
| 6000.3 | Gradle 8.13 / AGP 8.10.0 / OpenJDK 17 | 36 | Uses `androidx.core:core:1.17.0` and `packaging.jniLibs.pickFirsts`. R8 warns that it "cannot parse Kotlin metadata", but this has no effect since Kotlin reflection is not used. The warning does not appear with Minify OFF (the default) |

- Even with R8 enabled, the consumer-rules keep `FoundryLocalBridge` / `VoiceAssistant` / `FoundryLocalManager`.
- With Unity's default settings (`abiFilters 'arm64-v8a'`), x86_64 native libraries are not included in the APK.

## API (`com.example.flxr.unity.FoundryLocalBridge`, all `@JvmStatic`)

| Method | Description |
|---|---|
| `boolean init(Activity, String gameObjectName, String configJson)` | Creates the `VoiceAssistant` and starts downloading and loading the models. Calling it again recreates it (e.g. to switch speechMode) |
| `boolean startMic()` | Starts listening with the device's default microphone (core's `micPcm()`, `VOICE_RECOGNITION`) |
| `boolean startGlassMic()` | MiRZA only. Listens via the glasses microphone (`AudioDeviceInfo.TYPE_IP`) using `setPreferredDevice` |
| `boolean startPcmInput()` / `void pushPcm(byte[])` / `void stopPcmInput()` | Feeds 16kHz/mono/s16le PCM from C#. `stopPcmInput` also transcribes an in-progress utterance to the end |
| `void stopMic()` | Stops immediately, regardless of input |
| `void ask(String)` | Asks a question as text |
| `boolean isReady()` / `isListening()` / `hasMicPermission()` | State |
| `String describeInputDevices()` | List of input devices (for logging. The MiRZA microphone is `type=20`) |
| `void setListener(BridgeListener)` | For hosts other than Unity. Receives messages instead of `UnitySendMessage` |
| `void release()` | Unloads the models and closes the runtime |

`configJson` (all fields optional; omitted fields use the defaults of core's `AssistantConfig`):

```json
{ "speechMode": "whisper", "language": "ja", "chatEnabled": true,
  "systemPrompt": "...", "whisperModels": ["openai-whisper-small"], "streamingModels": [], "chatModels": [] }
```

`speechMode`: `"whisper"` (supports Japanese, per utterance) / `"streaming_en"` (Nemotron, English streaming)

### Messages to C# (`UnitySendMessage(gameObjectName, method, json)`)

| method | JSON |
|---|---|
| `OnBridgeState` | `{"status","progress"(0..1, -1 when absent),"ready","listening","partial","error","models"}`. Does not include the conversation log |
| `OnBridgeTranscript` | `{"text"}` A finalized utterance (including question text from `ask`) |
| `OnBridgeReplyDelta` | `{"text"}` A streaming fragment of the reply |
| `OnBridgeReply` | `{"text"}` The finalized reply |
| `OnBridgeError` | `{"code","message"}`. code is `not_initialized` / `not_ready` / `busy` / `no_permission` / `bad_config`. Failures during inference go to `OnBridgeState.error` |

`UnitySendMessage` may be called from any thread (Unity queues it and invokes it on the main thread on the next frame).

## Files

| Path | Contents |
|---|---|
| `src/main/java/com/example/flxr/unity/FoundryLocalBridge.kt` | The static API itself (device-independent) |
| `src/main/java/com/example/flxr/unity/UnityMessenger.kt` | Reflection-based `UnitySendMessage` call and `BridgeListener` |
| `src/main/java/com/example/flxr/unity/BridgeJson.kt` | configJson → `AssistantConfig`, state → JSON |
| `src/main/java/com/example/flxr/unity/mirza/GlassMic.kt` | MiRZA-specific: Flow of PCM from the `TYPE_IP` microphone |
| `consumer-rules.pro` | R8 keep rules (because C# calls by name) |
| `unity-gradle/*.gradle` | Snippets to paste into Unity's Custom Gradle Templates |
