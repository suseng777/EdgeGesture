package com.omarea.gesture;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.omarea.gesture.ui.FloatVirtualTouchBar;
import com.omarea.gesture.ui.QuickPanel;
import com.omarea.gesture.ui.TouchIconCache;
import com.omarea.gesture.util.GlobalState;
import com.omarea.gesture.util.Recents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AccessibilityServiceGesture extends AccessibilityService {
    public Recents recents = new Recents();
    private FloatVirtualTouchBar floatVitualTouchBar = null;
    private BroadcastReceiver configChanged = null;
    private BroadcastReceiver serviceDisable = null;
    private BroadcastReceiver screenStateReceiver;
    private SharedPreferences config;
    private SharedPreferences appSwitchBlackList;
    private ContentResolver cr = null;
    private String lastApp = "";
    private BatteryReceiver batteryReceiver;

    private void hidePopupWindow() {
        if (floatVitualTouchBar != null) {
            floatVitualTouchBar.hidePopupWindow();
            floatVitualTouchBar = null;
        }
    }

    private boolean ignored(String packageName) {
        return recents.inputMethods.indexOf(packageName) > -1;
    }

    // 检测应用是否是可以打开的
    private boolean canOpen(String packageName) {
        if (recents.blackList.indexOf(packageName) > -1) {
            return false;
        } else if (recents.whiteList.indexOf(packageName) > -1) {
            return true;
        } else {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                recents.whiteList.add(packageName);
                return true;
            } else {
                recents.blackList.add(packageName);
                return false;
            }
        }
    }

    // 启动器应用（桌面）
    private ArrayList<String> getLauncherApps() {
        Intent resolveIntent = new Intent(Intent.ACTION_MAIN, null);
        resolveIntent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> resolveinfoList = getPackageManager().queryIntentActivities(resolveIntent, 0);
        ArrayList<String> launcherApps = new ArrayList<>();
        for (ResolveInfo resolveInfo : resolveinfoList) {
            String packageName = resolveInfo.activityInfo.packageName;
            if (!("com.android.settings".equals(packageName))) { // MIUI的设置有算个桌面，什么鬼
                launcherApps.add(packageName);
            }
        }
        return launcherApps;
    }

    // 输入法应用
    private ArrayList<String> getInputMethods() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        ArrayList<String> inputMethods = new ArrayList<>();
        for (InputMethodInfo inputMethodInfo : imm.getInputMethodList()) {
            inputMethods.add(inputMethodInfo.getPackageName());
        }
        return inputMethods;
    }

    // TODO:判断是否进入全屏状态，以便在游戏和视频过程中降低功耗
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (recents.inputMethods == null) {
            recents.inputMethods = getInputMethods();
            recents.launcherApps = getLauncherApps();
        }

        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            long start = System.currentTimeMillis();
            try {
                List<AccessibilityWindowInfo> windowInfos = getWindows();
                AccessibilityWindowInfo lastWindow = null;
                for (AccessibilityWindowInfo windowInfo : windowInfos) {
                    if ((!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && windowInfo.isInPictureInPictureMode())) && (windowInfo.getType() == AccessibilityWindowInfo.TYPE_APPLICATION)) {
                        if (lastWindow == null || windowInfo.getLayer() > lastWindow.getLayer()) {
                            Rect outBounds = new Rect();
                            windowInfo.getBoundsInScreen(outBounds);
                            if (outBounds.left == 0 && outBounds.top == 0 &&
                                    (outBounds.right == GlobalState.displayWidth || outBounds.right == GlobalState.displayHeight)
                                    &&
                                    (outBounds.bottom == GlobalState.displayWidth || outBounds.bottom == GlobalState.displayHeight)
                            ) {
                                lastWindow = windowInfo;
                            }
                        }
                    }
                }

                if (lastWindow != null) {
                    AccessibilityNodeInfo root = lastWindow.getRoot();
                    if (root == null) {
                        return;
                    }

                    CharSequence packageName = root.getPackageName();
                    if (packageName == null) {
                        return;
                    }

                    String packageNameStr = packageName.toString();

                    if (!packageNameStr.equals(getPackageName())) {
                        if (recents.launcherApps.contains(packageNameStr)) {
                            recents.addRecent(Intent.CATEGORY_HOME);
                            GlobalState.lastBackHomeTime = System.currentTimeMillis();
                        } else if (!ignored(packageNameStr) && canOpen(packageNameStr) && !appSwitchBlackList.contains(packageNameStr)) {
                            recents.addRecent(packageNameStr);
                            GlobalState.lastBackHomeTime = 0;
                        }
                    }

                    if (
                            GlobalState.updateBar != null &&
                                    !GlobalState.useBatteryCapacity &&
                                    !((packageNameStr.equals("com.android.systemui") || (recents.inputMethods.indexOf(packageNameStr) > -1 && recents.inputMethods.indexOf(lastApp) > -1)))) {
                        if (!(packageName.equals("android") || packageName.equals("com.omarea.filter"))) {
                            WhiteBarColor.updateBarColorMultiple();
                        }
                    }

                    lastApp = packageNameStr;
                }
            } finally {
                Log.d(">>>>", "OnAccessibilityEvent Processing time(ms): " + (System.currentTimeMillis() - start));
            }
        }
    }

    private void setBatteryReceiver() {
        if (batteryReceiver == null) {
            batteryReceiver = new BatteryReceiver(this);
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_POWER_CONNECTED));
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_POWER_DISCONNECTED));
        }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        if (config == null) {
            config = getSharedPreferences(SpfConfig.ConfigFile, Context.MODE_PRIVATE);
        }

        if (appSwitchBlackList == null) {
            appSwitchBlackList = getSharedPreferences(SpfConfig.AppSwitchBlackList, Context.MODE_PRIVATE);
        }

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Point point = new Point();
        wm.getDefaultDisplay().getRealSize(point);
        GlobalState.displayWidth = point.x;
        GlobalState.displayHeight = point.y;
        GlobalState.consecutive = config.getBoolean(SpfConfig.IOS_BAR_CONSECUTIVE, SpfConfig.IOS_BAR_CONSECUTIVE_DEFAULT);

        GlobalState.useBatteryCapacity = config.getBoolean(SpfConfig.IOS_BAR_POP_BATTERY, SpfConfig.IOS_BAR_POP_BATTERY_DEFAULT);
        if (GlobalState.useBatteryCapacity) {
            setBatteryReceiver();
        }

        TouchIconCache.setContext(this.getBaseContext());

        if (configChanged == null) {
            configChanged = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    GlobalState.consecutive = config.getBoolean(SpfConfig.IOS_BAR_CONSECUTIVE, SpfConfig.IOS_BAR_CONSECUTIVE_DEFAULT);
                    GlobalState.useBatteryCapacity = config.getBoolean(SpfConfig.IOS_BAR_POP_BATTERY, SpfConfig.IOS_BAR_POP_BATTERY_DEFAULT);
                    if (GlobalState.useBatteryCapacity) {
                        setBatteryReceiver();
                    } else if (batteryReceiver != null) {
                        unregisterReceiver(batteryReceiver);
                        batteryReceiver = null;
                    }

                    String action = intent != null ? intent.getAction() : null;
                    if (action != null && action.equals(getString(R.string.app_switch_changed))) {
                        if (recents != null) {
                            recents.clear();
                            Gesture.toast("OK！", Toast.LENGTH_SHORT);
                        }
                    } else {
                        new AdbProcessExtractor().updateAdbProcessState(context, false);
                        if (action != null && action.equals(getString(R.string.action_adb_process))) {
                            if (GlobalState.enhancedMode) {
                                setResultCode(0);
                                setResultData("Nice, The enhancement mode has been activated ^_^");
                            } else {
                                setResultCode(5);
                                setResultData("Unable to start enhanced mode >_<");
                            }
                        }
                        createPopupView();
                    }
                }
            };

            registerReceiver(configChanged, new IntentFilter(getString(R.string.action_config_changed)));
            registerReceiver(configChanged, new IntentFilter(getString(R.string.app_switch_changed)));
            registerReceiver(configChanged, new IntentFilter(getString(R.string.action_adb_process)));
        }
        if (serviceDisable == null) {
            serviceDisable = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        disableSelf();
                    }
                    stopSelf();
                }
            };
            registerReceiver(serviceDisable, new IntentFilter(getString(R.string.action_service_disable)));
        }
        createPopupView();

        registerReceiver(screenStateReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(screenStateReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        }
        registerReceiver(screenStateReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        registerReceiver(screenStateReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));

        Collections.addAll(recents.blackList, getResources().getStringArray(R.array.app_switch_black_list));

        new AdbProcessExtractor().updateAdbProcessState(this, true);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hidePopupWindow();
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
    }

    // 监测屏幕旋转
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (floatVitualTouchBar != null && newConfig != null) {
            // 关闭常用应用面板
            QuickPanel.close();

            GlobalState.isLandscapf = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;

            // 如果分辨率变了，那就重新创建手势区域
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            Point point = new Point();
            wm.getDefaultDisplay().getRealSize(point);
            if (point.x != GlobalState.displayWidth || point.y != GlobalState.displayHeight) {
                GlobalState.displayWidth = point.x;
                GlobalState.displayHeight = point.y;
                createPopupView();
            }
        }
    }

    private void createPopupView() {
        hidePopupWindow();

        AccessibilityServiceInfo accessibilityServiceInfo = getServiceInfo();
        accessibilityServiceInfo.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        // accessibilityServiceInfo.eventTypes = TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        setServiceInfo(accessibilityServiceInfo);

        floatVitualTouchBar = new FloatVirtualTouchBar(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatVitualTouchBar != null) {
            floatVitualTouchBar.hidePopupWindow();
        }

        if (configChanged != null) {
            unregisterReceiver(configChanged);
            configChanged = null;
        }

        if (screenStateReceiver != null) {
            unregisterReceiver(screenStateReceiver);
            screenStateReceiver = null;
        }

        if (batteryReceiver != null) {
            unregisterReceiver(batteryReceiver);
            batteryReceiver = null;
        }
        // stopForeground(true);
    }
}
