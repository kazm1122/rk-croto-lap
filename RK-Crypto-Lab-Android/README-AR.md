# تطبيق أندرويد – المختبر التعليمي للتشفير (RK-TEAM)

التطبيق هو نفس الموقع تماماً (ملف `app/src/main/assets/index.html`) داخل غلاف أندرويد احترافي:
يعمل بدون إنترنت، الصوت والنطق شغّالان، حفظ الملفات إلى «التنزيلات»، رفع الملفات، زر الرجوع، وروابط تلغرام تُفتح خارج التطبيق.

## الطريقة 1: بدون كمبيوتر (من الجوال) عبر GitHub
1. أنشئ حساب على github.com ثم مستودع جديد (Repository).
2. ارفع محتويات هذا المجلد كلها (بما فيه مجلد `.github`).
3. ادخل تبويب **Actions** ← **Build APK** ← **Run workflow**.
4. بعد دقائق ادخل على التشغيل ← **Artifacts** ← حمّل `RK-Crypto-Lab-APK` وفيه ملف `app-debug.apk`.

## الطريقة 2: Android Studio
1. افتح المجلد في Android Studio وانتظر Sync.
2. Build ← Build APK(s). الملف في `app/build/outputs/apk/debug/`.

## تحديث الموقع لاحقاً
استبدل `app/src/main/assets/index.html` بالنسخة الجديدة، ارفع `versionCode` في `app/build.gradle.kts`، وابنِ من جديد.

## نسخة النشر (Release)
ملف debug يُثبَّت مباشرة على أي جهاز. للنشر على Google Play أو لنسخة موقّعة:
Build ← Generate Signed Bundle/APK، واحتفظ بملف المفتاح (keystore) في مكان آمن.

اسم الحزمة: `com.rkteam.cryptolab` — الأيقونة: `play-store-icon-512.png`
