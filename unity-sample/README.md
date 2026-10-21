# unity-sample — Using it from Unity (MiRZA / Quest 3 / Galaxy XR)

English | [日本語](README.ja.md)

Scripts for using `unity-bridge` (an Android AAR) from C#, plus steps for integrating it into a Unity project.
No Unity project is included. Copy the scripts into an existing project.

| File | Role | Device |
|---|---|---|
| `Scripts/FoundryLocalBridge.cs` | C# wrapper for `com.example.flxr.unity.FoundryLocalBridge` (a static class plus `FoundryLocalBridgeReceiver`, which receives UnitySendMessage). Events: `StateChanged` / `FinalTranscript` / `ReplyDelta` / `Reply` / `Error` | All |
| `Scripts/FoundryLocalAssistantView.cs` | MonoBehaviour for display. State, download progress, mic button, text questions, and an `あなた / AI` ("You / AI") log (TextMeshPro + uGUI) | All |
| `Scripts/UnityMicrophoneStreamer.cs` | Converts audio from Unity's `Microphone` to 16kHz PCM16 and streams it to the bridge | All (optional) |
| `Scripts/MiRZA/MiRZAMicModeSwitcher.cs` | Switches the microphone mode with MiRZA Library's `SwitchMicrophone`. Compiled only when `FLXR_MIRZA` is defined | MiRZA only |

Minimal code:

```csharp
using Example.FoundryLocalXR;

FoundryLocalBridge.StateChanged += s => Debug.Log($"{s.status} ready={s.ready} {s.progress:P0}");
FoundryLocalBridge.FinalTranscript += t => Debug.Log("You: " + t);
FoundryLocalBridge.Reply += r => Debug.Log("AI: " + r);
FoundryLocalBridge.Init(new AssistantConfig { speechMode = SpeechMode.Whisper, language = "ja" });
// once ready
FoundryLocalBridge.StartMic();      // or StartGlassMic() (MiRZA) / Ask("こんにちは")
```

## Integration steps

1. At the repository root, build the bridge.
   ```powershell
   .\gradlew.bat :unity-bridge:dist
   ```
2. Place the following AARs in your Unity project's `Assets/Plugins/Android/`.
   - `unity-bridge/dist/Plugins/Android/flxr-unity-bridge.aar`
   - `unity-bridge/dist/Plugins/Android/flxr-core.aar`
   - Exactly one runtime: `dist/runtime-embedded/foundry-local-embedded-sdk-0.1.6.aar` (recommended) or `dist/runtime-ipc/foundry-local-ipc-sdk-0.1.6.aar`
   - In the Inspector, confirm that only Android is checked under Platform for each AAR.
3. Copy `Scripts/` to e.g. `Assets/FoundryLocalXR/`. `Scripts/MiRZA/` is not needed for devices other than MiRZA (even if copied, its contents are not compiled unless `FLXR_MIRZA` is defined).
4. Configure **Project Settings > Player > Android**.
   - Other Settings: **Minimum API Level = 33**. The Foundry Local AAR has minSdk 33, so anything lower fails during manifest merging.
   - Scripting Backend = IL2CPP, Target Architectures = **ARM64 only**
   - Publishing Settings > Build: turn **Custom Main Gradle Template** ON, and paste the lines from `dist/unity-gradle/mainTemplate.dependencies.gradle` inside `dependencies { }` in the generated `Assets/Plugins/Android/mainTemplate.gradle`, just before `**DEPS**`.
     ```gradle
     implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.4.10'
     implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2'
     // only for the ipc runtime
     // implementation 'androidx.core:core:1.13.1'
     ```
   - If the build reports `2 files found with path 'lib/arm64-v8a/libc++_shared.so'`, turn **Custom Launcher Gradle Template** ON and paste `dist/unity-gradle/launcherTemplate.packaging.gradle` (Unity 6 uses `packaging { jniLibs { pickFirsts ... } }`, 2022.3 uses `packagingOptions { pickFirst ... }`).
   - Minify (R8) can stay OFF. Even if turned ON, the consumer rules included in the AARs keep the required classes.
5. Build the UI in your scene. Put TMP_Text (state / partial result / log), a Button (mic), a TMP_InputField and Button (question), and a Slider (progress) on a Canvas, then add `FoundryLocalAssistantView` to an empty GameObject and wire up the references. All of them are optional; unconnected ones are ignored.
6. Set speechMode / language / chatEnabled in `FoundryLocalAssistantView.config`. The first run downloads the models, so connect to Wi-Fi (several GB for Whisper small and Qwen2.5 1.5B. From the second run on, it only loads from the cache).

It does not run in the Unity Editor (it shows `Editor: 非対応` ("Editor: not supported")). Test on a real device.

### Microphone input

| `MicSource` | Description | When to use |
|---|---|---|
| `DeviceMic` | The bridge records with Android's `AudioRecord` (`VOICE_RECOGNITION`). Same as core's `micPcm()` | Default. On MiRZA, this is the phone's microphone |
| `MiRZAGlassMic` | The bridge records after calling `setPreferredDevice` on the `TYPE_IP` input device (the MiRZA glasses microphone) | Using the glasses microphone on MiRZA |
| `UnityMicrophone` | Records with Unity's `Microphone` and streams it via `pushPcm` | When Unity is already using the microphone, or when you want to choose the device |

All of them feed core as 16kHz / mono / s16le. By calling `StartPcmInput()` → `PushPcm(bytes)` … → `StopPcmInput()` directly, you can also stream audio recorded by MiRZA Library or received over the network.

## MiRZA (AQUOS R9 / R9 pro / R10)

Intended setup: Unity 2022.3 LTS (2022.3.62f2 or later) + Snapdragon Spaces SDK 1.0.4 + OpenXR Plugin 1.15.0-pre.1 + Dual Render Fusion + MiRZA Library 1.2.1

- **Inference runs on the phone.** The APK is installed on the phone, and rendering to the glasses is done with Dual Render Fusion. The models are also downloaded to the phone.
- Minimum API Level 33 matches the MiRZA Library requirement as well.
- **Obtain the microphone permission before OpenXR starts.** With Dual Render Fusion, showing the permission dialog after OpenXR has started can cause the microphone to malfunction.
  - `FoundryLocalAssistantView.requestMicPermissionOnStart` assumes it is placed in the first scene (before the Dynamic OpenXR Loader starts XR).
  - If your project already handles permissions with the Spaces Permission Helper or the MiRZA Library samples, leave it to them and turn `requestMicPermissionOnStart` OFF.
- **UI placement**: It is easier to use if buttons and input fields go on a Canvas on the phone screen (Spaces Host View), and the transcription and reply display go on a Canvas on the glasses.
  Place only one `FoundryLocalAssistantView` per scene (it calls `Init` in `Start`, so with two, the later `Init` recreates the earlier one). To also show content on the glasses, subscribe to events such as `FoundryLocalBridge.FinalTranscript` / `Reply` from your own script.
- **Glasses microphone**: Select `MicSource = MiRZAGlassMic`. If it is not found, the list of input devices appears in `OnBridgeState.error` (`type=20` is `TYPE_IP`).
  Switch the microphone mode (omnidirectional / wearer only / ambient / front) with `MiRZAMicModeSwitcher` before starting to listen.
  1. Add `FLXR_MIRZA` to Player Settings > Scripting Define Symbols.
  2. Add `MiRZAMicModeSwitcher` to an empty GameObject and choose a `mode`.
- **Do not use it at the same time as MiRZA Library recording.** MiRZA Library recording and `StartGlassMic()` compete for the same microphone. If you want to record with MiRZA Library, stream that data into `FoundryLocalBridge.PushPcm()` (record at 16kHz / mono / 16bit).
- **Wireless disconnection**: Even if the connection to the glasses drops, inference and the models on the phone keep running. It is safest to call `StopMic()` on disconnect and `StartGlassMic()` again on reconnect via Spaces Lifecycle Events (because the glasses microphone device disappears).
- **Heat and battery**: Inference also heats up the phone, so keep it charging during long sessions.

## Quest 3 / Galaxy XR Unity apps

Neither the bridge nor the C# scripts use MiRZA-specific code, so the same files work (do not select `MiRZAGlassMic`, and do not define `FLXR_MIRZA`).

### Quest 3 (Meta XR SDK / Unity 6)

- Use the embedded runtime (there is no Play Store, so the Foundry Local app cannot be installed).
- Raise Minimum API Level to 33. Even if Meta's Project Setup Tool warns about the recommended value, prioritize the Foundry Local requirement. Requires Horizon OS v76 or later (Android 14).
- Check that `RECORD_AUDIO` remains in the final manifest (if you restrict permissions in the Meta XR settings). The permission dialog appears inside the headset.
- The microphone works with `DeviceMic` (the headset microphone). If the Meta Voice SDK or similar is already using the microphone, share it via `UnityMicrophone` or `PushPcm`.

### Galaxy XR (Android XR / Unity 6)

- Raise Minimum API Level to 33 (if left lower, manifest merging fails).
- Both embedded and ipc work (because the Play Store is available). For ipc, install the Foundry Local app first.
- Application Entry can stay as GameActivity.
- On Unity 6, 1.17.0 can also be used for `androidx.core`.

## Troubleshooting

- Check the logs with `adb logcat -s FLXR FLXR-Unity Unity`. The initialization sequence is `ランタイム初期化中` ("Initializing runtime") → `ダウンロード中` ("Downloading") → `読み込み中` ("Loading") → `準備完了` ("Ready").
- If the build fails, check whether the Gradle errors include `checkReleaseAarMetadata` / `Duplicate class` / `2 files found ... libc++_shared.so`.
- If `MiRZAGlassMic` fails, check the input devices in the output of `describeInputDevices()` (the `error` field or `FLXR-Unity` in logcat).
