# unity-bridge — Unity 向けブリッジ（Android ライブラリ）

[English](README.md) | 日本語

`core` の `VoiceAssistant`（文字起こし＋会話）を、Unity の C# から `AndroidJavaClass` で呼べる静的 API にしたものです。結果は `UnityPlayer.UnitySendMessage` で C# に返します。
MiRZA（AQUOS 上の Unity / Snapdragon Spaces）向けに作りましたが、デバイス固有の部分は `com.example.flxr.unity.mirza` に分けてあり、Quest 3 / Galaxy XR の Unity アプリでもそのまま使えます。

- パッケージ：`com.example.flxr.unity`
- minSdk 33（Foundry Local for Android と MiRZA Library の要件）
- Unity の `classes.jar` には依存しません（`UnityPlayer` はリフレクションで解決します）。Unity 以外のホストでは `setListener(BridgeListener)` でメッセージを受け取れます。

C# のラッパーと組み込み手順は [`../unity-sample/README.ja.md`](../unity-sample/README.ja.md) を参照してください。

## ビルド

```powershell
.\gradlew.bat :unity-bridge:dist
```

`unity-bridge/dist/` に次のファイルがまとまります。

```
dist/
├─ Plugins/Android/
│   ├─ flxr-unity-bridge.aar     ← 必須（このモジュール）
│   └─ flxr-core.aar             ← 必須（:core）
├─ runtime-embedded/foundry-local-embedded-sdk-0.1.6.aar   ← どちらか 1 つ
├─ runtime-ipc/foundry-local-ipc-sdk-0.1.6.aar             ←
├─ unity-gradle/
│   ├─ mainTemplate.dependencies.gradle    ← mainTemplate.gradle に貼る Maven の依存
│   └─ launcherTemplate.packaging.gradle   ← libc++_shared.so が重複したときだけ使う
└─ foundry-local-android-sdk-license-0.1.6.txt
```

`Plugins/Android/` の 2 つと、ランタイムの AAR 1 つを、Unity の `Assets/Plugins/Android/` に置きます。

## Unity への組み込み方

複数の AAR を並べ、Maven の依存は `mainTemplate.gradle` で解決する方式にしています。1 つの AAR にまとめる（fat AAR）方式は採っていません。

| 方式 | 採否 | 理由 |
|---|---|---|
| bridge・core・Foundry Local を 1 つの AAR にまとめる | 採らない | Foundry Local の AAR は Microsoft が署名して配布しているもので、手を加えずに使います。まとめ直すと SDK を更新するたびに作り直しになります |
| bridge に core を同梱する | 採らない | core はほかのアプリ（app-panel / Rokid / Even G2）と共通です。別の AAR にしておけば差し替えだけで済みます |
| **bridge・core・ランタイムを別の AAR で並べる** | **採用** | どの AAR も手を加えずに使えます。ランタイムは AAR の差し替えだけで embedded と ipc を切り替えられます |
| 推移的な依存を EDM4U で解決する | 任意 | Custom Main Gradle Template に数行貼る方式を基本にしました。EDM4U を使っているプロジェクトなら、Dependencies.xml に同じ座標を書いても構いません |

### embedded か ipc か

| ランタイム | AAR | 前提 | 向いている場面 |
|---|---|---|---|
| embedded | `foundry-local-embedded-sdk-0.1.6.aar`（46.6MB、arm64 のネイティブライブラリ込み） | なし（APK に推論ランタイムを同梱） | Quest 3（Play ストアが無い）。ほかの端末でも確実に動く |
| ipc | `foundry-local-ipc-sdk-0.1.6.aar`（0.44MB） | 端末に Play ストアの Foundry Local アプリ（`com.microsoft.foundrylocal.app`）が必要。`androidx.core` の依存も必要 | AQUOS（MiRZA）/ Galaxy XR で APK を小さくしたい場合 |

2 つのランタイムを同時に置かないでください。両方あると embedded が使われます。

### Unity 側で必要な Maven の依存（mainTemplate.gradle）

AAR をファイルとして置くと POM が無いため、Unity は推移的な依存を解決できません。次の行を手で追加します（`dist/unity-gradle/mainTemplate.dependencies.gradle` と同じ内容です）。

```gradle
dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar'])
    // ↓ 追加
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.4.10'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2'
    // ipc ランタイムを使う場合のみ
    // implementation 'androidx.core:core:1.13.1'
**DEPS**}
```

- `kotlin-stdlib:2.4.10`：bridge と core はこの版の Kotlin でコンパイルしています。古い版にすると `NoSuchMethodError` になる可能性があります。
- `kotlinx-coroutines-android:1.10.2`：core の `api` 依存です。Foundry Local SDK の API もコルーチンベースです。
- `androidx.core`：ipc ランタイムのときだけ必要です。Unity 2022.3（AGP 7.4.2）でも使える 1.13.1 にしています。
- bridge と core は Java 17 でコンパイルしています。

### minCompileSdk

AGP 9 は、ライブラリの `compileSdk`（ここでは 37）を AAR の `minCompileSdk` に書き込みます。Unity の compileSdk は 34〜36 なので、そのままでは `checkReleaseAarMetadata` で失敗します。
そのため、bridge と core の build.gradle.kts で `aarMetadata { minCompileSdk = 33 }` を設定しています。

### 確認したビルド環境

dist の AAR（embedded）と上記の Maven の依存を使うアプリを、Unity が同梱する Gradle の構成で R8 を有効にしてビルドし、成功することを確認しています。

| Unity | Gradle / AGP / JDK | compileSdk | 補足 |
|---|---|---|---|
| 2022.3 | Gradle 7.5.1 / AGP 7.4.2 / OpenJDK 11 | 34 | lint の実行中に `androidx annotation-experimental` の lint jar を読めない例外が出ますが、APK には影響しません |
| 6000.3 | Gradle 8.13 / AGP 8.10.0 / OpenJDK 17 | 36 | `androidx.core:core:1.17.0` と `packaging.jniLibs.pickFirsts` を使用。R8 が「Kotlin metadata を parse できない」という警告を出しますが、Kotlin のリフレクションは使わないので影響はありません。Minify を OFF（初期設定）にすれば出ません |

- R8 を有効にしても、consumer-rules により `FoundryLocalBridge` / `VoiceAssistant` / `FoundryLocalManager` は残ります。
- Unity の初期設定（`abiFilters 'arm64-v8a'`）では、x86_64 のネイティブライブラリは APK に入りません。

## API（`com.example.flxr.unity.FoundryLocalBridge`、すべて `@JvmStatic`）

| メソッド | 説明 |
|---|---|
| `boolean init(Activity, String gameObjectName, String configJson)` | `VoiceAssistant` を作り、モデルのダウンロードと読み込みを始める。もう一度呼ぶと作り直す（speechMode の切り替えなど） |
| `boolean startMic()` | 端末の既定のマイク（core の `micPcm()`、`VOICE_RECOGNITION`）で聞き取りを始める |
| `boolean startGlassMic()` | MiRZA 専用。グラスのマイク（`AudioDeviceInfo.TYPE_IP`）を `setPreferredDevice` して聞き取る |
| `boolean startPcmInput()` / `void pushPcm(byte[])` / `void stopPcmInput()` | C# から 16kHz/mono/s16le の PCM を流し込む。`stopPcmInput` は話している途中の発話も最後まで文字起こしする |
| `void stopMic()` | どの入力でもすぐに止める |
| `void ask(String)` | テキストで質問する |
| `boolean isReady()` / `isListening()` / `hasMicPermission()` | 状態 |
| `String describeInputDevices()` | 入力デバイスの一覧（ログ用。MiRZA のマイクは `type=20`） |
| `void setListener(BridgeListener)` | Unity 以外のホスト向け。`UnitySendMessage` の代わりにメッセージを受け取る |
| `void release()` | モデルをアンロードし、ランタイムを閉じる |

`configJson`（すべて省略可。省略した項目は core の `AssistantConfig` の初期値）：

```json
{ "speechMode": "whisper", "language": "ja", "chatEnabled": true,
  "systemPrompt": "...", "whisperModels": ["openai-whisper-small"], "streamingModels": [], "chatModels": [] }
```

`speechMode`：`"whisper"`（日本語も可、発話ごと）/ `"streaming_en"`（Nemotron、英語のストリーミング）

### C# へのメッセージ（`UnitySendMessage(gameObjectName, method, json)`）

| method | JSON |
|---|---|
| `OnBridgeState` | `{"status","progress"(0..1、無いときは -1),"ready","listening","partial","error","models"}`。会話のログは含まない |
| `OnBridgeTranscript` | `{"text"}` 確定した発話（`ask` の質問文も含む） |
| `OnBridgeReplyDelta` | `{"text"}` 返事のストリーミングの断片 |
| `OnBridgeReply` | `{"text"}` 確定した返事 |
| `OnBridgeError` | `{"code","message"}`。code は `not_initialized` / `not_ready` / `busy` / `no_permission` / `bad_config`。推論の途中の失敗は `OnBridgeState.error` に入る |

`UnitySendMessage` はどのスレッドから呼んでも構いません（Unity がキューに入れ、次のフレームでメインスレッドから呼びます）。

## ファイル

| パス | 内容 |
|---|---|
| `src/main/java/com/example/flxr/unity/FoundryLocalBridge.kt` | 静的 API の本体（デバイスに依存しない） |
| `src/main/java/com/example/flxr/unity/UnityMessenger.kt` | `UnitySendMessage` のリフレクション呼び出しと `BridgeListener` |
| `src/main/java/com/example/flxr/unity/BridgeJson.kt` | configJson → `AssistantConfig`、状態 → JSON |
| `src/main/java/com/example/flxr/unity/mirza/GlassMic.kt` | MiRZA 固有：`TYPE_IP` のマイクの PCM の Flow |
| `consumer-rules.pro` | R8 の keep ルール（C# から名前で呼ぶため） |
| `unity-gradle/*.gradle` | Unity の Custom Gradle Template に貼る断片 |
