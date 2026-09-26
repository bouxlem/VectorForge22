<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/5a4a3a7e-cafb-4418-bb1f-08e9a2935162

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.

## بناء ملف APK (Build APK)

هذا المشروع ما فيهش ملفات `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` (سكربت الـwrapper)، فباش تبنيو ملف APK عندكم جوج خيارات:

### الخيار 1 (موصى به): GitHub Actions — بناء تلقائي فالسحابة، بلا Android Studio
1. ارفعو هذا المجلد كاملاً لمستودع (repository) جديد فـGitHub.
2. الملف `.github/workflows/build-apk.yml` غايخدم أوتوماتيكياً عند كل push.
3. مورو لتبويب **Actions** فالمستودع، اختارو آخر تشغيل ناجح، وحملو الـAPK من قسم **Artifacts** (اسمه `vectorforge-debug-apk`).

### الخيار 2: البناء محلياً بـAndroid Studio
1. حلو المشروع بـAndroid Studio (سيقترح توليد ملفات الـwrapper المفقودة أوتوماتيكياً، أو دبرو `gradle wrapper` يدوياً إذا عندكم Gradle مثبت).
2. Build → Build Bundle(s) / APK(s) → Build APK(s).
3. الـAPK غيتلقاوه فـ `app/build/outputs/apk/debug/app-debug.apk`.
