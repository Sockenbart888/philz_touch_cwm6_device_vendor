/*
 * Copyright (c) 2014 The CyanogenMod Project
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 * Also add information on how to contact you by electronic and paper mail.
 *
 */

package org.cyanogenmod.dotcase;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.telephony.TelephonyManager;
import android.util.Log;

class CoverObserver extends UEventObserver {
    private static final String COVER_UEVENT_MATCH = "DEVPATH=/devices/virtual/switch/cover";

    private static final String TAG = "Dotcase";

    private final Context mContext;
    private final WakeLock mWakeLock;
    private final IntentFilter filter = new IntentFilter();
    private PowerManager manager;

    private int oldBrightness = -1;
    private int oldBrightnessMode = -1;
    private boolean needStoreOldBrightness = true;
    private int switchState = 0;

    public static boolean topActivityKeeper = false;

    public CoverObserver(Context context) {
        mContext = context;
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CoverObserver");
        mWakeLock.setReferenceCounted(false);
    }

    public synchronized final void init() {
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction("com.android.deskclock.ALARM_ALERT");
        // add other alarm apps here

        manager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        startObserving(COVER_UEVENT_MATCH);
    }

    @Override
    public void onUEvent(UEventObserver.UEvent event) {
        try {
            switchState = Integer.parseInt(event.get("SWITCH_STATE"));
            boolean screenOn = manager.isScreenOn();
            topActivityKeeper = false;

            if (switchState == 1) {
                if (screenOn) {
                    manager.goToSleep(SystemClock.uptimeMillis());
                }
            } else {
                killActivity();
                if (!screenOn) {
                    manager.wakeUp(SystemClock.uptimeMillis());
                }
            }

            mWakeLock.acquire();
            mHandler.sendMessageDelayed(mHandler.obtainMessage(switchState), 0);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing SWITCH_STATE event", e);
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                mContext.getApplicationContext().registerReceiver(receiver, filter);
            } else {
                mContext.getApplicationContext().unregisterReceiver(receiver);
            }
            mWakeLock.release();
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // If the case is open, don't try to do any of this
            if (switchState == 0) {
                return;
            }
            Intent i = new Intent();
            if (intent.getAction().equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                if (state.equals("RINGING")) {
                    Dotcase.ringing = true;
                    Dotcase.reset_timer = true;
                    topActivityKeeper = true;
                    Dotcase.ringCounter = 0;
                    Dotcase.phoneNumber =
                            intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                    new Thread(new ensureTopActivity()).start();
                } else {
                    topActivityKeeper = false;
                    Dotcase.ringing = false;
                    Dotcase.phoneNumber = "";
                }
            } else if(intent.getAction().equals("com.android.deskclock.ALARM_ALERT")) {
                // add other alarm apps here
                Dotcase.alarm_clock = true;
                Dotcase.reset_timer = true;
                topActivityKeeper = true;
                new Thread(new ensureTopActivity()).start();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                crankUpBrightness();
                Dotcase.checkNotifications();
                Dotcase.reset_timer = true;
                intent.setAction(DotcaseConstants.ACTION_REDRAW);
                mContext.sendBroadcast(intent);
                i.setClassName("org.cyanogenmod.dotcase", "org.cyanogenmod.dotcase.Dotcase");
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(i);
            }
        }
    };

    private void crankUpBrightness() {
        if (needStoreOldBrightness) {
            try {
                oldBrightness = Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS);
                oldBrightnessMode = Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE);
            } catch (Settings.SettingNotFoundException e) {
                Log.e(TAG, "Error retrieving brightness settings", e);
            }

            needStoreOldBrightness = false;
        }

        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, 255);
    }

    public void killActivity() {
        Dotcase.ringing = false;
        Dotcase.alarm_clock = false;
        topActivityKeeper = false;
        if (oldBrightnessMode != -1 && oldBrightness != -1 && !needStoreOldBrightness) {
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    oldBrightnessMode);
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS,
                    oldBrightness);
            needStoreOldBrightness = true;
        }

        Intent i = new Intent();
        i.setAction(DotcaseConstants.ACTION_KILL_ACTIVITY);
        mContext.sendBroadcast(i);
    }

    private class ensureTopActivity implements Runnable {
        Intent i = new Intent();

        @Override
        public void run() {
            while ((Dotcase.ringing || Dotcase.alarm_clock) && topActivityKeeper) {
                ActivityManager am =
                        (ActivityManager) mContext.getSystemService(Activity.ACTIVITY_SERVICE);
                if (!am.getRunningTasks(1).get(0).topActivity.getPackageName().equals(
                        "org.cyanogenmod.dotcase")) {
                    i.setClassName("org.cyanogenmod.dotcase", "org.cyanogenmod.dotcase.Dotcase");
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(i);
                }
                try {
                    Thread.sleep(100);
                } catch (IllegalArgumentException e) {
                    // This isn't going to happen
                } catch (InterruptedException e) {
                    Log.i(TAG, "Sleep interrupted", e);
                }
            }
        }
    }
}
