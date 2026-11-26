# tvaVPN

برنامهٔ VPN و دورزدن فیلترینگ برای اندروید.

- تلگرام: [@ClassicDevs](https://t.me/ClassicDevs)
- گیت‌هاب: [tohidbareza](https://github.com/tohidbareza)

تغییرات نسخهٔ ۳٫۰ را در [CHANGELOG.md](CHANGELOG.md) ببینید.

## امکانات
- چند حالت تونل و یک حالت هوشمند که خودش حالت‌ها را امتحان می‌کند.
- خروجی و ورودی همهٔ تنظیمات تونل با فایل.
- همهٔ گزینه‌ها لیست کشویی هستند و برنامه برای TalkBack ساخته شده است.
- قفل امضا: فقط نسخه‌های امضاشده با کلید رسمی اجرا می‌شوند.

## ساخت
پروژه با Android Studio ساخته می‌شود (Gradle JDK 17).

۱. مخزن را کلون و پوشه را در Android Studio باز کنید.
۲. فایل‌های موتور (`.so`) را در `app/src/main/jniLibs/arm64-v8a/` بگذارید. این فایل‌ها
   در git نیستند (فایل `.gitignore` را ببینید).
۳. برای نسخهٔ debug روی گوشی اجرا کنید، برای نسخهٔ نهایی از
   Build > Generate Signed App Bundle / APK استفاده کنید.

### امضا
نسخهٔ نهایی باید با کلید رسمی امضا شود، وگرنه برنامه صفحهٔ خطا نشان می‌دهد و وصل نمی‌شود.
فایل‌های `release.keystore` و `keystore.properties` را کنار `settings.gradle.kts` بگذارید
(هرگز کامیت نکنید). راهنما: `docs/SIGNING.md` و `keystore.properties.example`.

## مجوز
GNU AGPL-3.0، فایل [LICENSE](LICENSE). اجزای جانبی در
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) فهرست شده‌اند.
