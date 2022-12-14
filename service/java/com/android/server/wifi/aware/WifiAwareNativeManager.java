/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.aware;

import android.annotation.NonNull;
import android.hardware.wifi.V1_0.IWifiNanIface;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.os.Handler;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.HalDeviceManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Manages the interface to Wi-Fi Aware HIDL (HAL).
 */
public class WifiAwareNativeManager {
    private static final String TAG = "WifiAwareNativeManager";
    private boolean mDbg = false;

    // to be used for synchronizing access to any of the WifiAwareNative objects
    private final Object mLock = new Object();

    private WifiAwareStateManager mWifiAwareStateManager;
    private HalDeviceManager mHalDeviceManager;
    private Handler mHandler;
    private WifiAwareNativeCallback mWifiAwareNativeCallback;
    private IWifiNanIface mWifiNanIface = null;
    private InterfaceDestroyedListener mInterfaceDestroyedListener;
    private int mReferenceCount = 0;

    WifiAwareNativeManager(WifiAwareStateManager awareStateManager,
            HalDeviceManager halDeviceManager,
            WifiAwareNativeCallback wifiAwareNativeCallback) {
        mWifiAwareStateManager = awareStateManager;
        mHalDeviceManager = halDeviceManager;
        mWifiAwareNativeCallback = wifiAwareNativeCallback;
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(boolean verbose) {
        mDbg = verbose;
    }

    /**
     * (HIDL) Cast the input to a 1.2 NAN interface (possibly resulting in a null).
     *
     * Separate function so can be mocked in unit tests.
     */
    public android.hardware.wifi.V1_2.IWifiNanIface mockableCastTo_1_2(IWifiNanIface iface) {
        return android.hardware.wifi.V1_2.IWifiNanIface.castFrom(iface);
    }

    /**
     * (HIDL) Cast the input to a 1.5 NAN interface (possibly resulting in a null).
     *
     * Separate function so can be mocked in unit tests.
     */
    public android.hardware.wifi.V1_5.IWifiNanIface mockableCastTo_1_5(IWifiNanIface iface) {
        return android.hardware.wifi.V1_5.IWifiNanIface.castFrom(iface);
    }

    /**
     * (HIDL) Cast the input to a 1.6 NAN interface (possibly resulting in a null).
     *
     * Separate function so can be mocked in unit tests.
     */
    public android.hardware.wifi.V1_6.IWifiNanIface mockableCastTo_1_6(IWifiNanIface iface) {
        return android.hardware.wifi.V1_6.IWifiNanIface.castFrom(iface);
    }

    /**
     * Initialize the class - intended for late initialization.
     *
     * @param handler Handler on which to execute interface available callbacks.
     */
    public void start(Handler handler) {
        mHandler = handler;
        mHalDeviceManager.initialize();
        mHalDeviceManager.registerStatusListener(
                new HalDeviceManager.ManagerStatusListener() {
                    @Override
                    public void onStatusChanged() {
                        if (mDbg) Log.v(TAG, "onStatusChanged");
                        // only care about isStarted (Wi-Fi started) not isReady - since if not
                        // ready then Wi-Fi will also be down.
                        if (mHalDeviceManager.isStarted()) {
                            mWifiAwareStateManager.tryToGetAwareCapability();
                        } else {
                            awareIsDown(false);
                        }
                    }
                }, mHandler);
        if (mHalDeviceManager.isStarted()) {
            mWifiAwareStateManager.tryToGetAwareCapability();
        }
    }

    /**
     * Returns the native HAL WifiNanIface through which commands to the NAN HAL are dispatched.
     * Return may be null if not initialized/available.
     */
    @VisibleForTesting
    public IWifiNanIface getWifiNanIface() {
        synchronized (mLock) {
            return mWifiNanIface;
        }
    }

    /**
     * Attempt to obtain the HAL NAN interface.
     */
    public void tryToGetAware(@NonNull WorkSource requestorWs) {
        synchronized (mLock) {
            if (mDbg) {
                Log.d(TAG, "tryToGetAware: mWifiNanIface=" + mWifiNanIface + ", mReferenceCount="
                        + mReferenceCount + ", requestorWs=" + requestorWs);
            }

            if (mWifiNanIface != null) {
                mReferenceCount++;
                return;
            }
            if (mHalDeviceManager == null) {
                Log.e(TAG, "tryToGetAware: mHalDeviceManager is null!?");
                awareIsDown(false);
                return;
            }

            mInterfaceDestroyedListener = new InterfaceDestroyedListener();
            IWifiNanIface iface = mHalDeviceManager.createNanIface(mInterfaceDestroyedListener,
                    mHandler, requestorWs);
            if (iface == null) {
                Log.e(TAG, "Was not able to obtain an IWifiNanIface (even though enabled!?)");
                awareIsDown(true);
            } else {
                if (mDbg) Log.v(TAG, "Obtained an IWifiNanIface");

                try {
                    android.hardware.wifi.V1_2.IWifiNanIface iface12 = mockableCastTo_1_2(iface);
                    android.hardware.wifi.V1_5.IWifiNanIface iface15 = mockableCastTo_1_5(iface);
                    android.hardware.wifi.V1_6.IWifiNanIface iface16 = mockableCastTo_1_6(iface);
                    WifiStatus status;
                    if (iface16 != null) {
                        mWifiAwareNativeCallback.mIsHal12OrLater = true;
                        mWifiAwareNativeCallback.mIsHal15OrLater = true;
                        mWifiAwareNativeCallback.mIsHal16OrLater = true;
                        status = iface16.registerEventCallback_1_6(mWifiAwareNativeCallback);
                    } else if (iface15 != null) {
                        mWifiAwareNativeCallback.mIsHal12OrLater = true;
                        mWifiAwareNativeCallback.mIsHal15OrLater = true;
                        status = iface15.registerEventCallback_1_5(mWifiAwareNativeCallback);
                    } else if (iface12 != null) {
                        mWifiAwareNativeCallback.mIsHal12OrLater = true;
                        status = iface12.registerEventCallback_1_2(mWifiAwareNativeCallback);
                    } else {
                        status = iface.registerEventCallback(mWifiAwareNativeCallback);
                    }
                    if (status.code != WifiStatusCode.SUCCESS) {
                        Log.e(TAG, "IWifiNanIface.registerEventCallback error: " + statusString(
                                status));
                        mHalDeviceManager.removeIface(iface);
                        awareIsDown(false);
                        return;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "IWifiNanIface.registerEventCallback exception: " + e);
                    awareIsDown(false);
                    return;
                }
                mWifiNanIface = iface;
                mReferenceCount = 1;
            }
        }
    }

    /**
     * Release the HAL NAN interface.
     */
    public void releaseAware() {
        if (mDbg) {
            Log.d(TAG, "releaseAware: mWifiNanIface=" + mWifiNanIface + ", mReferenceCount="
                    + mReferenceCount);
        }

        if (mWifiNanIface == null) {
            return;
        }
        if (mHalDeviceManager == null) {
            Log.e(TAG, "releaseAware: mHalDeviceManager is null!?");
            return;
        }

        synchronized (mLock) {
            mReferenceCount--;
            if (mReferenceCount != 0) {
                return;
            }
            mInterfaceDestroyedListener.active = false;
            mInterfaceDestroyedListener = null;
            mHalDeviceManager.removeIface(mWifiNanIface);
            mWifiNanIface = null;
            mWifiAwareNativeCallback.resetChannelInfo();
        }
    }

    /**
     * Replace requestorWs in-place when iface is already enabled.
     */
    public boolean replaceRequestorWs(@NonNull WorkSource requestorWs) {
        synchronized (mLock) {
            if (mDbg) {
                Log.d(TAG, "replaceRequestorWs: mWifiNanIface=" + mWifiNanIface
                        + ", mReferenceCount=" + mReferenceCount + ", requestorWs=" + requestorWs);
            }

            if (mWifiNanIface == null) {
                return false;
            }
            if (mHalDeviceManager == null) {
                Log.e(TAG, "tryToGetAware: mHalDeviceManager is null!?");
                awareIsDown(false);
                return false;
            }

            return mHalDeviceManager.replaceRequestorWs(mWifiNanIface, requestorWs);
        }
    }

    private void awareIsDown(boolean markAsAvailable) {
        synchronized (mLock) {
            if (mDbg) {
                Log.d(TAG, "awareIsDown: mWifiNanIface=" + mWifiNanIface + ", mReferenceCount ="
                        + mReferenceCount);
            }
            mWifiNanIface = null;
            mReferenceCount = 0;
            mWifiAwareStateManager.disableUsage(markAsAvailable);
        }
    }

    private class InterfaceDestroyedListener implements
            HalDeviceManager.InterfaceDestroyedListener {
        public boolean active = true;

        @Override
        public void onDestroyed(@NonNull String ifaceName) {
            if (mDbg) {
                Log.d(TAG, "Interface was destroyed: mWifiNanIface=" + mWifiNanIface + ", active="
                        + active);
            }
            if (active && mWifiNanIface != null) {
                awareIsDown(true);
            } // else: we released it locally so no need to disable usage
        }
    }

    private static String statusString(WifiStatus status) {
        if (status == null) {
            return "status=null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(status.code).append(" (").append(status.description).append(")");
        return sb.toString();
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WifiAwareNativeManager:");
        pw.println("  mWifiNanIface: " + mWifiNanIface);
        pw.println("  mReferenceCount: " + mReferenceCount);
        mWifiAwareNativeCallback.dump(fd, pw, args);
    }
}
