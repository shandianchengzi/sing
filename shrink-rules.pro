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
