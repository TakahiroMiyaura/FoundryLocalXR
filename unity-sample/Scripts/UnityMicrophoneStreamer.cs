// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

using UnityEngine;

namespace Example.FoundryLocalXR
{
    /// <summary>Streams audio from Unity's Microphone API to the native bridge in push mode.</summary>
    public class UnityMicrophoneStreamer : MonoBehaviour
    {
        [Tooltip("Microphone.devices の名前。空ならデフォルトマイク")]
        public string deviceName = "";
        [Tooltip("録音サンプルレート。16000 を渡せないデバイスでは自動でリサンプリングされる")]
        public int sampleRate = 16000;

        AudioClip clip;
        int readPos;
        float[] buffer = new float[0];

        /// <summary>Whether recording is in progress.</summary>
        public bool IsStreaming => clip != null;

        /// <summary>Starts push mode and begins recording.</summary>
        public bool Begin()
        {
            if (IsStreaming) return true;
            if (!FoundryLocalBridge.StartPcmInput()) return false;
            var dev = string.IsNullOrEmpty(deviceName) ? null : deviceName;
            clip = Microphone.Start(dev, true, 2, sampleRate);
            readPos = 0;
            if (clip == null) FoundryLocalBridge.StopPcmInput();
            return clip != null;
        }

        /// <summary>Stops recording and ends push mode after transcribing the utterance in progress.</summary>
        public void End()
        {
            if (!IsStreaming) return;
            Microphone.End(string.IsNullOrEmpty(deviceName) ? null : deviceName);
            clip = null;
            FoundryLocalBridge.StopPcmInput();
        }

        void Update()
        {
            if (clip == null) return;
            int pos = Microphone.GetPosition(string.IsNullOrEmpty(deviceName) ? null : deviceName);
            if (pos < 0 || pos == readPos) return;
            int count = pos > readPos ? pos - readPos : clip.samples - readPos + pos;
            int ch = clip.channels;
            if (buffer.Length < count * ch) buffer = new float[count * ch];
            clip.GetData(buffer, readPos);
            FoundryLocalBridge.PushSamples(buffer, count * ch, clip.frequency, ch);
            readPos = pos;
        }

        void OnDisable() => End();
    }
}
