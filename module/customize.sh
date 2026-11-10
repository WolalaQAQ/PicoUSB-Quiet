#!/system/bin/sh
# Magisk install-time script (zygisk/<abi>.so is auto-discovered; no module.prop flag needed).
SKIPUNZIP=0

if [ "$ARCH" != "arm64" ]; then
  abort "! PicoUSB-Quiet only supports arm64."
fi

ui_print "- PicoUSB-Quiet $MODVER"
ui_print "- PICO OS (Android 10) only; no APK is modified"
ui_print "- rules: /data/adb/modules/pico_usb_quiet/rules.txt (edit, then reboot)"

# Keep a user-edited rules file across upgrades.
OLD=/data/adb/modules/pico_usb_quiet/rules.txt
if [ -f "$OLD" ] && [ ! -L "$OLD" ] && [ "$MODPATH/rules.txt" != "$OLD" ]; then
  cp "$MODPATH/rules.txt" "$MODPATH/rules.txt.new" || abort "! cannot stage shipped rules"
  cp "$OLD" "$MODPATH/rules.txt" || abort "! cannot preserve rules.txt"
  if ! cmp -s "$MODPATH/rules.txt" "$MODPATH/rules.txt.new"; then
    ui_print "- your edited rules.txt was kept; the shipped version is next to it as rules.txt.new"
  else
    rm -f "$MODPATH/rules.txt.new"
  fi
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/zygisk/arm64-v8a.so" 0 0 0644
set_perm "$MODPATH/classes.dex" 0 0 0644
set_perm "$MODPATH/rules.txt" 0 0 0644
