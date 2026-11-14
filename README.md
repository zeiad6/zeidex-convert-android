# Zeidex Convert — Android

تطبيق أندرويد محلي لتحويل الملفات ومعالجة الوسائط، بلا رفع ولا حساب. مبني بـ **Kotlin + Jetpack Compose** ويعمل مع النسخة المكتبية عبر **LocalDrop** (نقل ملفات على شبكة Wi-Fi المحلية بمسح QR).

<p align="center">
  <b>متطلّبات:</b> Android 8.0+ (API 26)، وJDK 17، وAndroid SDK 35.
</p>

## البناء

```bash
gradle :app:testDebugUnitTest      # الاختبارات
gradle :app:assembleDebug          # APK للتطوير
gradle :app:assembleRelease        # APK موقّع (يحتاج keystore/release.keystore)
```

- `keystore/release.keystore` غير مرفوع إلى المستودع؛ التوقيع يحدث على جهاز المطوّر.
- `UpdateChecker` يرفض أي تحديث لا يطابق SHA-256 وبصمة شهادة التوقيع `d24bf9df…`.
- خطوات الإصدار الكاملة في [`RELEASING.md`](RELEASING.md).

## البنية

```
app/src/main/java/com/zeidex/convert/
├── engine/      محرّكات التحويل، LocalDrop، QR، قراءة المستندات
├── ui/          شاشات Compose، العجلة، المهام، الإعدادات
├── util/        الكتابة الذرية، التحقق من التحديثات، أدوات الملفات
└── work/        مهام الخلفية والتقدّم
```

## الرخصة

[MIT](../LICENSE) © 2026 Zeidexsoft — نفس ترخيص المشروع الأم.

---

## English

An offline Android companion for [Zeidex Convert](https://github.com/zeiad6/zeidexfileconventer): convert files and media on the device, and receive files from the desktop app over the local network with LocalDrop (QR pairing, no server).

Build with `gradle :app:assembleDebug` (JDK 17, SDK 35). Release signing, the pinned certificate fingerprint and the update manifest rules are documented in [`RELEASING.md`](RELEASING.md).
