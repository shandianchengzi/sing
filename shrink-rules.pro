# Android instantiates the launcher Activity and calls lifecycle overrides by name.
-keep public class com.shandian.sing.MainActivity {
    public <init>();
    public *;
    protected *;
}
-dontwarn java.lang.invoke.StringConcatFactory
-keepattributes *Annotation*

-keep public class com.shandian.sing.UpdateApkProvider {
    public <init>();
    public *;
}

# Android creates foreground services and TensorFlow JNI resolves these names.
-keep public class com.shandian.sing.LocalSeparationService { public <init>(); public *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
# External signed instrumentation verifies the actual release implementation by reflection.
-keep class com.shandian.sing.LocalSeparation { *; }
-keep class com.shandian.sing.LocalSeparation$Task { *; }
-keep interface com.shandian.sing.SeparationClient$Progress { *; }

-keep class com.shandian.sing.LocalSeparationService { *; }
-keep class com.shandian.sing.LocalSeparationService$State { *; }
