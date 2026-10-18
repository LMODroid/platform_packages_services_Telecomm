/*
 * Copyright (C) 2026 The LibreMobileOS Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import android.app.ActivityManager;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

/**
 * Keys of LMOSettings.System used by Telecom.
 *
 * Telecom also builds as part of the Telephony APEX, which can't link framework-lmodroid,
 * so the values are copied here. Keep them in sync with
 * frameworks/lmodroid com.libremobileos.providers.LMOSettings.
 */
final class LMOSettingsKeys {
    static final String INCREASING_RING = "increasing_ring";
    static final String INCREASING_RING_START_VOLUME = "increasing_ring_start_vol";
    static final String INCREASING_RING_RAMP_UP_TIME = "increasing_ring_ramp_up_time";
    static final String VIBRATE_ON_CONNECT = "vibrate_on_connect";
    static final String VIBRATE_ON_CALLWAITING = "vibrate_on_callwaiting";
    static final String VIBRATE_ON_DISCONNECT = "vibrate_on_disconnect";

    /**
     * Settings.System.getIntForUser(..., USER_CURRENT) without the hidden APIs, which the
     * Telephony APEX build can't use (same way as SystemSettingsUtil).
     */
    static int getIntForCurrentUser(Context context, String name, int def) {
        return Settings.System.getInt(context.createContextAsUser(
                UserHandle.of(ActivityManager.getCurrentUser()), 0).getContentResolver(),
                name, def);
    }

    private LMOSettingsKeys() {}
}
