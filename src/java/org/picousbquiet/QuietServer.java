package org.picousbquiet;

import android.content.pm.ActivityInfo;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PicoUSB-Quiet's system_server helper for PICO OS (Android 10).
 *
 * <p>Two independent rule kinds, both read from the module's rules.txt:
 * <ul>
 *   <li><b>activity rule</b>: marks an activity as a "dialog" class window (the manifest
 *       meta-data key {@code pico.vr.position}) in PackageManager's in-memory package
 *       data, so SystemExt's app-switch check quick-allows it instead of showing
 *       "exit current app". Memory only; a reboot without the module reverts it.</li>
 *   <li><b>usb-default rule</b>: stores the package as the default handler of matching
 *       attached USB devices through the system's own {@code setDevicePackage}, so PICO
 *       skips its auto-confirming UsbConfirmActivity panel. The system persists this in
 *       its usual USB settings file.</li>
 * </ul>
 * No method hooks, no APK changes.
 */
public final class QuietServer {
    private static final String TAG = "PicoUSB-Quiet";
    private static final String KEY = "pico.vr.position";
    private static final String PROP_KEY = "pico.vr.app.prop";
    private static final long POLL_MS = 1000;
    private static final Object START_LOCK = new Object();
    private static Thread worker;

    private QuietServer() { }

    private static final class Target {
        final String pkg;
        final String cls;
        final String position;
        Object lastPackage;   // Package incarnation already handled (for quiet logging)

        Target(String pkg, String cls, String position) {
            this.pkg = pkg;
            this.cls = cls;
            this.position = position;
        }
    }

    /** usb-default rule: make pkg the saved default handler for devices with these VID/PID. */
    private static final class UsbRule {
        final String pkg;
        final Set<Integer> ids = new HashSet<Integer>();   // (vid << 16) | pid

        UsbRule(String pkg) {
            this.pkg = pkg;
        }
    }

    /** Called by the native worker after boot completion (not from SystemServer.main). */
    public static void start(String config) {
        try {
            if (Process.myUid() != 1000 || Build.VERSION.SDK_INT != 29) {
                log("startup rejected: process or SDK mismatch", null);
                return;
            }
            synchronized (START_LOCK) {
                if (worker != null) {
                    return;
                }
                final List<Target> targets = parse(config);
                final List<UsbRule> rules = parseUsb(config);
                if (targets.isEmpty() && rules.isEmpty()) {
                    log("no valid targets; idle", null);
                    return;
                }
                Thread thread = new Thread(new Runnable() {
                    @Override public void run() { new Engine(targets, rules).loop(); }
                }, "PicoUSB-Quiet-monitor");
                thread.setDaemon(true);
                worker = thread;
                thread.start();
                log("started with " + targets.size() + " target(s), " + rules.size() + " usb rule(s)", null);
            }
        } catch (Throwable error) {
            log("startup failed", error);
        }
    }

    private static List<Target> parse(String config) {
        List<Target> result = new ArrayList<Target>();
        if (config == null) {
            return result;
        }
        for (String raw : config.split("\n")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if ("usb-default".equals(parts[0])) {
                continue;   // handled by parseUsb
            }
            // Only positions that SystemExt quick-allows without the switch dialog.
            if (parts.length == 3 && ("system_dialog".equals(parts[2])
                    || "global_dialog".equals(parts[2]) || "near_dialog".equals(parts[2]))
                    && parts[0].indexOf('/') < 0) {
                String cls = parts[1].startsWith(".") ? parts[0] + parts[1] : parts[1];
                result.add(new Target(parts[0], cls, parts[2]));
            } else {
                log("ignored invalid target line", null);
            }
        }
        return result;
    }

    private static List<UsbRule> parseUsb(String config) {
        List<UsbRule> result = new ArrayList<UsbRule>();
        if (config == null) {
            return result;
        }
        for (String raw : config.split("\n")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 3 || !"usb-default".equals(parts[0]) || parts[1].indexOf('/') >= 0) {
                continue;
            }
            UsbRule rule = new UsbRule(parts[1]);
            for (int i = 2; i < parts.length; ++i) {
                try {
                    String[] pair = parts[i].split(":");
                    int vid = Integer.decode(pair[0]).intValue();
                    int pid = Integer.decode(pair[1]).intValue();
                    if (pair.length == 2 && vid >= 0 && vid <= 0xFFFF && pid >= 0 && pid <= 0xFFFF) {
                        rule.ids.add(Integer.valueOf((vid << 16) | pid));
                        continue;
                    }
                } catch (RuntimeException ignored) {
                    // fall through to the log below
                }
                log("ignored invalid usb id", null);
            }
            if (!rule.ids.isEmpty()) {
                result.add(rule);
            }
        }
        return result;
    }

    private static void log(String event, Throwable error) {
        try {
            Log.i(TAG, event + (error == null ? "" : " (" + error + ")"));
        } catch (Throwable ignored) {
            // Logging must never escape into SystemServer.
        }
    }

    private static final class Engine {
        private final List<Target> targets;
        private Map<?, ?> packages;
        private Field activitiesField;
        private Field infoField;
        private Method getExt;
        private Method getPosition;
        private Method getProperties;
        private Method updateFeature;
        private boolean bound;

        private final List<UsbRule> rules;
        private final Set<String> handledDevices = new HashSet<String>();
        private Object usbService;
        private Method getDeviceList;
        private Method setDevicePackage;
        private boolean usbBound;
        private boolean usbDisabled;

        Engine(List<Target> targets, List<UsbRule> rules) {
            this.targets = targets;
            this.rules = rules;
        }

        void loop() {
            int failures = 0;
            for (;;) {
                long delay = POLL_MS;
                try {
                    if (!bound) {
                        try {
                            bound = bind();
                        } catch (Throwable error) {
                            // Reflection/shape mismatch is not transient (unsupported firmware):
                            // log once and stop instead of retrying every second.
                            log("PMS bind failed; module disabled", error);
                            return;
                        }
                    }
                    if (bound) {
                        for (Target target : targets) {
                            apply(target);
                        }
                    }
                    if (bound && !rules.isEmpty() && !usbDisabled) {
                        try {
                            applyUsbDefaults();
                        } catch (Throwable error) {
                            if (!usbBound) {
                                log("USB bind failed; usb-default disabled", error);
                                usbDisabled = true;
                            } else {
                                throw error;
                            }
                        }
                    }
                    failures = 0;
                } catch (Throwable error) {
                    failures++;
                    if (Integer.bitCount(failures) == 1) {   // log 1st, 2nd, 4th, 8th ...
                        log("iteration failed (x" + failures + ")", error);
                    }
                    delay = Math.min(60000L, POLL_MS << Math.min(failures, 6));
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {
                    // Process-lifetime daemon.
                }
            }
        }

        /**
         * PICO's UsbConfirmActivity auto-confirms without ticking "always use", so every attach
         * shows a brief confirm panel. When a default handler is stored for the exact device,
         * UsbProfileGroupSettingsManager starts the handler directly and skips that panel.
         * We store the default (once per newly seen device) through the same system API the
         * confirm dialog's checkbox would have used. The preference is persisted by the system.
         */
        @SuppressWarnings("deprecation") // Bundle.get(String) returns the stored UsbDevice
        private void applyUsbDefaults() throws Exception {
            if (!usbBound) {
                usbBound = bindUsb();
                if (!usbBound) {
                    return;
                }
            }
            Bundle devices = new Bundle();
            getDeviceList.invoke(usbService, devices);
            Set<String> present = new HashSet<String>();
            for (String key : new ArrayList<String>(devices.keySet())) {
                Object value = devices.get(key);
                if (!(value instanceof UsbDevice)) {
                    continue;
                }
                UsbDevice device = (UsbDevice) value;
                String id = key + "|" + device.getVendorId() + ":" + device.getProductId();
                present.add(id);
                if (handledDevices.contains(id)) {
                    continue;
                }
                int ident = (device.getVendorId() << 16) | device.getProductId();
                for (UsbRule rule : rules) {
                    if (!rule.ids.contains(Integer.valueOf(ident))) {
                        continue;
                    }
                    boolean installed;
                    synchronized (packages) {
                        installed = packages.get(rule.pkg) != null;
                    }
                    if (!installed) {
                        continue;
                    }
                    try {
                        setDevicePackage.invoke(usbService, device, rule.pkg, 0);
                        log("default handler for usb " + Integer.toHexString(device.getVendorId()) + ":"
                                + Integer.toHexString(device.getProductId()) + " -> " + rule.pkg, null);
                    } catch (Throwable error) {
                        log("setDevicePackage failed", error);   // not retried every second
                    }
                    break;
                }
                handledDevices.add(id);   // also remember non-matching devices; no repeat work
            }
            handledDevices.retainAll(present);   // forget detached devices so a re-attach is re-checked
        }

        private boolean bindUsb() throws Exception {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager.getMethod("checkService", String.class)
                    .invoke(null, "usb");
            if (binder == null) {
                return false;
            }
            Object local = binder.queryLocalInterface("android.hardware.usb.IUsbManager");
            if (local == null || !"com.android.server.usb.UsbService".equals(local.getClass().getName())) {
                throw new IllegalStateException("USB service is not local UsbService");
            }
            Method list = local.getClass().getMethod("getDeviceList", Bundle.class);
            Method set = local.getClass().getMethod("setDevicePackage", UsbDevice.class, String.class, int.class);
            usbService = local;
            getDeviceList = list;
            setDevicePackage = set;
            log("bound to UsbService", null);
            return true;
        }

        private boolean bind() throws Exception {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager.getMethod("checkService", String.class)
                    .invoke(null, "package");
            if (binder == null) {
                return false;
            }
            Object local = binder.queryLocalInterface("android.content.pm.IPackageManager");
            if (local == null || !"com.android.server.pm.PackageManagerService"
                    .equals(local.getClass().getName())) {
                throw new IllegalStateException("Package service is not local PMS");
            }
            Field mapField = local.getClass().getDeclaredField("mPackages");
            mapField.setAccessible(true);
            Object map = mapField.get(local);
            if (!(map instanceof Map) || !"android.util.ArrayMap".equals(map.getClass().getName())) {
                throw new IllegalStateException("Unexpected PMS lock/map");
            }
            ClassLoader loader = local.getClass().getClassLoader();
            Class<?> pkgClass = Class.forName("android.content.pm.PackageParser$Package", false, loader);
            Class<?> activityClass = Class.forName("android.content.pm.PackageParser$Activity", false, loader);
            Class<?> extClass = Class.forName("android.content.pm.IExtActivityInfo", false, loader);

            Field activities = pkgClass.getField("activities");
            Field info = activityClass.getField("info");
            Method ext = ActivityInfo.class.getMethod("getExt");
            Method position = extClass.getMethod("get2dAppPosition");
            Method props = extClass.getMethod("getAppProperties");
            Method update = extClass.getMethod("updateAppFeature", Bundle.class);
            if (!ArrayList.class.isAssignableFrom(activities.getType()) || info.getType() != ActivityInfo.class
                    || position.getReturnType() != String.class || props.getReturnType() != String.class) {
                throw new IllegalStateException("Unexpected PMS/ext member types");
            }
            activities.setAccessible(true);
            info.setAccessible(true);
            ext.setAccessible(true);
            position.setAccessible(true);
            props.setAccessible(true);
            update.setAccessible(true);
            // Publish only after every member resolved.
            packages = (Map<?, ?>) map;
            activitiesField = activities;
            infoField = info;
            getExt = ext;
            getPosition = position;
            getProperties = props;
            updateFeature = update;
            log("bound to PMS and ext members", null);
            return true;
        }

        private void apply(Target target) throws Exception {
            String event = null;
            Object pkgSeen;
            synchronized (packages) {
                Object pkg = packages.get(target.pkg);
                pkgSeen = pkg;
                if (pkg == null) {
                    if (target.lastPackage != null) {
                        target.lastPackage = null;
                        event = target.pkg + " removed";
                    }
                } else {
                    ActivityInfo found = null;
                    List<?> list = (List<?>) activitiesField.get(pkg);
                    if (list != null) {
                        for (Object activity : list) {
                            ActivityInfo info = (ActivityInfo) infoField.get(activity);
                            if (info != null && target.cls.equals(info.name)) {
                                found = info;
                                break;
                            }
                        }
                    }
                    if (found == null) {
                        if (target.lastPackage != pkg) {
                            event = target.pkg + " present but " + target.cls + " not found";
                        }
                    } else {
                        Object ext = getExt.invoke(found);
                        Object current = getPosition.invoke(ext);
                        if (!target.position.equals(current)) {
                            Bundle bundle = new Bundle();
                            bundle.putString(KEY, target.position);
                            // updateAppFeature overwrites app properties; carry the existing value.
                            Object props = getProperties.invoke(ext);
                            if (props != null) {
                                bundle.putString(PROP_KEY, (String) props);
                            }
                            updateFeature.invoke(ext, bundle);
                            Object after = getPosition.invoke(ext);
                            event = target.pkg + "/" + target.cls + " position " + current
                                    + " -> " + after + (target.position.equals(after) ? " ok" : " FAILED");
                        } else if (target.lastPackage != pkg) {
                            event = target.pkg + "/" + target.cls + " already " + current;
                        }
                    }
                }
            }
            target.lastPackage = pkgSeen;
            if (event != null) {
                log(event, null);   // never log inside PMS.mPackages
            }
        }
    }
}
