# unity-sample — Unity で使う（MiRZA / Quest 3 / Galaxy XR）

[English](README.md) | 日本語

`unity-bridge`（Android の AAR）を C# から使うためのスクリプトと、Unity プロジェクトへの組み込み手順です。
Unity プロジェクトそのものは含めていません。スクリプトを既存のプロジェクトにコピーして使います。

| ファイル | 役割 | デバイス |
|---|---|---|
| `Scripts/FoundryLocalBridge.cs` | `com.example.flxr.unity.FoundryLocalBridge` の C# ラッパー（静的クラスと、UnitySendMessage を受け取る `FoundryLocalBridgeReceiver`）。イベント：`StateChanged` / `FinalTranscript` / `ReplyDelta` / `Reply` / `Error` | 共通 |
| `Scripts/FoundryLocalAssistantView.cs` | 表示用の MonoBehaviour。状態、ダウンロードの進捗、マイクのボタン、テキストでの質問、「あなた / AI」のログ（TextMeshPro + uGUI） | 共通 |
| `Scripts/UnityMicrophoneStreamer.cs` | Unity の `Microphone` の音声を 16kHz の PCM16 に変換してブリッジに流す | 共通（任意） |
| `Scripts/MiRZA/MiRZAMicModeSwitcher.cs` | MiRZA Library の `SwitchMicrophone` でマイクのモードを切り替える。`FLXR_MIRZA` が定義されているときだけコンパイルされる | MiRZA のみ |

最小のコード：

```csharp
using Example.FoundryLocalXR;

FoundryLocalBridge.StateChanged += s => Debug.Log($"{s.status} ready={s.ready} {s.progress:P0}");
FoundryLocalBridge.FinalTranscript += t => Debug.Log("あなた: " + t);
FoundryLocalBridge.Reply += r => Debug.Log("AI: " + r);
FoundryLocalBridge.Init(new AssistantConfig { speechMode = SpeechMode.Whisper, language = "ja" });
// ready になったら
FoundryLocalBridge.StartMic();      // または StartGlassMic()（MiRZA）/ Ask("こんにちは")
```

## 組み込み手順

1. リポジトリのルートで、ブリッジをビルドします。
   ```powershell
   .\gradlew.bat :unity-bridge:dist
   ```
2. Unity プロジェクトの `Assets/Plugins/Android/` に次の AAR を置きます。
   - `unity-bridge/dist/Plugins/Android/flxr-unity-bridge.aar`
   - `unity-bridge/dist/Plugins/Android/flxr-core.aar`
   - ランタイムを 1 つだけ：`dist/runtime-embedded/foundry-local-embedded-sdk-0.1.6.aar`（推奨）または `dist/runtime-ipc/foundry-local-ipc-sdk-0.1.6.aar`
   - Inspector で、各 AAR の Platform が Android だけにチェックされていることを確認します。
3. `Scripts/` を `Assets/FoundryLocalXR/` などにコピーします。MiRZA 以外では `Scripts/MiRZA/` は不要です（コピーしても、`FLXR_MIRZA` が無ければ中身はコンパイルされません）。
4. **Project Settings > Player > Android** を設定します。
   - Other Settings：**Minimum API Level = 33**。Foundry Local の AAR が minSdk 33 なので、これより低いと manifest のマージで失敗します。
   - Scripting Backend = IL2CPP、Target Architectures = **ARM64 のみ**
   - Publishing Settings > Build：**Custom Main Gradle Template** を ON にし、生成された `Assets/Plugins/Android/mainTemplate.gradle` の `dependencies { }` の中、`**DEPS**` の直前に、`dist/unity-gradle/mainTemplate.dependencies.gradle` の行を貼ります。
     ```gradle
     implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.4.10'
     implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2'
     // ipc ランタイムのときだけ
     // implementation 'androidx.core:core:1.13.1'
     ```
   - ビルドで `2 files found with path 'lib/arm64-v8a/libc++_shared.so'` が出た場合は、**Custom Launcher Gradle Template** を ON にして `dist/unity-gradle/launcherTemplate.packaging.gradle` を貼ります（Unity 6 は `packaging { jniLibs { pickFirsts ... } }`、2022.3 は `packagingOptions { pickFirst ... }`）。
   - Minify（R8）は OFF のままで構いません。ON にしても、AAR に含まれる consumer rules で必要なクラスは残ります。
5. シーンに UI を作ります。Canvas に TMP_Text（状態 / 途中経過 / ログ）、Button（マイク）、TMP_InputField と Button（質問）、Slider（進捗）を置き、空の GameObject に `FoundryLocalAssistantView` を付けて参照をつなぎます。どれも任意で、つながっていないものは無視されます。
6. `FoundryLocalAssistantView.config` で speechMode / language / chatEnabled を設定します。初回はモデルをダウンロードするので、Wi-Fi に接続してください（Whisper small と Qwen2.5 1.5B で数 GB。2 回目からはキャッシュを読み込むだけです）。

Unity Editor の上では動きません（`Editor: 非対応` と表示されます）。動作は実機で確認してください。

### マイクの入力

| `MicSource` | 内容 | 使いどころ |
|---|---|---|
| `DeviceMic` | ブリッジが Android の `AudioRecord`（`VOICE_RECOGNITION`）で録音する。core の `micPcm()` と同じ | 初期値。MiRZA ではスマホのマイク |
| `MiRZAGlassMic` | ブリッジが `TYPE_IP` の入力デバイス（MiRZA のグラスのマイク）に `setPreferredDevice` して録音する | MiRZA でグラスのマイクを使う |
| `UnityMicrophone` | Unity の `Microphone` で録音して `pushPcm` で流す | Unity 側ですでにマイクを使っている場合や、デバイスを選びたい場合 |

どれも 16kHz / mono / s16le で core に入ります。`StartPcmInput()` → `PushPcm(bytes)` … → `StopPcmInput()` を直接呼べば、MiRZA Library の録音やネットワーク越しの音声も流せます。

## MiRZA（AQUOS R9 / R9 pro / R10）

想定している構成：Unity 2022.3 LTS（2022.3.62f2 以降）+ Snapdragon Spaces SDK 1.0.4 + OpenXR Plugin 1.15.0-pre.1 + Dual Render Fusion + MiRZA Library 1.2.1

- **推論はスマホで行います。** APK はスマホに入れ、グラスには Dual Render Fusion で描画します。モデルもスマホにダウンロードされます。
- Minimum API Level 33 は MiRZA Library の要件とも同じです。
- **マイクの権限は OpenXR が起動する前に取ってください。** Dual Render Fusion では、OpenXR が動き出した後に権限のダイアログを出すと、マイクがうまく動かないことがあります。
  - `FoundryLocalAssistantView.requestMicPermissionOnStart` は、最初のシーン（Dynamic OpenXR Loader が XR を始める前）に置く前提です。
  - プロジェクトにすでに Spaces Permission Helper や MiRZA Library のサンプルの権限処理がある場合は、そちらに任せて `requestMicPermissionOnStart` を OFF にしてください。
- **UI の置き場所**：ボタンや入力欄はスマホの画面（Spaces Host View）の Canvas、文字起こしと返事の表示はグラス側の Canvas に分けると使いやすくなります。
  `FoundryLocalAssistantView` は 1 つのシーンに 1 つだけ置いてください（`Start` で `Init` を呼ぶため、2 つ置くと後の `Init` が前のものを作り直します）。グラス側にも表示したい場合は、自分のスクリプトで `FoundryLocalBridge.FinalTranscript` / `Reply` などのイベントを購読してください。
- **グラスのマイク**：`MicSource = MiRZAGlassMic` を選びます。見つからない場合は、`OnBridgeState.error` に入力デバイスの一覧が出ます（`type=20` が `TYPE_IP`）。
  マイクのモード（全指向 / 装着者のみ / 環境音 / 前方）は、聞き取りを始める前に `MiRZAMicModeSwitcher` で切り替えます。
  1. Player Settings > Scripting Define Symbols に `FLXR_MIRZA` を追加します。
  2. 空の GameObject に `MiRZAMicModeSwitcher` を付けて、`mode` を選びます。
- **MiRZA Library の録音と同時に使わないでください。** MiRZA Library の録音と `StartGlassMic()` は同じマイクを取り合います。MiRZA Library で録音したい場合は、そのデータを `FoundryLocalBridge.PushPcm()` に流してください（16kHz / mono / 16bit で録音します）。
- **無線の切断**：グラスとの接続が切れても、スマホ側の推論とモデルは動き続けます。Spaces Lifecycle Events の切断で `StopMic()`、復帰で `StartGlassMic()` を呼び直すのが安全です（グラスのマイクのデバイスが消えるため）。
- **発熱と電池**：推論でスマホも熱くなるので、長時間使う場合は給電しながら使ってください。

## Quest 3 / Galaxy XR の Unity アプリ

ブリッジも C# のスクリプトも MiRZA 固有のコードは使わないので、同じファイルで動きます（`MiRZAGlassMic` を選ばず、`FLXR_MIRZA` を定義しないでください）。

### Quest 3（Meta XR SDK / Unity 6）

- ランタイムは embedded を使います（Play ストアが無く、Foundry Local アプリを入れられないため）。
- Minimum API Level を 33 に上げます。Meta の Project Setup Tool が推奨値について警告を出しても、Foundry Local の要件を優先してください。Horizon OS v76 以降（Android 14）が前提です。
- 最終的な manifest に `RECORD_AUDIO` が残っているか確認してください（Meta XR の設定で権限を絞っている場合）。権限のダイアログはヘッドセットの中に出ます。
- マイクは `DeviceMic`（ヘッドセットのマイク）で使えます。Meta Voice SDK などがすでにマイクを使っている場合は、`UnityMicrophone` か `PushPcm` で共有してください。

### Galaxy XR（Android XR / Unity 6）

- Minimum API Level を 33 に上げてください（低いままだと manifest のマージで失敗します）。
- embedded と ipc のどちらも使えます（Play ストアがあるため）。ipc の場合は、先に Foundry Local アプリを入れてください。
- Application Entry は GameActivity のままで構いません。
- Unity 6 では、`androidx.core` に 1.17.0 も使えます。

## うまく動かないとき

- ログは `adb logcat -s FLXR FLXR-Unity Unity` で確認できます。初期化の流れは「ランタイム初期化中」→「ダウンロード中」→「読み込み中」→「準備完了」です。
- ビルドに失敗する場合は、Gradle のエラーに `checkReleaseAarMetadata` / `Duplicate class` / `2 files found ... libc++_shared.so` が無いかを確認してください。
- `MiRZAGlassMic` で失敗する場合は、`describeInputDevices()` の出力（`error` 欄または logcat の `FLXR-Unity`）で入力デバイスを確認してください。
