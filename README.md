# OmniVerte Keyboard

**A modified version of [FUTO Keyboard](https://keyboard.futo.tech/).** It keeps FUTO's typing,
swipe and predictions as they are and replaces the voice input engine with
**Google Gemini Live** streaming transcription. FUTO's local Whisper model stays as an offline
fallback.

**Modified by OmniNeurons (Arseniy Bandurin).** This is a free, non-commercial hobby project. It is
**not affiliated with or endorsed by FUTO**.
The original FUTO Keyboard is developed by FUTO Holdings, Inc.:
[GitHub](https://github.com/futo-org/android-keyboard) ·
[website](https://keyboard.futo.tech/).

> Голосовой ввод через Gemini, всё остальное как в FUTO Keyboard. Краткая инструкция на русском
> языке есть ниже.

## What is changed compared to FUTO Keyboard

- **Voice input via Gemini Live** (`gemini-3.5-transcribe-live`). Audio streams while you speak,
  and text appears in the field as you talk. The final text arrives about 0.5 s after you stop.
  - All languages added to the keyboard are sent as hints, the current layout first.
  - Filler sounds ("uh", "um", "э-э") are removed. You can turn this off.
  - Recording stops after a pause of 5 s or longer (configurable). FUTO's default is about 2 s.
  - *Terms for Gemini*: your own list of names and terms that Gemini should spell exactly as
    written.
- **Offline fallback**: with no network, an invalid key or an exhausted quota, the same recording
  is transcribed by FUTO's on-device Whisper model if one is installed for the language. A label
  in the voice window shows which engine is in use.
- The language switch key is pinned next to the microphone.
- Own app ID (`com.omniverte.keyboard`), name and icon, so it installs next to the official
  FUTO Keyboard.
- Crash reports are not sent to FUTO. Update notices from FUTO are disabled.

Everything else, including FUTO's payment screen, license and notices, is kept as the
[FUTO Source First License](LICENSE.md) requires. Paying for FUTO Keyboard on that screen supports
FUTO, not this project.

## Privacy

FUTO Keyboard works fully offline. **This build does not when Gemini is on.** If you set a Gemini
API key, the audio of each voice input is sent to Google's Gemini API. Typing, swipe and
predictions never leave the device.

- Without a key, or with *Recognize with Gemini* turned off, voice input stays on the device
  (Whisper), the same as in FUTO.
- Each user uses **their own** Gemini API key. No key is built into the app.
- The key is stored encrypted with an Android Keystore key. It is kept outside FUTO's settings
  export.
- Google's [Gemini API terms](https://ai.google.dev/gemini-api/terms) apply to the audio you
  send. On the free tier Google may use the data to improve its products.

## Install

1. Download the APK from [Releases](../../releases), or add this repository to
   [Obtainium](https://github.com/ImranR98/Obtainium) to get updates automatically.
2. Enable *OmniVerte Keyboard* in Android's keyboard settings and select it.
3. Get a free API key at [Google AI Studio](https://aistudio.google.com/apikey).
4. Open the keyboard settings, go to *Voice input* → *Gemini voice input*, then tap
   *Paste from clipboard* and *Check key*.
5. Optional: download a Whisper model for your language under *Languages & Models* to get offline
   voice input.

### Коротко по-русски

1. Установите APK из [Releases](../../releases) или добавьте репозиторий в Obtainium.
2. Включите клавиатуру в настройках Android.
3. Получите бесплатный ключ на [Google AI Studio](https://aistudio.google.com/apikey).
4. Откройте настройки клавиатуры → *Голосовой ввод* → *Голосовой ввод Gemini*. Скопируйте ключ и
   нажмите *Вставить из буфера*, затем *Проверить ключ*.
5. Для офлайн-режима скачайте модель Whisper для своего языка в разделе *Языки и модели*.

Без ключа голосовой ввод работает как в обычном FUTO, на устройстве. С ключом аудио голосового
ввода отправляется в Google Gemini. Набор текста никуда не отправляется.

## Building

```
git clone --recursive https://github.com/OmniNeurons/OmniVerteKeyboard.git
# or, after a plain clone:
git submodule update --init --recursive
```

You need JDK 17 or 21 (not 25), Android SDK 35, NDK `28.2.13676358` and Python on `PATH`.
On Windows, set `PYTHONUTF8=1`.

```
./gradlew assembleUnstableDebug     # dev build, "OmniVerte Keyboard [Dev]"
./gradlew assembleStableRelease     # release build; signing via keystore.properties
./gradlew :voiceinput-shared:testDebugUnitTest   # JVM tests for the Gemini engine
```

The Gemini engine lives in
`voiceinput-shared/src/main/java/org/futo/voiceinput/shared/gemini/`. It connects to
`AudioRecognizer.kt`.

Upstream FUTO changes are merged from `https://github.com/futo-org/android-keyboard`.

## APK signing

Release APKs of this project are signed with the following key. It is different from FUTO's key,
so this app cannot update an official FUTO install and an official FUTO build cannot update it.

```
SHA1:   5E:5F:7B:8E:35:5A:0D:0D:AB:AA:79:AF:F1:22:01:2A:E1:53:6C:DB
SHA256: A8:38:9D:16:0D:45:0C:F2:96:4C:54:42:8C:8F:13:6E:88:34:E7:BC:7B:3D:6E:23:78:C3:23:23:66:0B:92:0C
```

## License

The code is licensed under the [FUTO Source First License 1.1](LICENSE.md). It may be modified and
distributed only free of charge and for non-commercial purposes. This repository is a modified
copy, and the modifications are listed above. The original copyright and notices of FUTO
Holdings, Inc. are kept.
