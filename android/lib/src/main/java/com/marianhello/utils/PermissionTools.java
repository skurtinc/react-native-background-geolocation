package com.marianhello.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.support.v4.content.ContextCompat;

/**
 * Created by jackwink on 6/23/17.
 */

public class PermissionTools {

    /**
     * Returns True if the provided permissions are granted, False otherwise
     * @param context Application Context
     * @param permissions Array of permission names, i.e. android.permission.ACCESS_COARSE_LOCATION
     * @return
     */
    public static boolean hasPermissions(Context context, String[] permissions) {
        int granted = 0; // check permissions returns 0 if the permission is granted, -1 otherwise
        for (String permission : permissions) {
            granted |= ContextCompat.checkSelfPermission(context, permission);
        }
        return granted == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns True if the provided permissions are granted, False otherwise
     * @param context Application Context
     * @param permission Permission name to check, i.e. android.permission.ACCESS_COARSE_LOCATION
     * @return
     */
    public static boolean hasPermission(Context context, String permission) {
        String[] permissions = {permission};
        return hasPermissions(context, permissions);
    }
}
