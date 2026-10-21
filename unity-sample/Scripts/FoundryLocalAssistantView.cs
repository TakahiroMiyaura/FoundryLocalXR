// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

using System.Collections.Generic;
using System.Text;
using TMPro;
using UnityEngine;
using UnityEngine.UI;
#if UNITY_ANDROID && !UNITY_EDITOR
using UnityEngine.Android;
#endif

namespace Example.FoundryLocalXR
{
    /// <summary>Audio input used by <see cref="FoundryLocalAssistantView"/>.</summary>
    public enum MicSource
    {
        /// <summary>Device default microphone captured by the native bridge.</summary>
        DeviceMic,
        /// <summary>MiRZA glasses microphone.</summary>
        MiRZAGlassMic,
        /// <summary>Unity Microphone API streamed in push mode through a <see cref="UnityMicrophoneStreamer"/>.</summary>
        UnityMicrophone,
    }

    /// <summary>Minimal UI for the assistant: status, progress, microphone toggle, text input and conversation log.</summary>
    public class FoundryLocalAssistantView : MonoBehaviour
    {
        [Header("Pipeline")]
        public AssistantConfig config = new AssistantConfig();
        public MicSource micSource = MicSource.DeviceMic;
        [Tooltip("Start() で RECORD_AUDIO を要求してから Init する。MiRZA(Dual Render Fusion) では OpenXR 起動前の権限処理を別途行う場合は外す")]
        public bool requestMicPermissionOnStart = true;
        public UnityMicrophoneStreamer unityMicrophone;

        [Header("UI (optional)")]
        public TMP_Text statusText;
        public Slider progressBar;
        public TMP_Text partialText;
        public TMP_Text logText;
        public Button micButton;
        public TMP_Text micButtonLabel;
        public TMP_InputField askInput;
        public Button askButton;
        [Range(1, 20)] public int maxTurns = 6;

        class Turn { public string user; public StringBuilder ai = new StringBuilder(); public bool done; }
        readonly List<Turn> turns = new List<Turn>();
        AssistantState state = new AssistantState();

        void OnEnable()
        {
            FoundryLocalBridge.StateChanged += OnState;
            FoundryLocalBridge.FinalTranscript += OnTranscript;
            FoundryLocalBridge.ReplyDelta += OnReplyDelta;
            FoundryLocalBridge.Reply += OnReply;
            FoundryLocalBridge.Error += OnError;
        }

        void OnDisable()
        {
            FoundryLocalBridge.StateChanged -= OnState;
            FoundryLocalBridge.FinalTranscript -= OnTranscript;
            FoundryLocalBridge.ReplyDelta -= OnReplyDelta;
            FoundryLocalBridge.Reply -= OnReply;
            FoundryLocalBridge.Error -= OnError;
        }

        void Start()
        {
            if (micButton) micButton.onClick.AddListener(ToggleMic);
            if (askButton) askButton.onClick.AddListener(SubmitAsk);
            if (askInput) askInput.onSubmit.AddListener(_ => SubmitAsk());
            Render();

#if UNITY_ANDROID && !UNITY_EDITOR
            if (requestMicPermissionOnStart && !Permission.HasUserAuthorizedPermission(Permission.Microphone))
            {
                var cb = new PermissionCallbacks();
                cb.PermissionGranted += _ => FoundryLocalBridge.Init(config);
                cb.PermissionDenied += _ => { FoundryLocalBridge.Init(config); SetStatusNote("マイク未許可: テキスト質問のみ使えます"); };
                Permission.RequestUserPermission(Permission.Microphone, cb);
                return;
            }
#endif
            FoundryLocalBridge.Init(config);
        }

        /// <summary>Starts or stops listening on the selected <see cref="MicSource"/>.</summary>
        public void ToggleMic()
        {
            if (state.listening)
            {
                if (micSource == MicSource.UnityMicrophone && unityMicrophone) unityMicrophone.End();
                else FoundryLocalBridge.StopMic();
                return;
            }
            bool ok;
            switch (micSource)
            {
                case MicSource.MiRZAGlassMic:
                    Debug.Log("[FoundryLocalXR] inputs: " + FoundryLocalBridge.DescribeInputDevices());
                    ok = FoundryLocalBridge.StartGlassMic();
                    break;
                case MicSource.UnityMicrophone:
                    ok = unityMicrophone && unityMicrophone.Begin();
                    break;
                default:
                    ok = FoundryLocalBridge.StartMic();
                    break;
            }
            if (!ok) Debug.LogWarning("[FoundryLocalXR] mic start failed (see OnBridgeError / logcat FLXR)");
        }

        /// <summary>Sends the text in the input field as a question.</summary>
        public void SubmitAsk()
        {
            if (!askInput || string.IsNullOrWhiteSpace(askInput.text)) return;
            FoundryLocalBridge.Ask(askInput.text);
            askInput.text = "";
        }

        void OnState(AssistantState s)
        {
            state = s;
            Render();
        }

        void OnTranscript(string text)
        {
            turns.Add(new Turn { user = text, done = !config.chatEnabled });
            while (turns.Count > maxTurns) turns.RemoveAt(0);
            Render();
        }

        void OnReplyDelta(string delta)
        {
            var t = Current();
            if (t != null) { t.ai.Append(delta); Render(); }
        }

        void OnReply(string reply)
        {
            var t = Current();
            if (t != null) { t.ai.Clear().Append(reply); t.done = true; Render(); }
        }

        void OnError(BridgeError e) => SetStatusNote($"{e.code}: {e.message}");

        Turn Current()
        {
            for (int i = turns.Count - 1; i >= 0; i--) if (!turns[i].done) return turns[i];
            return null;
        }

        string note = "";
        void SetStatusNote(string n) { note = n; Render(); }

        void Render()
        {
            if (statusText)
            {
                var sb = new StringBuilder(state.status);
                if (!string.IsNullOrEmpty(state.models)) sb.Append('\n').Append(state.models);
                if (!string.IsNullOrEmpty(state.error)) sb.Append("\n<color=#FF8A80>").Append(state.error).Append("</color>");
                if (!string.IsNullOrEmpty(note)) sb.Append("\n<color=#FFD54F>").Append(note).Append("</color>");
                statusText.text = sb.ToString();
            }
            if (progressBar)
            {
                progressBar.gameObject.SetActive(state.progress >= 0f);
                progressBar.value = Mathf.Clamp01(state.progress);
            }
            if (partialText) partialText.text = state.listening ? "聞き取り中… " + state.partial : "";
            if (micButton) micButton.interactable = state.ready;
            if (micButtonLabel) micButtonLabel.text = state.listening ? "■ 停止" : "● 話す";
            if (askButton) askButton.interactable = state.ready;
            if (logText)
            {
                var sb = new StringBuilder();
                foreach (var t in turns)
                {
                    sb.Append("<color=#E3F2FD>あなた: ").Append(t.user).Append("</color>\n");
                    if (t.ai.Length > 0 || !t.done)
                        sb.Append("<color=#A5D6A7>AI: ").Append(t.ai).Append(t.done ? "" : " ▍").Append("</color>\n");
                    sb.Append('\n');
                }
                logText.text = sb.ToString();
            }
        }
    }
}
