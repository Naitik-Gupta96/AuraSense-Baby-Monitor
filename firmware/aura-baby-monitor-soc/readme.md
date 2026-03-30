# Baby Cry Detection (EFR32xG26, On-Device)

This firmware runs a fused 4-class baby-audio model on Silicon Labs EFR32xG26 and reports results over BLE.

## Model and Classes

Model file in project:
- `config/tflite/baby_cry_unified_fp32.tflite`

Embedded C array (auto-generated):
- `autogen/sl_tflite_micro_model.c`

Class map:
- `0`: `background`
- `1`: `cry`
- `2`: `laugh`
- `3`: `sad`
- `255`: uncertain/no-report sentinel

## Alert Policy

The device only raises an alert for `sad`.

Firmware behavior:
- Inference runs continuously on 1-second windows.
- Results are smoothed over a short history.
- `sad` must persist for multiple consecutive frames before alerting (debounce).
- `cry` is monitor-only (no alert).
- `laugh` and `background` do not alert.

## BLE Payload

Characteristic: `gattdb_inference_result`

Notification payload (2 bytes):
- Byte 0: `class_id`
- Byte 1: `score_uint8` where `score_uint8 = int8_score + 128`

## App Control Characteristic

Service UUID:
- `a3c87500-8ed3-4bdf-8a39-a01bebede295`

Control Characteristic UUID:
- `a3c87502-8ed3-4bdf-8a39-a01bebede295`

2-byte write command format:
- Byte 0: command id
- Byte 1: command value

Command ids:
- `1` -> monitoring enable (`0/1`)
- `2` -> alerts enable (`0/1`, reserved for app policy)
- `3` -> confidence threshold (`0..100`)
- `4` -> debounce count (`1..10`)
- `5` -> deep sleep enable (`0/1`)
- `6` -> device enable (`0/1`)

Local button override (hardware):
- BTN0 -> device ON
- BTN1 -> device OFF + deep sleep

Pin mapping is defined in `config/pin_config.h`:
- `APP_BTN0_PORT/PIN`
- `APP_BTN1_PORT/PIN`

## Key Runtime Files

- `audio_classifier.cc`: inference loop, smoothing, variance gate, sad-only debounce alert logic
- `app.c`: BLE advertising/event handling and notifications
- `app.h`: shared BLE state and class ID documentation
- `config/audio_classifier_config.h`: fallback label order
- `config/sl_ml_audio_feature_generation_config.h`: frontend parameters

## Important Notes

- Keep label order consistent across training/export/firmware: `[background, cry, laugh, sad]`.
- If you replace the `.tflite`, regenerate `autogen/sl_tflite_micro_model.c` before building.
- Tensor arena sizing is configured in `config/sl_tflite_micro_config.h`.
- The current model expects a 3-channel input; the frontend provides mono features
  which are replicated into 3 channels at runtime.

## Build/Flash

Build and flash with Simplicity Studio project `ml_ble_classifier1`.

After boot:
- Connect from phone app over BLE.
- Enable notifications on `inference_result`.
- Trigger alert path with sustained sad/distress audio.
