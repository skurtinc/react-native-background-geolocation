package com.marianhello.utils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Created by jackwink on 6/23/17.
 */

public class LocationTools {

    /**
     * Returns True if the app has location permissions, False otherwise
     * @param context App or Activity context
     * @return True if the app has location permissions, False otherwise
     */
    public static boolean hasLocationPermissions(Context context) {
        String[] permissionGroup = {
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        return PermissionTools.hasPermissions(context, permissionGroup);
    }

    public static boolean isLocationEnabled(Context context) throws Settings.SettingNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return apiKitKatPlus_isLocationEnabled(context);
        }
        return apiLegacy_isLocationEnabled(context);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private static boolean apiKitKatPlus_isLocationEnabled(Context context) throws Settings.SettingNotFoundException {
        int locationMode = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.LOCATION_MODE);
        return locationMode != Settings.Secure.LOCATION_MODE_OFF;
    }



    private static boolean apiLegacy_isLocationEnabled(Context context) throws Settings.SettingNotFoundException {
        String locationProviders = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
        return !TextUtils.isEmpty(locationProviders);
    }
}
