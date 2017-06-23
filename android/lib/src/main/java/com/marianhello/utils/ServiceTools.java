package com.marianhello.utils;

import android.app.ActivityManager;
import android.content.Context;

import java.util.List;


public class ServiceTools {
    private final static String TAG = ServiceTools.class.getName();

    /**
     * Returns True if a provided service name is running on the system, otherwise returns False.
     * @param context Application context
     * @param serviceClassName Service name to check
     * @return True if running, False otherwise.
     */
    public static boolean isServiceRunning(Context appContext, String serviceClassName) {
        final ActivityManager activityManager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        final List<ActivityManager.RunningServiceInfo> services = activityManager.getRunningServices(Integer.MAX_VALUE);

        for (ActivityManager.RunningServiceInfo runningServiceInfo : services) {
            if (runningServiceInfo.service.getClassName().equals(serviceClassName)){
                return true;
            }
        }
        return false;
    }
}
