package com.marianhello.react;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.LocationService;
import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.LocationDAO;
import com.marianhello.logging.DBLogReader;
import com.marianhello.logging.LogEntry;
import com.marianhello.logging.LogReader;
import com.marianhello.logging.LoggerManager;
import com.marianhello.utils.Convert;
import com.marianhello.utils.ErrorConstants;
import com.marianhello.utils.LocationTools;
import com.marianhello.utils.ServiceTools;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.annotation.Nullable;

public class BackgroundGeolocationModule extends ReactContextBaseJavaModule implements LifecycleEventListener {

    public static final String LOCATION_EVENT = "location";
    public static final String STATIONARY_EVENT = "stationary";
    public static final String ERROR_EVENT = "error";
    private static final Integer MESSENGER_CLIENT_ID = 666;

    /** Messenger for communicating with the service. */
    private Messenger mService = null;
    /** Flag indicating whether we have called bind on the service. */
    private Boolean mIsBound = false;

    private Boolean mIsLocationModeChangeReceiverRegistered = false;

    private LocationDAO mDao;
    private Config mConfig;

    private org.slf4j.Logger log;

    Messenger mMessenger;

    public BackgroundGeolocationModule(ReactApplicationContext reactContext) {
        super(reactContext);
        reactContext.addLifecycleEventListener(this);

        LoggerManager.enableDBLogging();
        log = LoggerManager.getLogger(BackgroundGeolocationModule.class);
        log.info("initializing plugin");
    }

    /* Overrides / Lifecycle */

    @Override
    public String getName() {
        return "BackgroundGeolocation";
    }

    @Override
    public void onHostResume() {
        log.info("App will be resumed");
    }

    @Override
    public void onHostPause() {
        log.info("App will be paused");
    }

    @Override
    public void onHostDestroy() {
        log.info("Destroying plugin");

        //TODO: unregisterLocationModeChangeReceiver();

        // Unbind from the service
        doUnbindService();
        if (mConfig != null && mConfig.getStopOnTerminate()) {
            stopBackgroundService();
        }
    }

    /* Javascript API */

    @ReactMethod
    public void configure(ReadableMap options, Callback success, Callback error) {
        Config config = new Config();
        if (options.hasKey("stationaryRadius")) config.setStationaryRadius((float) options.getDouble("stationaryRadius"));
        if (options.hasKey("distanceFilter")) config.setDistanceFilter(options.getInt("distanceFilter"));
        if (options.hasKey("desiredAccuracy")) config.setDesiredAccuracy(options.getInt("desiredAccuracy"));
        if (options.hasKey("debug")) config.setDebugging(options.getBoolean("debug"));
        if (options.hasKey("notificationTitle")) config.setNotificationTitle(options.getString("notificationTitle"));
        if (options.hasKey("notificationText")) config.setNotificationText(options.getString("notificationText"));
        if (options.hasKey("notificationIconLarge")) config.setLargeNotificationIcon(options.getString("notificationIconLarge"));
        if (options.hasKey("notificationIconSmall")) config.setSmallNotificationIcon(options.getString("notificationIconSmall"));
        if (options.hasKey("notificationIconColor")) config.setNotificationIconColor(options.getString("notificationIconColor"));
        if (options.hasKey("stopOnTerminate")) config.setStopOnTerminate(options.getBoolean("stopOnTerminate"));
        if (options.hasKey("startOnBoot")) config.setStartOnBoot(options.getBoolean("startOnBoot"));
        if (options.hasKey("startForeground")) config.setStartForeground(options.getBoolean("startForeground"));
        if (options.hasKey("locationProvider")) config.setLocationProvider(options.getInt("locationProvider"));
        if (options.hasKey("interval")) config.setInterval(options.getInt("interval"));
        if (options.hasKey("fastestInterval")) config.setFastestInterval(options.getInt("fastestInterval"));
        if (options.hasKey("activitiesInterval")) config.setActivitiesInterval(options.getInt("activitiesInterval"));
        if (options.hasKey("stopOnStillActivity")) config.setStopOnStillActivity(options.getBoolean("stopOnStillActivity"));
        if (options.hasKey("url")) config.setUrl(options.getString("url"));
        if (options.hasKey("httpHeaders")) {
            HashMap httpHeaders = new HashMap<String, String>();
            ReadableMap rm = options.getMap("httpHeaders");
            ReadableMapKeySetIterator it = rm.keySetIterator();

            while (it.hasNextKey()) {
                String key = it.nextKey();
                httpHeaders.put(key, rm.getString(key));
            }

            config.setHttpHeaders(httpHeaders);
        }

        try {
            persistConfiguration(config);
        } catch (NullPointerException e) {
            log.error("Configuration error: {}", e.getMessage());
            error.invoke("Configuration error: " + e.getMessage());
            return;
        }

        this.mConfig = config;
        success.invoke(true);
    }

    @ReactMethod
    public void start(Callback success, Callback error) {
        if (mConfig == null) {
            log.warn("Attempt to start unconfigured service");
            error.invoke("Plugin not configured. Please call configure method first.");
            return;
        }

        try {
            if (hasRequiredPermissions()) {
                log.info("Requesting permissions from user");
                startAndBindBackgroundService();
                success.invoke(true);
            } else {
                //TODO: requestPermissions
                log.warn("Attempt to start service without location permissions!");
                error.invoke(ErrorConstants.MISSING_PERMISSION);
            }
        } catch (Exception e) {
            log.error("Error starting location service: {}", e.getMessage());
            error.invoke(ErrorConstants.GENERIC_ERROR + e.getMessage());
        }
    }

    @ReactMethod
    public void stop(Callback success, Callback error) {
        try {
            doUnbindService();
            stopBackgroundService();
            success.invoke(true);
        } catch (Exception e) {
            log.error("Error stopping location service: {}", e.getMessage());
            error.invoke(ErrorConstants.GENERIC_ERROR + e.getMessage());
        }
    }

    @ReactMethod
    public void isLocationEnabled(Callback success, Callback error) {
        log.debug("Location services enabled check");
        try {
            success.invoke(isGPSEnabled());
        } catch (Exception e) {
            log.error("Error checking location enabled: {}", e.getMessage());
            error.invoke(ErrorConstants.GENERIC_ERROR + e.getMessage());
        }
    }

    /**
     * Launches an activity to show settings for the application. If the app has been backgrounded
     * this will invoke the error callback, otherwise it'll launch the settings activity and
     * and call success.
     *
     * @param success React success callback
     * @param error React error callback
     */
    @ReactMethod
    public void showAppSettings(Callback success, Callback error) {
        Activity activity = getActivity();
        if (activity == null) {
            error.invoke(ErrorConstants.NO_ACTIVITY);
            return;
        }

        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.setData(Uri.parse("package:" + activity.getApplicationContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

            activity.startActivity(intent);
            success.invoke(true);
        } catch (Exception e) {
            log.warn(ErrorConstants.GENERIC_ERROR + e.getMessage());
            error.invoke(ErrorConstants.GENERIC_ERROR + e.getMessage());
        }
    }

    @ReactMethod
    public void showLocationSettings(Callback success, Callback error) {
        Activity activity = getActivity();
        if (activity == null) {
            error.invoke(ErrorConstants.NO_ACTIVITY);
            return;
        }

        try {
            activity.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            success.invoke(true);
        } catch (Exception e) {
            log.warn(ErrorConstants.GENERIC_ERROR + e.getMessage());
            error.invoke(ErrorConstants.GENERIC_ERROR + e.getMessage());
        }
    }

    @ReactMethod
    public void watchLocationMode(Callback success, Callback error) {
        //TODO: implement
        error.invoke(ErrorConstants.NOT_IMPLEMENTED);
    }

    @ReactMethod
    public void stopWatchingLocationMode(Callback success, Callback error) {
        //TODO: implement
        error.invoke(ErrorConstants.NOT_IMPLEMENTED);
    }

    @ReactMethod
    public void getLocations(Callback success, Callback error) {
        WritableArray locationsArray = Arguments.createArray();
        LocationDAO dao = DAOFactory.createLocationDAO(getReactApplicationContext());
        try {
            Collection<BackgroundLocation> locations = dao.getAllLocations();
            for (BackgroundLocation location : locations) {
                WritableMap out = Arguments.createMap();
                Long locationId = location.getLocationId();
                Integer locationProvider = location.getLocationProvider();
                if (locationId != null) out.putInt("locationId", Convert.safeLongToInt(locationId));
                if (locationProvider != null) out.putInt("locationProvider", locationProvider);
                out.putDouble("time", new Long(location.getTime()).doubleValue());
                out.putDouble("latitude", location.getLatitude());
                out.putDouble("longitude", location.getLongitude());
                out.putDouble("accuracy", location.getAccuracy());
                out.putDouble("speed", location.getSpeed());
                out.putDouble("altitude", location.getAltitude());
                out.putDouble("bearing", location.getBearing());

                locationsArray.pushMap(out);
            }
            success.invoke(locationsArray);
        } catch (Exception e) {
            log.error("Getting all locations failed: {}", e.getMessage());
            error.invoke("Converting locations to JSON failed.");
        }
    }

    @ReactMethod
    public void switchMode(ReadableMap options, Callback success, Callback error) {
        //TODO: implement
        error.invoke("Not implemented yet");
    }

    @ReactMethod
    public void getConfig(Callback success, Callback error) {
        ConfigurationDAO dao = DAOFactory.createConfigurationDAO(getReactApplicationContext());
        try {
            Config config = dao.retrieveConfiguration();
            WritableMap json = Arguments.createMap();
            WritableMap httpHeaders = Arguments.createMap();
            json.putDouble("stationaryRadius", config.getStationaryRadius());
            json.putInt("distanceFilter", config.getDistanceFilter());
            json.putInt("desiredAccuracy", config.getDesiredAccuracy());
            json.putBoolean("debug", config.isDebugging());
            json.putString("notificationTitle", config.getNotificationTitle());
            json.putString("notificationText", config.getNotificationText());
            json.putString("notificationIconLarge", config.getLargeNotificationIcon());
            json.putString("notificationIconSmall", config.getSmallNotificationIcon());
            json.putString("notificationIconColor", config.getNotificationIconColor());
            json.putBoolean("stopOnTerminate", config.getStopOnTerminate());
            json.putBoolean("startOnBoot", config.getStartOnBoot());
            json.putBoolean("startForeground", config.getStartForeground());
            json.putInt("locationProvider", config.getLocationProvider());
            json.putInt("interval", config.getInterval());
            json.putInt("fastestInterval", config.getFastestInterval());
            json.putInt("activitiesInterval", config.getActivitiesInterval());
            json.putBoolean("stopOnStillActivity", config.getStopOnStillActivity());
            json.putString("url", config.getUrl());
            json.putString("syncUrl", config.getSyncUrl());
            json.putInt("syncThreshold", config.getSyncThreshold());
            // httpHeaders
            Iterator<Map.Entry<String, String>> it = config.getHttpHeaders().entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> pair = it.next();
                httpHeaders.putString(pair.getKey(), pair.getValue());
            }
            json.putMap("httpHeaders", httpHeaders);
            json.putInt("maxLocations", config.getMaxLocations());

            success.invoke(json);
        } catch (Exception e) {
            log.error("Error getting config: {}", e.getMessage());
            error.invoke("Error getting config: " + e.getMessage());
        }
    }

    @ReactMethod
    public void getLogEntries(int limit, Callback success, Callback error) {
        LogReader logReader = new DBLogReader();
        WritableArray logEntriesArray = Arguments.createArray();
        Collection<LogEntry> logEntries = logReader.getEntries(limit);
        for (LogEntry logEntry : logEntries) {
            WritableMap out = Arguments.createMap();
            out.putInt("context", logEntry.getContext());
            out.putString("level", logEntry.getLevel());
            out.putString("message", logEntry.getMessage());
            out.putString("timestamp", new Long(logEntry.getTimestamp()).toString());
            out.putString("logger", logEntry.getLoggerName());

            logEntriesArray.pushMap(out);
        }

        success.invoke(logEntriesArray);
    }

    /* Helpers */

    private void sendEvent(ReactContext reactContext, String eventName, @Nullable WritableMap params) {
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }


    protected boolean hasRequiredPermissions() {
        return LocationTools.hasLocationPermissions(getReactApplicationContext());
    }

    protected void startAndBindBackgroundService() {
        startBackgroundService();
        doBindService();
    }

    protected void startBackgroundService() {
        if (isLocationServiceRunning()) { return; }

        Context c = getReactApplicationContext();
        Intent locationServiceIntent = new Intent(c, LocationService.class);
        locationServiceIntent.putExtra("config", mConfig);
        locationServiceIntent.addFlags(Intent.FLAG_FROM_BACKGROUND);
        // start service to keep service running even if no clients are bound to it
        c.startService(locationServiceIntent);
    }

    protected void stopBackgroundService() {
        if (!isLocationServiceRunning()) { return; }

        log.info("Stopping bg service");
        Context c = getReactApplicationContext();
        c.stopService(new Intent(c, LocationService.class));
    }

    void doBindService() {
        // Establish a connection with the service.  We use an explicit
        // class name because there is no reason to be able to let other
        // applications replace our component.
        if (mIsBound) { return; }

        mMessenger = new Messenger(new IncomingHandler());

        Context c = getReactApplicationContext();
        Intent locationServiceIntent = new Intent(c, LocationService.class);
        locationServiceIntent.putExtra("config", mConfig);
        c.bindService(locationServiceIntent, mConnection, Context.BIND_IMPORTANT);
    }

    void doUnbindService () {
        if (mIsBound) {
            // If we have received the service, and hence registered with
            // it, then now is the time to unregister.
            if (mService != null) {
                try {
                    Message msg = Message.obtain(null,
                            LocationService.MSG_UNREGISTER_CLIENT);
                    msg.replyTo = mMessenger;
                    msg.arg1 = MESSENGER_CLIENT_ID;
                    mService.send(msg);
                } catch (RemoteException e) {
                    // There is nothing special we need to do if the service
                    // has crashed.
                }

                // Detach our existing connection.
                getReactApplicationContext().unbindService(mConnection);
                mIsBound = false;
            }
        }
    }

    protected Activity getActivity() {
        return this.getCurrentActivity();
    }

    private void persistConfiguration(Config config) throws NullPointerException {
        ConfigurationDAO dao = DAOFactory.createConfigurationDAO(getReactApplicationContext());
        dao.persistConfiguration(config);
    }

    /**
     * Returns True if our location service is running, False otherwise
     * @return True if our location service is running, False otherwise
     */
    private boolean isLocationServiceRunning() {
        return ServiceTools.isServiceRunning(getReactApplicationContext(),
                LocationService.class.getName());
    }

    /**
     * Returns True if the GPS is enabled, False if it's off or if the device doesn't have a
     * GPS setting.
     *
     * @return True if the GPS is enabled, False otherwise
     */
    private boolean isGPSEnabled () {
        try {
            return LocationTools.isLocationEnabled(getReactApplicationContext());
        } catch (SettingNotFoundException e) {
            log.error("Location settings not found: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Handler of incoming messages from service.
     */
    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case LocationService.MSG_LOCATION_UPDATE:
                    try {
                        log.debug("Sending location to webview");
                        Bundle bundle = msg.getData();
                        bundle.setClassLoader(LocationService.class.getClassLoader());
                        BackgroundLocation location = (BackgroundLocation) bundle.getParcelable("location");
                        Integer locationProvider = location.getLocationProvider();

                        WritableMap out = Arguments.createMap();
                        if (locationProvider != null) out.putInt("locationProvider", locationProvider);
                        out.putDouble("time", new Long(location.getTime()).doubleValue());
                        out.putDouble("latitude", location.getLatitude());
                        out.putDouble("longitude", location.getLongitude());
                        out.putDouble("accuracy", location.getAccuracy());
                        out.putDouble("speed", location.getSpeed());
                        out.putDouble("altitude", location.getAltitude());
                        out.putDouble("bearing", location.getBearing());

                        sendEvent(getReactApplicationContext(), LOCATION_EVENT, out);
                    } catch (Exception e) {
                        log.warn("Error converting message to json");

                        WritableMap out = Arguments.createMap();
                        out.putString("message", "Error converting message to json");
                        out.putString("detail", e.getMessage());

                        sendEvent(getReactApplicationContext(), ERROR_EVENT, out);
                    }

                    break;
                case LocationService.MSG_ON_STATIONARY:
                    try {
                        log.debug("Sending stationary location to webview");
                        Bundle bundle = msg.getData();
                        bundle.setClassLoader(LocationService.class.getClassLoader());
                        BackgroundLocation location = (BackgroundLocation) bundle.getParcelable("location");
                        Integer locationProvider = location.getLocationProvider();

                        WritableMap out = Arguments.createMap();
                        if (locationProvider != null) out.putInt("locationProvider", locationProvider);
                        out.putDouble("time", new Long(location.getTime()).doubleValue());
                        out.putDouble("latitude", location.getLatitude());
                        out.putDouble("longitude", location.getLongitude());
                        out.putDouble("accuracy", location.getAccuracy());
                        out.putDouble("speed", location.getSpeed());
                        out.putDouble("altitude", location.getAltitude());
                        out.putDouble("bearing", location.getBearing());

                        sendEvent(getReactApplicationContext(), STATIONARY_EVENT, out);
                    } catch (Exception e) {
                        log.warn("Error converting message to json");

                        WritableMap out = Arguments.createMap();
                        out.putString("message", "Error converting message to json");
                        out.putString("detail", e.getMessage());

                        sendEvent(getReactApplicationContext(), ERROR_EVENT, out);
                    }

                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    /**
     * Class for interacting with the main interface of the service.
     */
    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the object we can use to
            // interact with the service.  We are communicating with the
            // service using a Messenger, so here we get a client-side
            // representation of that from the raw IBinder object.
            mService = new Messenger(service);
            mIsBound = true;

            // We want to monitor the service for as long as we are
            // connected to it.
            try {
                Message msg = Message.obtain(null,
                        LocationService.MSG_REGISTER_CLIENT);
                msg.replyTo = mMessenger;
                msg.arg1 = MESSENGER_CLIENT_ID;
                mService.send(msg);
            } catch (RemoteException e) {
                // In this case the service has crashed before we could even
                // do anything with it; we can count on soon being
                // disconnected (and then reconnected if it can be restarted)
                // so there is no need to do anything here.
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            mService = null;
            mIsBound = false;
        }
    };
}
