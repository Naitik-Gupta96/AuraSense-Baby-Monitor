# AuraSense Baby Monitor

End-to-end baby cry monitoring system using:

- **EFR32xG26 SoC firmware** (on-device audio inference + BLE)
- **Android mobile app** (monitoring UI + alerts + controls)
- **Model training assets** (dataset + training script + deployable `.tflite`)

This repository is organized so you can clone it, follow one guide, and run the full stack.

## Repository Structure

```text
firmware/
  aura-baby-monitor-soc/        # Main SoC firmware project (stable source)
  releases/                     # Ready-to-flash firmware images (.s37)

mobile/
  aurasense-android-app/        # Android app source code

model/
  training/                     # Model training script(s)
  artifacts/                    # Deployable model files (.tflite)
  datasets/                     # Training dataset folders
```

## 1. Flash Firmware (Fastest Path)

Use the prebuilt `.s37` image:

- `firmware/releases/aura_baby_monitor.s37`

### Flash command (Simplicity Commander)

```bash
commander flash firmware/releases/aura_baby_monitor.s37 --device EFR32MG26 --serialno <YOUR_JLINK_SN>
commander device reset --serialno <YOUR_JLINK_SN>
```

If you have only one connected adapter, `--serialno` can be omitted.

## 2. Build Firmware From Source (Simplicity Studio 6)

Open project:

1. Launch Simplicity Studio 6
2. `File -> Import`
3. Select `firmware/aura-baby-monitor-soc/ml_ble_classifier.slcp`
4. Build project
5. Flash to target

Notes:

- Main inference/runtime code:
  - `firmware/aura-baby-monitor-soc/audio_classifier.cc`
  - `firmware/aura-baby-monitor-soc/app.c`
- Model in firmware config:
  - `firmware/aura-baby-monitor-soc/config/tflite/baby_cry_int8_DEPLOY.tflite`

## 3. Build and Run Android App

Project:

- `mobile/aurasense-android-app`

Steps:

1. Open folder in Android Studio
2. Let Gradle sync complete
3. Connect Android phone
4. Build and run app

CLI option:

```bash
cd mobile/aurasense-android-app
./gradlew assembleDebug
```

APK output (default):

- `mobile/aurasense-android-app/mobile/build/outputs/apk/debug/`

## 4. BLE Behavior Summary

- Firmware sends inference result over BLE characteristic (class + confidence)
- App consumes this stream and applies monitor/alert logic
- Temperature support is integrated where available

## 5. Model Training and Deployment

Training assets:

- Script: `model/training/training.py`
- Dataset root: `model/datasets/`
- Deployable TFLite: `model/artifacts/baby_cry_int8_DEPLOY.tflite`

Typical flow:

1. Train/update model with `training.py`
2. Export quantized `.tflite`
3. Replace firmware model file in:
   - `firmware/aura-baby-monitor-soc/config/tflite/`
4. Rebuild and flash firmware

## 6. Troubleshooting

- If app cannot discover data:
  - Verify BLE is connected
  - Verify notifications/indications are enabled
  - Confirm correct firmware is flashed
- If build fails in Studio due missing SDK/toolchain:
  - Re-run Simplicity Studio package install (`gcc-arm-none-eabi`, `ninja`, `cmake`, `simplicity-sdk`, `aiml`)
- If temperature shows `N/A`:
  - Ensure firmware with sensor-enabled configuration is flashed

## 7. What Is Included in GitHub Push

- Firmware source (stable branch of your prepared project)
- Android app source
- Model training code + model artifact + dataset
- Prebuilt `.s37` release image(s)
