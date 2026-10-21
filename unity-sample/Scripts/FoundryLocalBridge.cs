// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

using System;
using System.Collections.Generic;
using UnityEngine;

namespace Example.FoundryLocalXR
{
    /// <summary>Speech-to-text strategy used by the native pipeline.</summary>
    public enum SpeechMode
    {
        /// <summary>Whisper on VAD-segmented utterances; supports any Whisper language.</summary>
        Whisper,
        /// <summary>Native streaming ASR with partial results; English only.</summary>
        StreamingEn,
    }

    /// <summary>Pipeline configuration passed to the native bridge; empty fields fall back to the native defaults.</summary>
    [Serializable]
    public class AssistantConfig
    {
        public SpeechMode speechMode = SpeechMode.Whisper;
        public string language = "ja";
        public bool chatEnabled = true;
        [TextArea] public string systemPrompt = "";
        public List<string> whisperModels = new List<string>();
        public List<string> streamingModels = new List<string>();
        public List<string> chatModels = new List<string>();

        [Serializable]
        private class Wire
        {
            public string speechMode;
            public string language;
            public bool chatEnabled;
            public string systemPrompt;
            public string[] whisperModels;
            public string[] streamingModels;
            public string[] chatModels;
        }

        /// <summary>Serializes the configuration to the bridge's config JSON.</summary>
        public string ToJson() => JsonUtility.ToJson(new Wire
        {
            speechMode = speechMode == SpeechMode.StreamingEn ? "streaming_en" : "whisper",
            language = language,
            chatEnabled = chatEnabled,
            systemPrompt = systemPrompt,
            whisperModels = whisperModels?.ToArray() ?? new string[0],
            streamingModels = streamingModels?.ToArray() ?? new string[0],
            chatModels = chatModels?.ToArray() ?? new string[0],
        });
    }

    /// <summary>Snapshot of the native pipeline state.</summary>
    [Serializable]
    public class AssistantState
    {
        public string status = "";
        /// <summary>Download or load progress from 0 to 1, or -1 when idle.</summary>
        public float progress = -1f;
        public bool ready;
        public bool listening;
        /// <summary>Hypothesis for the utterance in progress.</summary>
        public string partial = "";
        /// <summary>Last pipeline error, empty when none.</summary>
        public string error = "";
        public string models = "";
    }

    /// <summary>Error reported by the native bridge.</summary>
    [Serializable]
    public class BridgeError
    {
        public string code;
        public string message;
    }

    [Serializable]
    internal class TextMessage
    {
        public string text;
    }

    /// <summary>Static C# facade over the native Foundry Local bridge; events are raised on the Unity main thread.</summary>
    public static class FoundryLocalBridge
    {
        const string JavaClass = "com.example.flxr.unity.FoundryLocalBridge";
        const string ReceiverName = "FoundryLocalBridgeReceiver";

        /// <summary>Raised when the pipeline state changes.</summary>
        public static event Action<AssistantState> StateChanged;
        /// <summary>Raised with each finished user utterance or text question.</summary>
        public static event Action<string> FinalTranscript;
        /// <summary>Raised with each streamed reply fragment.</summary>
        public static event Action<string> ReplyDelta;
        /// <summary>Raised with each finished reply.</summary>
        public static event Action<string> Reply;
        /// <summary>Raised when the bridge reports an error.</summary>
        public static event Action<BridgeError> Error;

        /// <summary>Latest pipeline state.</summary>
        public static AssistantState State { get; private set; } = new AssistantState();
        /// <summary>Whether the current platform supports the native bridge.</summary>
        public static bool IsSupported => Application.platform == RuntimePlatform.Android;

#if UNITY_ANDROID && !UNITY_EDITOR
        static AndroidJavaClass bridge;
        static AndroidJavaClass Bridge => bridge ??= new AndroidJavaClass(JavaClass);
#endif

        /// <summary>Creates the native assistant and starts model download / load.</summary>
        public static bool Init(AssistantConfig config)
        {
            EnsureReceiver();
#if UNITY_ANDROID && !UNITY_EDITOR
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            {
                return Bridge.CallStatic<bool>("init", activity, ReceiverName, (config ?? new AssistantConfig()).ToJson());
            }
#else
            Debug.LogWarning("[FoundryLocalBridge] Android 実機でのみ動作します（Editor では何もしません）");
            Dispatch(s => s.OnBridgeState(JsonUtility.ToJson(new AssistantState { status = "Editor: 非対応（Android 実機で実行してください）" })));
            return false;
#endif
        }

        /// <summary>Starts listening on the device's default microphone.</summary>
        public static bool StartMic() => CallBool("startMic");

        /// <summary>Starts listening on the MiRZA glasses microphone.</summary>
        public static bool StartGlassMic() => CallBool("startGlassMic");

        /// <summary>Starts push mode, in which audio is supplied with <see cref="PushPcm"/> or <see cref="PushSamples"/>.</summary>
        public static bool StartPcmInput() => CallBool("startPcmInput");

        /// <summary>Ends push mode after transcribing the utterance in progress.</summary>
        public static void StopPcmInput() => Call("stopPcmInput");

        /// <summary>Stops listening immediately on any input.</summary>
        public static void StopMic() => Call("stopMic");

        /// <summary>Sends a text question to the assistant.</summary>
        public static void Ask(string text) => Call("ask", text);

        /// <summary>Returns whether the models are ready.</summary>
        public static bool IsReady() => CallBool("isReady");

        /// <summary>Returns whether RECORD_AUDIO is granted.</summary>
        public static bool HasMicPermission() => CallBool("hasMicPermission");

        /// <summary>Returns a description of the available audio input devices.</summary>
        public static string DescribeInputDevices()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            return Bridge.CallStatic<string>("describeInputDevices");
#else
            return "";
#endif
        }

        /// <summary>Unloads the models and releases the native runtime.</summary>
        public static void Release() => Call("release");

        /// <summary>Pushes 16 kHz mono signed 16-bit little-endian PCM in push mode.</summary>
        public static void PushPcm(byte[] pcm16le)
        {
            if (pcm16le == null || pcm16le.Length == 0) return;
#if UNITY_ANDROID && !UNITY_EDITOR
            var s = new sbyte[pcm16le.Length];
            Buffer.BlockCopy(pcm16le, 0, s, 0, pcm16le.Length);
            Bridge.CallStatic("pushPcm", s);
#endif
        }

        /// <summary>Resamples Unity float samples to 16 kHz mono PCM16 and pushes them.</summary>
        public static void PushSamples(float[] samples, int count, int sampleRate, int channels = 1)
        {
            if (samples == null || count <= 0) return;
            int frames = count / Mathf.Max(1, channels);
            int outFrames = (int)((long)frames * 16000 / sampleRate);
            if (outFrames <= 0) return;
            var pcm = new byte[outFrames * 2];
            double step = (double)sampleRate / 16000;
            for (int i = 0; i < outFrames; i++)
            {
                double src = i * step;
                int i0 = (int)src;
                int i1 = Math.Min(i0 + 1, frames - 1);
                float t = (float)(src - i0);
                float a = samples[i0 * channels];
                float b = samples[i1 * channels];
                short v = (short)Mathf.Clamp((a + (b - a) * t) * 32767f, -32768f, 32767f);
                pcm[2 * i] = (byte)v;
                pcm[2 * i + 1] = (byte)(v >> 8);
            }
            PushPcm(pcm);
        }

        static void Call(string method, params object[] args)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            Bridge.CallStatic(method, args);
#endif
        }

        static bool CallBool(string method)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            return Bridge.CallStatic<bool>(method);
#else
            return false;
#endif
        }

        static FoundryLocalBridgeReceiver receiver;

        static void EnsureReceiver()
        {
            if (receiver != null) return;
            var go = GameObject.Find(ReceiverName);
            if (go == null) go = new GameObject(ReceiverName);
            receiver = go.GetComponent<FoundryLocalBridgeReceiver>();
            if (receiver == null) receiver = go.AddComponent<FoundryLocalBridgeReceiver>();
            UnityEngine.Object.DontDestroyOnLoad(go);
        }

        static void Dispatch(Action<FoundryLocalBridgeReceiver> a)
        {
            EnsureReceiver();
            a(receiver);
        }

        internal static void RaiseState(AssistantState s)
        {
            State = s;
            StateChanged?.Invoke(s);
        }

        internal static void RaiseTranscript(string t) => FinalTranscript?.Invoke(t);
        internal static void RaiseReplyDelta(string t) => ReplyDelta?.Invoke(t);
        internal static void RaiseReply(string t) => Reply?.Invoke(t);
        internal static void RaiseError(BridgeError e) => Error?.Invoke(e);
    }

    /// <summary>Receives UnitySendMessage callbacks from the native bridge; created automatically by <see cref="FoundryLocalBridge.Init"/>.</summary>
    public class FoundryLocalBridgeReceiver : MonoBehaviour
    {
        /// <summary>Handles a state message.</summary>
        public void OnBridgeState(string json) =>
            FoundryLocalBridge.RaiseState(JsonUtility.FromJson<AssistantState>(json));

        /// <summary>Handles a finished transcript message.</summary>
        public void OnBridgeTranscript(string json) =>
            FoundryLocalBridge.RaiseTranscript(JsonUtility.FromJson<TextMessage>(json).text);

        /// <summary>Handles a streamed reply fragment message.</summary>
        public void OnBridgeReplyDelta(string json) =>
            FoundryLocalBridge.RaiseReplyDelta(JsonUtility.FromJson<TextMessage>(json).text);

        /// <summary>Handles a finished reply message.</summary>
        public void OnBridgeReply(string json) =>
            FoundryLocalBridge.RaiseReply(JsonUtility.FromJson<TextMessage>(json).text);

        /// <summary>Handles an error message.</summary>
        public void OnBridgeError(string json)
        {
            var e = JsonUtility.FromJson<BridgeError>(json);
            Debug.LogWarning($"[FoundryLocalBridge] {e.code}: {e.message}");
            FoundryLocalBridge.RaiseError(e);
        }

        void OnApplicationQuit() => FoundryLocalBridge.Release();
    }
}
