// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

#if FLXR_MIRZA
using com.nttqonoq.devices.android.mirzalibrary;
using UnityEngine;

namespace Example.FoundryLocalXR.MiRZA
{
    /// <summary>Switches the MiRZA glasses microphone mode through MiRZA Library; compiled only when FLXR_MIRZA is defined.</summary>
    public class MiRZAMicModeSwitcher : MonoBehaviour
    {
        [Tooltip("聞き取りに使うマイクモード。装着者の声だけを拾うモードが STT 向き")]
        public MicMode mode;
        public bool applyOnStart = true;

        MirzaPlugin plugin;

        void Start()
        {
            if (applyOnStart) Apply();
        }

        /// <summary>Applies <see cref="mode"/> to the glasses microphone; ignored while listening.</summary>
        public void Apply()
        {
            if (FoundryLocalBridge.State.listening)
            {
                Debug.LogWarning("[FoundryLocalXR] 聞き取り中はマイクモードを変更できません");
                return;
            }
            plugin ??= new MirzaPlugin();
            plugin.SwitchMicrophone(mode, mode, 0);
            Debug.Log($"[FoundryLocalXR] MiRZA mic mode -> {mode}");
        }
    }
}
#endif
