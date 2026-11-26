# Changelog

<!--
  The app reads this file (raw from GitHub). Per version, write the English block
  (ASCII digits) and the Persian block (Persian digits). Optional section:

    ### Files            (Persian block: ### فایل‌ها)
    - path/of/changed/file

  About > Changelog in the app shows ONLY these paths; the update page shows the
  rest of each block as release notes.
-->

## 1.8.4 - 2026-11-26

### Fixed
- **The app and the notification no longer disagree about the tunnel.** The connection state lived
  only in the screen's process, so after a Recents swipe on MIUI/HyperOS the app could open on a
  fresh "disconnected" while the VPN was still up (and the shade still said connected). Opening the
  app now asks the service which side is right: a live session is republished as Connected, and if
  the session really is gone while "stay connected" is on, it is restored right away instead of
  leaving a stale "connected" notification behind.
- **A system start no longer leaves the notification on "starting the core".** Android's own
  `android.net.VpnService` start request re-posted the foreground notification with the launch text
  and then returned without a connect, so that text stayed on screen over a working tunnel. It now
  keeps the notification it finds and repaints the real state.

---

## ۱٫۸٫۴ - ۱۴۰۵/۰۹/۰۵

### درست شد
- **برنامه و نوتیفیکیشن دیگر دربارهٔ اتصال با هم تناقض ندارند.** وضعیت اتصال فقط در حافظهٔ صفحه بود؛
  بعد از بستن از «برنامه‌های اخیر» در MIUI/HyperOS ممکن بود برنامه با حالت «وصل نیست» باز شود در
  حالی که VPN هنوز بالا بود (و نوتیفیکیشن هنوز «متصل» می‌نوشت). حالا باز کردن برنامه از سرویس
  می‌پرسد کدام درست است: اگر اتصال زنده باشد «متصل» منتشر می‌شود، و اگر واقعاً قطع شده باشد و
  گزینهٔ «متصل بمان» روشن باشد همان‌جا دوباره وصل می‌شود؛ نوتیفیکیشن کهنه باقی نمی‌ماند.
- **اجرای سرویس توسط سیستم دیگر نوتیفیکیشن را روی «در حال راه‌اندازی هسته…» جا نمی‌گذارد.** درخواست
  خود اندروید (`android.net.VpnService`) نوتیفیکیشن را با متن شروع دوباره می‌نوشت و بعد بدون اتصال
  برمی‌گشت، پس آن متن روی یک تونل سالم می‌ماند. حالا نوتیفیکیشن موجود نگه داشته و وضعیت واقعی
  دوباره نوشته می‌شود.

---

## 1.8.3 - 2026-11-26

### Fixed
- **The tunnel no longer restarts by itself.** Android re-opens a running VPN by starting the
  service with its own `android.net.VpnService` intent. That intent carried no settings, and the
  service treated it as a new connect: it tore down the healthy session and dialled again from
  scratch (with default settings). It is now handled as what it is - a restore request - so
  swiping the app out of Recents or opening it again keeps the same connection.
- **The Xiaomi Autostart row goes away once Autostart is on.** The app now reads MIUI/HyperOS'
  Autostart state where the ROM allows it, and also treats a successful background restart of the
  VPN service as proof that the permission works, so the Settings step is no longer shown for
  something already done.

---

## ۱٫۸٫۳ - ۱۴۰۵/۰۹/۰۵

### درست شد
- **تونل دیگر خودش از اول وصل نمی‌شود.** اندروید برای بازگرداندن یک VPN فعال، سرویس را با intent
  خودش (`android.net.VpnService`) اجرا می‌کند. آن intent تنظیمات همراه نداشت و برنامه آن را
  «اتصال تازه» حساب می‌کرد: اتصال سالم را قطع و از نو (با تنظیمات پیش‌فرض) وصل می‌کرد. حالا
  به‌عنوان درخواست بازگردانی شناخته می‌شود؛ پس بستن برنامه از «برنامه‌های اخیر» یا باز کردن دوبارهٔ
  آن، همان اتصال را نگه می‌دارد.
- **ردیف «اجرای خودکار» شیائومی وقتی روشن باشد نشان داده نمی‌شود.** برنامه وضعیت اجرای خودکار
  MIUI/HyperOS را هر جا ROM اجازه بدهد می‌خواند و شروع موفق سرویس در پس‌زمینه را هم مدرک
  روشن‌بودن این اجازه می‌گیرد، بنابراین این مرحله دیگر برای کاری که انجام شده نشان داده نمی‌شود.

---

## 1.7.1 - 2026-10-30

### Changed
- **Connection:** changes to how the app connects.
- **App speed:** the app is faster.

### Added
- **Update notification.** The app now notifies you when a new version is available.

---

## ۱٫۷٫۱ - ۱۴۰۵/۰۸/۰۸

### تغییر کرد
- **اتصال:** تغییراتی در نحوهٔ اتصال برنامه.
- **سرعت برنامه:** سرعت برنامه بهتر شد.

### اضافه شد
- **اطلاع‌رسانی بروزرسانی.** وقتی نسخهٔ جدید منتشر شود، برنامه به شما اطلاع می‌دهد.

---

## 1.6.0 - 2026-10-29

### Changed
- **Connection screen rewritten.** The single glass card with the travelling light is gone.
  Under the power button there is now plain status text with the session timer, and one
  flat panel with speeds, address, route and latency.
- Engine core is 2.4.0.

### Added
- **Hysteria 2 in custom configs.** `hysteria2://` and `hy2://` links (auth, SNI, ALPN,
  `insecure`, Salamander obfuscation, certificate pinning, port hopping) and Xray JSON
  outbounds with `protocol: hysteria` can be imported and run. Hysteria 1 links get a
  clear "not supported" message. The config runs through Hysteria's own official client
  binary (`libhysteria.so`), not through Xray: current Xray releases have dropped the
  `allowInsecure` option that most shared Hysteria2 links depend on, so running it there
  could never actually reach those servers. Tested end-to-end against a real Hysteria 2
  server (salamander obfuscation, SNI, the insecure-certificate flag, port hopping). The
  original Xray core is untouched and still runs every other protocol exactly as before.

### Removed
- The "Ready to edit" chip on the Settings summary. Only "Locked while connected" is shown now.

---

## ۱٫۶٫۰ - ۱۴۰۵/۰۸/۰۷

### تغییر کرد
- **صفحهٔ اتصال از نو نوشته شد.** کارت شیشه‌ای و نور متحرک حذف شد. زیر دکمهٔ روشن/خاموش
  حالا متن وضعیت ساده با زمان‌سنج نشست است، و یک پنل تخت با سرعت، آدرس، مسیر و تأخیر.
- هستهٔ موتور ۲٫۴٫۰ شد.

### اضافه شد
- **پشتیبانی ⁨Hysteria 2⁩ در کانفیگ‌های دلخواه.** لینک‌های ⁨hysteria2://⁩ و ⁨hy2://⁩ (با
  ⁨auth⁩، ⁨SNI⁩، ⁨ALPN⁩، گزینهٔ نادیده‌گرفتن گواهی، مبهم‌سازی سالاماندر، قفل گواهی و پرش
  پورت) و خروجی‌های JSON ⁨Xray⁩ با ⁨protocol: hysteria⁩ وارد و اجرا می‌شوند. لینک‌های
  ⁨Hysteria 1⁩ با پیام روشن «پشتیبانی نمی‌شود» رد می‌شوند. این کانفیگ با کلاینت رسمی خود
  ⁨Hysteria⁩ (فایل ⁨libhysteria.so⁩) اجرا می‌شود، نه با ⁨Xray⁩: نسخه‌های فعلی ⁨Xray⁩ گزینهٔ
  ⁨allowInsecure⁩ را که بیشتر لینک‌های اشتراکی ⁨Hysteria2⁩ به آن نیاز دارند حذف کرده‌اند،
  پس اجرا از آن مسیر هرگز نمی‌توانست واقعاً به آن سرورها وصل شود. روی یک سرور ⁨Hysteria 2⁩
  واقعی آزمایش شد. هستهٔ اصلی ⁨Xray⁩ دست‌نخورده مانده و دقیقاً مثل قبل بقیهٔ پروتکل‌ها را
  اجرا می‌کند.

### حذف شد
- تگ «آمادهٔ ویرایش» در خلاصهٔ تنظیمات. فقط «قفل هنگام اتصال» نشان داده می‌شود.

---

## 3.0 - 2026-10-24

### Added
- **Export & import settings** (Settings > App). Saves the tunnel settings to a small
  JSON file with the system file picker and restores them, with a confirmation
  dialog. Passwords, tokens, the Zero Trust sign-in and the upstream proxy are never
  written to the file and are never changed by an import.
- **Telegram channel button** in About: opens the channel straight in the Telegram app
  (falls back to the web page when Telegram is not installed).
- **Signature lock.** The app only runs when it is signed with the official release key.
  A copy signed with any other key shows an error screen (with its own certificate
  fingerprint) and never connects. Debug builds are exempt.

### Changed
- Every option picker is now a **drop-down list**: the tab-like option cards and the
  bottom-sheet pickers are gone.
- The app is now **tvaVPN**, with a new app icon, notification icon, TV banner and
  in-app mark.
- About shows this edition's own GitHub link and Telegram channel.
- Version is 3.0 (versionCode 30).

### Removed
- The AI assistant (chat, advisor, explanation hints and the Gemini key field).
- The "What's new" dialog and the announcements archive.

### Fixed
- The IP on the connection card always matches the state. It is cleared the moment the VPN
  disconnects (also when disconnecting from the notification, the tile or the widget), a late
  self-test can no longer put the old tunnel IP back, and while connecting the card says
  "checking" instead of "unavailable".
- When the system destroys the VPN service (for example after swiping the app out of the
  recent-apps list on some phones) and restarts it, the tunnel now reconnects with the saved
  settings instead of staying off. A tunnel the user switched off never comes back by itself.

### Accessibility
- Drop-down fields are announced as drop-down lists, and every item as a radio button
  that says whether it is selected.
- The export/import buttons are announced as buttons, the disabled import button as
  disabled, and the result of an export or import is spoken as well as shown.

---

## ۳٫۰ - ۱۴۰۵/۰۸/۰۲

### اضافه شد
- **خروجی و ورودی تنظیمات** (تنظیمات > برنامه): تنظیمات تونل را در یک فایل JSON ذخیره
  و دوباره وارد می‌کند، با کادر تأیید. رمز، توکن، ورود Zero Trust و پروکسی بالادستی
  هرگز در فایل نمی‌آیند و ورودی هم آن‌ها را تغییر نمی‌دهد.
- **دکمهٔ کانال تلگرام** در «درباره»: کانال را مستقیم در برنامهٔ تلگرام باز می‌کند.
- **قفل امضا:** برنامه فقط با کلید رسمی امضا اجرا می‌شود. نسخهٔ امضاشده با کلید دیگر
  صفحهٔ خطا نشان می‌دهد و هرگز وصل نمی‌شود (نسخهٔ debug مستثناست).

### تغییر کرد
- همهٔ انتخاب‌گرها **لیست کشویی** شدند؛ کارت‌های برگه‌مانند و پنجره‌های پایین‌صفحه حذف شدند.
- نام برنامه **tvaVPN** شد، با آیکون، آیکون اعلان، بنر تلویزیون و نشان جدید.
- بخش «درباره» لینک گیت‌هاب و کانال تلگرام همین نسخه را نشان می‌دهد.
- نسخه ۳٫۰ (versionCode 30).

### حذف شد
- دستیار هوش مصنوعی (گفتگو، مشاور، راهنماهای توضیحی و کلید Gemini).
- پنجرهٔ «تغییرات نسخه» و آرشیو اطلاعیه‌ها.

### رفع اشکال
- IP روی کارت اتصال همیشه با وضعیت می‌خواند: لحظهٔ قطع شدن VPN پاک می‌شود (حتی اگر از اعلان، تایل
  یا ویجت قطع کنید)، نتیجهٔ دیرهنگام خودآزمایی دیگر IP قدیمی را برنمی‌گرداند، و هنگام اتصال به‌جای
  «در دسترس نیست» نوشته می‌شود «در حال بررسی».
- وقتی سیستم سرویس VPN را از بین ببرد و دوباره راه بیندازد (مثلاً بعد از بستن برنامه از فهرست
  برنامه‌های اخیر در بعضی گوشی‌ها)، تونل با تنظیمات ذخیره‌شده دوباره وصل می‌شود. تونلی که کاربر خودش
  قطع کرده باشد هرگز خودبه‌خود برنمی‌گردد.

### دسترس‌پذیری
- فیلدهای کشویی «لیست کشویی» و هر گزینه «دکمهٔ رادیویی» با وضعیت انتخاب خوانده می‌شود.
- دکمه‌های خروجی/ورودی «دکمه» و دکمهٔ غیرفعال «غیرفعال» خوانده می‌شود و نتیجهٔ عملیات
  هم نوشته و هم گفته می‌شود.
