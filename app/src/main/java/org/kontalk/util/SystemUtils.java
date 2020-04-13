/*
 * Kontalk Android client
 * Copyright (C) 2020 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.util;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.ContactsContract;
import androidx.annotation.AttrRes;
import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import org.kontalk.BuildConfig;
import org.kontalk.Kontalk;
import org.kontalk.Log;
import org.kontalk.R;


/**
 * System-related utilities.
 * @author Daniele Ricci
 */
public final class SystemUtils {

    private static final Pattern VERSION_CODE_MATCH = Pattern
        .compile("\\(([0-9]+)\\)$");

    private SystemUtils() {
    }

    public static boolean isOlderVersion(Context context, String version) {
        Matcher m = VERSION_CODE_MATCH.matcher(version);
        if (m.find() && m.groupCount() > 0) {
            try {
                int versionCode = Integer.parseInt(m.group(1));
                int currentVersion = getVersionCode();
                return versionCode < currentVersion;
            }
            catch (Exception ignored) {
            }

        }

        // no version code found at the end - assume older version
        return true;
    }

    public static int getVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    public static String getVersionFullName(Context context) {
        return context.getString(R.string.about_version,
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
    }

    public static Point getDisplaySize(Context context) {
        Point displaySize = null;
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = manager.getDefaultDisplay();
        if (display != null) {
            displaySize = new Point();
            display.getSize(displaySize);
        }

        return displaySize;
    }

    public static int getDisplayRotation(Context context) {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        return manager.getDefaultDisplay().getRotation();
    }

    /**
     * Returns the correct screen orientation based on the supposedly preferred
     * position of the device.
     * http://stackoverflow.com/a/16585072/1045199
     */
    public static int getScreenOrientation(Activity activity) {
        WindowManager windowManager =  (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        Configuration configuration = activity.getResources().getConfiguration();
        int rotation = windowManager.getDefaultDisplay().getRotation();

        // Search for the natural position of the device
        if(configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) ||
            configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270))
        {
            // Natural position is Landscape
            switch (rotation)
            {
                case Surface.ROTATION_0:
                    return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                case Surface.ROTATION_90:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                case Surface.ROTATION_180:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                case Surface.ROTATION_270:
                    return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            }
        }
        else {
            // Natural position is Portrait
            switch (rotation)
            {
                case Surface.ROTATION_0:
                    return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                case Surface.ROTATION_90:
                    return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                case Surface.ROTATION_180:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                case Surface.ROTATION_270:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            }
        }

        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }

    public static void acquireScreenOn(Activity activity) {
        activity.getWindow().addFlags(WindowManager
            .LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public static void releaseScreenOn(Activity activity) {
        activity.getWindow().clearFlags(WindowManager
            .LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Returns the type name of the current network, or null. */
    public static String getCurrentNetworkName(Context context) {
        ConnectivityManager connMgr = (ConnectivityManager) context
            .getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo info = connMgr.getActiveNetworkInfo();
        return info != null ? info.getTypeName() : null;
    }

    public static int getCurrentNetworkType(Context context) {
        ConnectivityManager connMgr = (ConnectivityManager) context
            .getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo info = connMgr.getActiveNetworkInfo();
        return info != null ? info.getType() : -1;
    }

    public static boolean isOnWifi(Context context) {
        return getCurrentNetworkType(context) == ConnectivityManager.TYPE_WIFI;
    }

    /** Checks for network availability. */
    public static boolean isNetworkConnectionAvailable(Context context) {
        final ConnectivityManager cm = (ConnectivityManager) context
            .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm.getBackgroundDataSetting()) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null && info.getState() == NetworkInfo.State.CONNECTED)
                return true;
        }

        return false;
    }

    public static Bitmap getProfilePhoto(Context context) {
        // profile photo is available only since API level 14
        ContentResolver cr = context.getContentResolver();
        InputStream input = ContactsContract.Contacts
            .openContactPhotoInputStream(cr, ContactsContract.Profile.CONTENT_URI);
        if (input != null) {
            try {
                return BitmapFactory.decodeStream(input);
            }
            finally {
                DataUtils.close(input);
            }
        }

        return null;
    }

    /**
     * Returns the system profile Uri. This will be replaced by the users's
     * personal avatar - when there will be such a feature.
     * @param context not used really
     * @return the profile Uri
     */
    public static Uri getProfileUri(Context context) {
        return ContactsContract.Profile.CONTENT_URI;
    }

    public static Uri lookupPhoneNumber(Context context, String phoneNumber) {
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber));
        Cursor cur = context.getContentResolver().query(uri,
            new String[] { ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.LOOKUP_KEY },
            null, null, null);
        if (cur != null) {
            try {
                if (cur.moveToNext()) {
                    long id = cur.getLong(0);
                    String lookupKey = cur.getString(1);
                    return ContactsContract.Contacts.getLookupUri(id, lookupKey);
                }
            }
            finally {
                cur.close();
            }
        }

        return null;
    }

    public static void openURL(Context context, String url) {
        try {
            context.startActivity(externalIntent(Intent.ACTION_VIEW,
                Uri.parse(url)));
        }
        catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.chooser_error_no_browser,
                Toast.LENGTH_LONG).show();
        }
    }

    public static void call(final Context context, final CharSequence phone) {
        try {
            context.startActivity(externalIntent(Intent.ACTION_CALL,
                Uri.parse("tel:" + phone)));
        }
        catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.chooser_error_no_dialer,
                Toast.LENGTH_LONG).show();
        }
    }

    public static void dial(Context context, CharSequence phone) {
        try {
            context.startActivity(externalIntent(Intent.ACTION_DIAL,
                Uri.parse("tel:" + phone)));
        }
        catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.chooser_error_no_dialer,
                Toast.LENGTH_LONG).show();
        }
    }

    public static boolean isCallable(Context context, Intent intent) {
        List<ResolveInfo> list = context.getPackageManager().queryIntentActivities(intent,
            PackageManager.MATCH_DEFAULT_ONLY);
        return list.size() > 0;
    }

    public static Intent externalIntent(String action) {
        return externalIntent(action, null);
    }

    public static Intent externalIntent(String action, Uri data) {
        Intent i = new Intent(action, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        }
        else {
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        }
        return i;
    }

    public static String getUserSerial(Context context) {
        //noinspection ResourceType
        @SuppressLint("WrongConstant")
        Object userManager = context.getSystemService("user");
        if (null == userManager) return "";

        try {
            Method myUserHandleMethod = android.os.Process.class.getMethod("myUserHandle", (Class<?>[]) null);
            Object myUserHandle = myUserHandleMethod.invoke(android.os.Process.class, (Object[]) null);
            Method getSerialNumberForUser = userManager.getClass().getMethod("getSerialNumberForUser", myUserHandle.getClass());
            Long userSerial = (Long) getSerialNumberForUser.invoke(userManager, myUserHandle);
            if (userSerial != null) {
                return String.valueOf(userSerial);
            } else {
                return "";
            }
        }
        catch (Exception ignored) {
        }
        return "";
    }

    public static CharacterStyle getColoredSpan(Context context, @ColorRes int colorResId) {
        return new ForegroundColorSpan(ContextCompat.getColor(context, colorResId));
    }

    public static CharacterStyle getTypefaceSpan(int typeface) {
        return new StyleSpan(typeface);
    }

    public static int getThemedResource(Context context, @AttrRes int attrResId) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attrResId, value, true))
            throw new Resources.NotFoundException();
        return value.resourceId;
    }

    public static ProximityScreenLocker createProximityScreenLocker(final Activity activity)
    {
        final ProximityScreenLocker proximityScreenLockerNative = ProximityScreenLockerNative.create(activity);
        if (proximityScreenLockerNative == null) {
            Log.d(Kontalk.TAG, "native proximity screen locking is not supported => using fallback");
            return new ProximityScreenLockerFallback(activity);
        }

        Log.d(Kontalk.TAG, "native proximity screen locking is supported");
        return proximityScreenLockerNative;
    }

    public static PowerManager.WakeLock createPartialWakeLock(Context context, String tag, boolean referenceCounted) {
        PowerManager pwm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = pwm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        lock.setReferenceCounted(referenceCounted);
        return lock;
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return true;

        PowerManager pwm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pwm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static boolean supportsJobScheduler() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    public static boolean supportsMultiWindow() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    /** Return true if the platform can and will broadcast network state change *implicit* intents. */
    public static boolean isReceivingNetworkStateChanges() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.N;
    }

    public static boolean supportsScopedStorage() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

}
