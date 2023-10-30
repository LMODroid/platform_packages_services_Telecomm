/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom;

import static com.android.server.telecom.AudioRoute.BT_AUDIO_ROUTE_TYPES;
import static com.android.server.telecom.AudioRoute.TYPE_INVALID;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioProductStrategy;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.telecom.CallAudioState;
import android.telecom.Log;
import android.telecom.Logging.Session;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.IndentingPrintWriter;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class CallAudioRouteController implements CallAudioRouteAdapter {
    private static final long TIMEOUT_LIMIT = 2000L;
    private static final AudioRoute DUMMY_ROUTE = new AudioRoute(TYPE_INVALID, null, null);
    private static final Map<Integer, Integer> ROUTE_MAP;
    static {
        ROUTE_MAP = new ArrayMap<>();
        ROUTE_MAP.put(AudioRoute.TYPE_EARPIECE, CallAudioState.ROUTE_EARPIECE);
        ROUTE_MAP.put(AudioRoute.TYPE_WIRED, CallAudioState.ROUTE_WIRED_HEADSET);
        ROUTE_MAP.put(AudioRoute.TYPE_SPEAKER, CallAudioState.ROUTE_SPEAKER);
        ROUTE_MAP.put(AudioRoute.TYPE_DOCK, CallAudioState.ROUTE_SPEAKER);
        ROUTE_MAP.put(AudioRoute.TYPE_BLUETOOTH_SCO, CallAudioState.ROUTE_BLUETOOTH);
        ROUTE_MAP.put(AudioRoute.TYPE_BLUETOOTH_HA, CallAudioState.ROUTE_BLUETOOTH);
        ROUTE_MAP.put(AudioRoute.TYPE_BLUETOOTH_LE, CallAudioState.ROUTE_BLUETOOTH);
    }

    private final CallsManager mCallsManager;
    private AudioManager mAudioManager;
    private final Handler mHandler;
    private final WiredHeadsetManager mWiredHeadsetManager;
    private Set<AudioRoute> mAvailableRoutes;
    private AudioRoute mCurrentRoute;
    private AudioRoute mEarpieceWiredRoute;
    private AudioRoute mSpeakerDockRoute;
    private Map<AudioRoute, BluetoothDevice> mBluetoothRoutes;
    private Map<Integer, AudioRoute> mTypeRoutes;
    private PendingAudioRoute mPendingAudioRoute;
    private AudioRoute.Factory mAudioRouteFactory;
    private CallAudioState mCallAudioState;
    private boolean mIsMute;
    private boolean mIsPending;
    private boolean mIsActive;

    public CallAudioRouteController(
            Context context,
            CallsManager callsManager,
            AudioRoute.Factory audioRouteFactory,
            WiredHeadsetManager wiredHeadsetManager) {
        mCallsManager = callsManager;
        mAudioManager = context.getSystemService(AudioManager.class);
        mAudioRouteFactory = audioRouteFactory;
        mWiredHeadsetManager = wiredHeadsetManager;
        mIsMute = false;
        HandlerThread handlerThread = new HandlerThread(this.getClass().getSimpleName());
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                preHandleMessage(msg);
                String address;
                BluetoothDevice bluetoothDevice;
                @AudioRoute.AudioRouteType int type;
                switch (msg.what) {
                    case BT_AUDIO_CONNECTED:
                        bluetoothDevice = (BluetoothDevice) ((SomeArgs) msg.obj).arg2;
                        handleBtAudioActive(bluetoothDevice);
                        break;
                    case BT_AUDIO_DISCONNECTED:
                        bluetoothDevice = (BluetoothDevice) ((SomeArgs) msg.obj).arg2;
                        handleBtAudioInactive(bluetoothDevice);
                        break;
                    case BT_DEVICE_ADDED:
                        type = msg.arg1;
                        bluetoothDevice = (BluetoothDevice) ((SomeArgs) msg.obj).arg2;
                        handleBtConnected(type, bluetoothDevice);
                        break;
                    case BT_DEVICE_REMOVED:
                        type = msg.arg1;
                        bluetoothDevice = (BluetoothDevice) ((SomeArgs) msg.obj).arg2;
                        handleBtDisconnected(type, bluetoothDevice);
                        break;
                    case BLUETOOTH_DEVICE_LIST_CHANGED:
                        break;
                    case BT_ACTIVE_DEVICE_PRESENT:
                        type = msg.arg1;
                        address = (String) ((SomeArgs) msg.obj).arg2;
                        handleBtActiveDevicePresent(type, address);
                        break;
                    case BT_ACTIVE_DEVICE_GONE:
                        type = msg.arg1;
                        handleBtActiveDeviceGone(type);
                        break;
                    case EXIT_PENDING_ROUTE:
                        handleExitPendingRoute();
                        break;
                    default:
                        break;
                }
                postHandleMessage(msg);
            }
        };
    }
    @Override
    public void initialize() {
        mAvailableRoutes = new HashSet<>();
        mBluetoothRoutes = new ArrayMap<>();
        mTypeRoutes = new ArrayMap<>();
        mPendingAudioRoute = new PendingAudioRoute(this, mAudioManager);

        int supportMask = calculateSupportedRouteMask();
        if ((supportMask & CallAudioState.ROUTE_SPEAKER) != 0) {
            // Create spekaer routes
            mSpeakerDockRoute = mAudioRouteFactory.create(AudioRoute.TYPE_SPEAKER, null,
                    mAudioManager);
            if (mSpeakerDockRoute == null) {
                Log.w(this, "Can't find available audio device info for route TYPE_SPEAKER");
            } else {
                mTypeRoutes.put(AudioRoute.TYPE_SPEAKER, mSpeakerDockRoute);
                mAvailableRoutes.add(mSpeakerDockRoute);
            }
        }

        if ((supportMask & CallAudioState.ROUTE_WIRED_HEADSET) != 0) {
            // Create wired headset routes
            mEarpieceWiredRoute = mAudioRouteFactory.create(AudioRoute.TYPE_WIRED, null,
                    mAudioManager);
            if (mEarpieceWiredRoute == null) {
                Log.w(this, "Can't find available audio device info for route TYPE_WIRED_HEADSET");
            } else {
                mTypeRoutes.put(AudioRoute.TYPE_WIRED, mEarpieceWiredRoute);
                mAvailableRoutes.add(mEarpieceWiredRoute);
            }
        } else if ((supportMask & CallAudioState.ROUTE_EARPIECE) != 0) {
            // Create earpiece routes
            mEarpieceWiredRoute = mAudioRouteFactory.create(AudioRoute.TYPE_EARPIECE, null,
                    mAudioManager);
            if (mEarpieceWiredRoute == null) {
                Log.w(this, "Can't find available audio device info for route TYPE_EARPIECE");
            } else {
                mTypeRoutes.put(AudioRoute.TYPE_EARPIECE, mEarpieceWiredRoute);
                mAvailableRoutes.add(mEarpieceWiredRoute);
            }
        }

        // set current route
        if (mEarpieceWiredRoute != null) {
            mCurrentRoute = mEarpieceWiredRoute;
        } else {
            mCurrentRoute = mSpeakerDockRoute;
        }
        mIsActive = false;
        mCallAudioState = new CallAudioState(mIsMute, ROUTE_MAP.get(mCurrentRoute.getType()),
                supportMask, null, new HashSet<>());
    }

    @Override
    public void sendMessageWithSessionInfo(int message) {
        sendMessageWithSessionInfo(message, 0, (String) null);
    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg) {
        sendMessageWithSessionInfo(message, arg, (String) null);
    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg, String data) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = Log.createSubsession();
        args.arg2 = data;
        sendMessage(message, arg, 0, args);
    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg, BluetoothDevice bluetoothDevice) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = Log.createSubsession();
        args.arg2 = bluetoothDevice;
        sendMessage(message, arg, 0, args);
    }

    @Override
    public void sendMessage(int message, Runnable r) {
        r.run();
    }

    private void sendMessage(int what, int arg1, int arg2, Object obj) {
        mHandler.sendMessage(Message.obtain(mHandler, what, arg1, arg2, obj));
    }

    @Override
    public void setCallAudioManager(CallAudioManager callAudioManager) {
    }

    @Override
    public CallAudioState getCurrentCallAudioState() {
        return null;
    }

    @Override
    public boolean isHfpDeviceAvailable() {
        return !mBluetoothRoutes.isEmpty();
    }

    @Override
    public Handler getAdapterHandler() {
        return mHandler;
    }

    @Override
    public void dump(IndentingPrintWriter pw) {
    }

    private void preHandleMessage(Message msg) {
        if (msg.obj instanceof SomeArgs) {
            Session session = (Session) ((SomeArgs) msg.obj).arg1;
            String messageCodeName = MESSAGE_CODE_TO_NAME.get(msg.what, "unknown");
            Log.continueSession(session, "CARC.pM_" + messageCodeName);
            Log.i(this, "Message received: %s=%d, arg1=%d", messageCodeName, msg.what, msg.arg1);
        }
    }

    private void postHandleMessage(Message msg) {
        Log.endSession();
        if (msg.obj instanceof SomeArgs) {
            ((SomeArgs) msg.obj).recycle();
        }
    }

    public boolean isActive() {
        return mIsActive;
    }

    public boolean isPending() {
        return mIsPending;
    }

    private void routeTo(boolean active, AudioRoute destRoute) {
        if (mIsPending) {
            if (destRoute.equals(mPendingAudioRoute.getDestRoute()) && (mIsActive == active)) {
                return;
            }
            Log.i(this, "Override current pending route destination from %s(active=%b) to "
                            + "%s(active=%b)",
                    mPendingAudioRoute.getDestRoute(), mIsActive, destRoute, active);
            // override pending route while keep waiting for still pending messages for the
            // previous pending route
            mPendingAudioRoute.setOrigRoute(mIsActive, mPendingAudioRoute.getDestRoute());
            mPendingAudioRoute.setDestRoute(active, destRoute);
        } else {
            if (mCurrentRoute.equals(destRoute) && (mIsActive = active)) {
                return;
            }
            Log.i(this, "Enter pending route, orig%s(active=%b), dest%s(active=%b)", mCurrentRoute,
                    mIsActive, destRoute, active);
            // route to pending route
            if (mAvailableRoutes.contains(mCurrentRoute)) {
                mPendingAudioRoute.setOrigRoute(mIsActive, mCurrentRoute);
            } else {
                // Avoid waiting for pending messages for an unavailable route
                mPendingAudioRoute.setOrigRoute(mIsActive, DUMMY_ROUTE);
            }
            mPendingAudioRoute.setDestRoute(active, destRoute);
            mIsPending = true;
        }
        mPendingAudioRoute.evaluatePendingState();
        postTimeoutMessage();
    }

    private void postTimeoutMessage() {
        // reset timeout handler
        mHandler.removeMessages(PENDING_ROUTE_TIMEOUT);
        mHandler.postDelayed(() -> mHandler.sendMessage(
                Message.obtain(mHandler, PENDING_ROUTE_TIMEOUT)), TIMEOUT_LIMIT);
    }

    private void handleBtAudioActive(BluetoothDevice bluetoothDevice) {
        if (mIsPending) {
            if (Objects.equals(mPendingAudioRoute.getDestRoute().getBluetoothAddress(),
                    bluetoothDevice.getAddress())) {
                mPendingAudioRoute.onMessageReceived(BT_AUDIO_CONNECTED);
            }
        } else {
            // ignore, not triggered by telecom
        }
    }

    private void handleBtAudioInactive(BluetoothDevice bluetoothDevice) {
        if (mIsPending) {
            if (Objects.equals(mPendingAudioRoute.getOrigRoute().getBluetoothAddress(),
                    bluetoothDevice.getAddress())) {
                mPendingAudioRoute.onMessageReceived(BT_AUDIO_DISCONNECTED);
            }
        } else {
            // ignore, not triggered by telecom
        }
    }

    private void handleBtConnected(@AudioRoute.AudioRouteType int type,
                                   BluetoothDevice bluetoothDevice) {
        AudioRoute bluetoothRoute = null;
        bluetoothRoute = mAudioRouteFactory.create(type, bluetoothDevice.getAddress(),
                mAudioManager);
        if (bluetoothRoute == null) {
            Log.w(this, "Can't find available audio device info for route type:"
                    + AudioRoute.DEVICE_TYPE_STRINGS.get(type));
        } else {
            Log.i(this, "bluetooth route added: " + bluetoothRoute);
            mAvailableRoutes.add(bluetoothRoute);
            mBluetoothRoutes.put(bluetoothRoute, bluetoothDevice);
            onAvailableRoutesChanged();
        }
    }

    private void handleBtDisconnected(@AudioRoute.AudioRouteType int type,
                                      BluetoothDevice bluetoothDevice) {
        // Clean up unavailable routes
        AudioRoute bluetoothRoute = getBluetoothRoute(type, bluetoothDevice.getAddress());
        if (bluetoothRoute != null) {
            Log.i(this, "bluetooth route removed: " + bluetoothRoute);
            mBluetoothRoutes.remove(bluetoothRoute);
            mAvailableRoutes.remove(bluetoothRoute);
            onAvailableRoutesChanged();
        }

        // Fallback to an available route
        if (Objects.equals(mCurrentRoute, bluetoothRoute)) {
            // fallback policy
            AudioRoute destRoute = getPreferredAudioRouteFromStrategy();
            if (destRoute != null && mAvailableRoutes.contains(destRoute)) {
                routeTo(mIsActive, destRoute);
            } else {
                routeTo(mIsActive, getPreferredAudioRouteFromDefault(true/* includeBluetooth */));
            }
        }
    }

    private void handleBtActiveDevicePresent(@AudioRoute.AudioRouteType int type,
                                             String deviceAddress) {
        AudioRoute bluetoothRoute = getBluetoothRoute(type, deviceAddress);
        if (bluetoothRoute != null) {
            Log.i(this, "request to route to bluetooth route: %s(active=%b)", bluetoothRoute,
                    mIsActive);
            routeTo(mIsActive, bluetoothRoute);
        }
    }

    private void handleBtActiveDeviceGone(@AudioRoute.AudioRouteType int type) {
        if ((mIsPending && mPendingAudioRoute.getDestRoute().getType() == type)
                || (!mIsPending && mCurrentRoute.getType() == type)) {
            // Fallback to an available route
            AudioRoute destRoute = getPreferredAudioRouteFromStrategy();
            if (destRoute != null && mAvailableRoutes.contains(destRoute)) {
                routeTo(mIsActive, destRoute);
            } else {
                routeTo(mIsActive, getPreferredAudioRouteFromDefault(false/* includeBluetooth */));
            }
            onAvailableRoutesChanged();
        }
    }

    public void handleExitPendingRoute() {
        if (mIsPending) {
            Log.i(this, "Exit pending route and enter %s(active=%b)",
                    mPendingAudioRoute.getDestRoute(), mPendingAudioRoute.isActive());
            mCurrentRoute = mPendingAudioRoute.getDestRoute();
            mIsActive = mPendingAudioRoute.isActive();
            mIsPending = false;
            onCurrentRouteChanged();
        }
    }

    private void onCurrentRouteChanged() {
        BluetoothDevice activeBluetoothDevice = null;
        int route = ROUTE_MAP.get(mCurrentRoute.getType());
        if (route == CallAudioState.ROUTE_BLUETOOTH) {
            activeBluetoothDevice = mBluetoothRoutes.get(mCurrentRoute);
        }
        updateCallAudioState(new CallAudioState(mIsMute, route,
                mCallAudioState.getSupportedRouteMask(), activeBluetoothDevice,
                mCallAudioState.getSupportedBluetoothDevices()));
    }

    private void onAvailableRoutesChanged() {
        int routeMask = 0;
        Set<BluetoothDevice> availableBluetoothDevices = new HashSet<>();
        for (AudioRoute route : mAvailableRoutes) {
            routeMask |= ROUTE_MAP.get(route.getType());
            if (BT_AUDIO_ROUTE_TYPES.contains(route.getType())) {
                availableBluetoothDevices.add(mBluetoothRoutes.get(route));
            }
        }
        updateCallAudioState(new CallAudioState(mIsMute, mCallAudioState.getRoute(), routeMask,
                mCallAudioState.getActiveBluetoothDevice(), availableBluetoothDevices));
    }

    private void updateCallAudioState(CallAudioState callAudioState) {
        CallAudioState oldState = mCallAudioState;
        mCallAudioState = callAudioState;
        mCallsManager.onCallAudioStateChanged(oldState, mCallAudioState);
    }

    private AudioRoute getPreferredAudioRouteFromStrategy() {
        // Get audio produce strategy
        AudioProductStrategy strategy = null;
        final AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build();
        List<AudioProductStrategy> strategies = AudioManager.getAudioProductStrategies();
        for (AudioProductStrategy s : strategies) {
            if (s.supportsAudioAttributes(attr)) {
                strategy = s;
            }
        }
        if (strategy == null) {
            return null;
        }

        // Get preferred device
        AudioDeviceAttributes deviceAttr = mAudioManager.getPreferredDeviceForStrategy(strategy);
        if (deviceAttr == null) {
            return null;
        }

        // Get corresponding audio route
        @AudioRoute.AudioRouteType int type = AudioRoute.DEVICE_INFO_TYPETO_AUDIO_ROUTE_TYPE.get(
                deviceAttr.getType());
        if (BT_AUDIO_ROUTE_TYPES.contains(type)) {
            return getBluetoothRoute(type, deviceAttr.getAddress());
        } else {
            return mTypeRoutes.get(deviceAttr.getType());

        }
    }

    public AudioRoute getPreferredAudioRouteFromDefault(boolean includeBluetooth) {
        if (mBluetoothRoutes.isEmpty() || !includeBluetooth) {
            return mEarpieceWiredRoute != null ? mEarpieceWiredRoute : mSpeakerDockRoute;
        } else {
            // Most recent active route will always be the last in the array
            return mBluetoothRoutes.keySet().stream().toList().get(mBluetoothRoutes.size() - 1);
        }
    }

    private int calculateSupportedRouteMask() {
        int routeMask = CallAudioState.ROUTE_SPEAKER;

        if (mWiredHeadsetManager.isPluggedIn()) {
            routeMask |= CallAudioState.ROUTE_WIRED_HEADSET;
        } else {
            AudioDeviceInfo[] deviceList = mAudioManager.getDevices(
                    AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device: deviceList) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    routeMask |= CallAudioState.ROUTE_EARPIECE;
                    break;
                }
            }
        }
        return routeMask;
    }

    public Set<AudioRoute> getAvailableRoutes() {
        return mAvailableRoutes;
    }

    public AudioRoute getCurrentRoute() {
        return mCurrentRoute;
    }

    private AudioRoute getBluetoothRoute(@AudioRoute.AudioRouteType int audioRouteType,
                                         String address) {
        for (AudioRoute route : mBluetoothRoutes.keySet()) {
            if (route.getType() == audioRouteType && route.getBluetoothAddress().equals(address)) {
                return route;
            }
        }
        return null;
    }

    @VisibleForTesting
    public void setAudioManager(AudioManager audioManager) {
        mAudioManager = audioManager;
    }

    @VisibleForTesting
    public void setAudioRouteFactory(AudioRoute.Factory audioRouteFactory) {
        mAudioRouteFactory = audioRouteFactory;
    }

    @VisibleForTesting
    public void setActive(boolean active) {
        mIsActive = active;
    }
}
