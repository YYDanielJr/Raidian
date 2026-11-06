# app_process loads this entry point by name, outside the Android component graph.
-keep class yy.daniel.raidian.privilege.RunnerServer { public static void main(java.lang.String[]); }
# LibADB accesses the bundled Conscrypt TLS exporter reflectively.
-keep class org.conscrypt.Conscrypt { public *; }
-keep class org.conscrypt.OpenSSLProvider { public <init>(); }
# Conscrypt's pre-KitKat adapters are unreachable with minSdk 33.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
