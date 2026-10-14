# release / releaseE2e 开 R8(压缩 + 混淆 + 资源收缩)。依赖库(OkHttp、Coil、Media3、Room、WorkManager、Hilt、Tink)
# 自带 consumer 规则;这里只补库规则管不到的部分。改规则后用 releaseE2e 包把 tools/e2e 的 p1–p4 全跑一遍。

# 崩溃日志(「我的 → 崩溃日志」)里的堆栈保留行号;发版时把 app/build/outputs/mapping/release/mapping.txt
# 与安装包一起存档,导出的日志用 retrace 还原类名与方法名
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization:@Serializable 类的 serializer 通过反射查找(Navigation 类型安全路由、CookieJar 持久化)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class xyz.yophon.gopan.**$$serializer { *; }
-keepclassmembers class xyz.yophon.gopan.** { *** Companion; }
-keepclasseswithmembers class xyz.yophon.gopan.** { kotlinx.serialization.KSerializer serializer(...); }

# AppAuth 用 JSON 反序列化自己的模型
-keep class net.openid.appauth.** { *; }
