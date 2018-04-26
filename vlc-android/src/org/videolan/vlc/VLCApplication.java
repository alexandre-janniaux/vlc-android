/*****************************************************************************
 * VLCApplication.java
 *****************************************************************************
 * Copyright © 2010-2013 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/
package org.videolan.vlc;

import android.app.Application;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.ProcessLifecycleOwner;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.preference.PreferenceManager;
import android.support.v4.app.DialogFragment;
import android.support.v4.util.SimpleArrayMap;
import android.util.Log;

import org.videolan.libvlc.Dialog;
import org.videolan.libvlc.util.AndroidUtil;
import org.videolan.medialibrary.Medialibrary;
import org.videolan.vlc.gui.DialogActivity;
import org.videolan.vlc.gui.dialogs.VlcProgressDialog;
import org.videolan.vlc.gui.helpers.AudioUtil;
import org.videolan.vlc.gui.helpers.BitmapCache;
import org.videolan.vlc.gui.helpers.NotificationHelper;
import org.videolan.vlc.util.AndroidDevices;
import org.videolan.vlc.util.Strings;
import org.videolan.vlc.util.Util;
import org.videolan.vlc.util.VLCInstance;
import org.videolan.vlc.util.WorkersKt;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import android.os.SystemClock;
import android.util.Log;


public class VLCApplication extends Application {
    public final static String TAG = "VLC/VLCApplication";

    public final static String ACTION_MEDIALIBRARY_READY = "VLC/VLCApplication";
    private static volatile VLCApplication instance;

    public final static String SLEEP_INTENT = Strings.buildPkgString("SleepIntent");

    public static Calendar sPlayerSleepTime = null;
    private static boolean sTV;
    private static SharedPreferences sSettings;

    private static SimpleArrayMap<String, WeakReference<Object>> sDataMap = new SimpleArrayMap<>();

    private static int sDialogCounter = 0;

    public VLCApplication() {
        super();
        instance = this;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sSettings = PreferenceManager.getDefaultSharedPreferences(this);
        sTV = AndroidDevices.isAndroidTv || (!AndroidDevices.isChromeBook && !AndroidDevices.hasTsp);

        setLocale();

        WorkersKt.runBackground(new Runnable() {
            @Override
            public void run() {

                if (AndroidUtil.isOOrLater) NotificationHelper.createNotificationChannels(VLCApplication.this);
                // Prepare cache folder constants
                AudioUtil.prepareCacheFolder(instance);

                if (!VLCInstance.testCompatibleCPU(instance)) return;
                Dialog.setCallbacks(VLCInstance.get(), mDialogCallbacks);
            }
        });


        final Context ctx = this;

        SystemClock.sleep(5000);
        new Thread(new Runnable() {
            public void run() {
                Log.e(TAG, "Hello from transcode thread");

                LibVLC libvlc = new LibVLC(ctx);
                // HACK debug mediacodec encoder

                Media media = new Media(libvlc, "/sdcard/bbc_news_24-239.35.2.0_dvbsub.ts");
                //media.addOption(":sout=#transcode{acodec=none,vcodec=h264,maxwidth=1920,maxheight=1080,vb=125000,venc=mediacodec_jni}:http{dst=:8089/test.mpg}");//file{mux=avformat{mux=matroska},access=file,dst=/sdcard/test.mkv}");
                media.addOption(":sout=#transcode{acodec=none,vcodec=h264,maxwidth=1920,maxheight=1080,vb=125000,venc=mediacodec_jni}:file{mux=mp4,access=file,dst=/sdcard/test.mp4}");
                media.addOption(":no-audio");

                MediaPlayer player = new MediaPlayer(libvlc);
                player.setMedia(media);
                player.play();

                SystemClock.sleep(70000);
            }
        }).start();

    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setLocale();
    }

    /**
     * Called when the overall system is running low on memory
     */
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w(TAG, "System is running low on memory");

        BitmapCache.getInstance().clear();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.w(TAG, "onTrimMemory, level: "+level);

        BitmapCache.getInstance().clear();
    }

    /**
     * @return the main context of the Application
     */
    public static Context getAppContext() {
        return instance;
    }

    /**
     * @return the main resources from the Application
     */
    public static Resources getAppResources()
    {
        return instance.getResources();
    }

    public static SharedPreferences getSettings() {
        return sSettings;
    }

    public static boolean showTvUi() {
        return sTV || (sSettings != null && sSettings.getBoolean("tv_ui", false));
    }

    public static void storeData(String key, Object data) {
        sDataMap.put(key, new WeakReference<>(data));
    }

    public static Object getData(String key) {
        final WeakReference wr = sDataMap.remove(key);
        return wr != null ? wr.get() : null;
    }

    public static boolean hasData(String key) {
        return sDataMap.containsKey(key);
    }

    public static void clearData() {
        sDataMap.clear();
    }

    Dialog.Callbacks mDialogCallbacks = new Dialog.Callbacks() {
        @Override
        public void onDisplay(Dialog.ErrorMessage dialog) {
            Log.w(TAG, "ErrorMessage "+dialog.getText());
        }

        @Override
        public void onDisplay(Dialog.LoginDialog dialog) {
            final String key = DialogActivity.KEY_LOGIN + sDialogCounter++;
            fireDialog(dialog, key);
        }

        @Override
        public void onDisplay(Dialog.QuestionDialog dialog) {
            if (!Util.byPassChromecastDialog(dialog)) {
                final String key = DialogActivity.KEY_QUESTION + sDialogCounter++;
                fireDialog(dialog, key);
            }
        }

        @Override
        public void onDisplay(Dialog.ProgressDialog dialog) {
            final String key = DialogActivity.KEY_PROGRESS + sDialogCounter++;
            fireDialog(dialog, key);
        }

        @Override
        public void onCanceled(Dialog dialog) {
            if (dialog != null && dialog.getContext() != null) ((DialogFragment)dialog.getContext()).dismiss();
        }

        @Override
        public void onProgressUpdate(Dialog.ProgressDialog dialog) {
            VlcProgressDialog vlcProgressDialog = (VlcProgressDialog) dialog.getContext();
            if (vlcProgressDialog != null && vlcProgressDialog.isVisible()) vlcProgressDialog.updateProgress();
        }
    };

    private void fireDialog(Dialog dialog, String key) {
        storeData(key, dialog);
        startActivity(new Intent(instance, DialogActivity.class).setAction(key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public static Medialibrary getMLInstance() {
        return Medialibrary.getInstance();
    }

    public static void setLocale() {
        if (sSettings == null) PreferenceManager.getDefaultSharedPreferences(instance);
        // Are we using advanced debugging - locale?
        String p = sSettings.getString("set_locale", "");
        if (!p.equals("")) {
            Locale locale;
            // workaround due to region code
            if (p.equals("zh-TW")) {
                locale = Locale.TRADITIONAL_CHINESE;
            } else if(p.startsWith("zh")) {
                locale = Locale.CHINA;
            } else if(p.equals("pt-BR")) {
                locale = new Locale("pt", "BR");
            } else if(p.equals("bn-IN") || p.startsWith("bn")) {
                locale = new Locale("bn", "IN");
            } else {
                /**
                 * Avoid a crash of
                 * java.lang.AssertionError: couldn't initialize LocaleData for locale
                 * if the user enters nonsensical region codes.
                 */
                if(p.contains("-"))
                    p = p.substring(0, p.indexOf('-'));
                locale = new Locale(p);
            }
            Locale.setDefault(locale);
            Configuration config = new Configuration();
            config.locale = locale;
            getAppResources().updateConfiguration(config,
                    getAppResources().getDisplayMetrics());
        }
    }

    /**
     * Check if application is currently displayed
     * @return true if an activity is displayed, false if app is in background.
     */
    public static boolean isForeground() {
        return ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    public void finalize() throws Throwable {
        Log.d(TAG, "Removing VLC APPLICATION");
        super.finalize();
    }
}
