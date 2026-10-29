# 保留 Room 生成的类名（发布版开启混淆时需要）
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# 崩溃堆栈保留行号（配合 app/build/outputs/mapping/release/mapping.txt 可还原混淆名）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
