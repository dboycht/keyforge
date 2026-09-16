# Keep the Bluetooth HID plumbing we call through reflection free of warnings.
-dontwarn android.bluetooth.**

# Keep line numbers so crash reports from the field stay actionable.
-keepattributes SourceFile,LineNumberTable
