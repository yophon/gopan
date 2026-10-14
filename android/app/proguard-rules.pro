# P1 release 未开启混淆(isMinifyEnabled = false),这里先放好发布阶段需要的规则。

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
