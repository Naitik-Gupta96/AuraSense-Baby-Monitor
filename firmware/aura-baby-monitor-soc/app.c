/***************************************************************************//**
 * @file
 * @brief Top level application functions + BLE event handler
 ******************************************************************************/
#include "app.h"
#include "audio_classifier.h"
#include "sl_bt_api.h"
#include "gatt_db.h"
#include "app_assert.h"
#include "config/pin_config.h"
#include "em_gpio.h"
#include <stdio.h>

// Some generated GATT DB variants do not emit a separate ai_control handle.
// Fall back to inference_result so write handling still compiles.
#ifndef gattdb_ai_control
#define gattdb_ai_control gattdb_inference_result
#endif

// ============================================================================
// BLE SHARED STATE (declared extern in app.h)
// ============================================================================
volatile bool notifications_enabled = false;
uint8_t       connection_handle     = 0xFF;
uint8_t       last_class            = 0xFF;
volatile bool device_enabled        = true;
volatile bool monitoring_enabled    = true;
volatile bool deep_sleep_enabled    = false;
volatile uint8_t app_conf_threshold = 85;
volatile uint8_t app_debounce_count = 5;

// ============================================================================
// PRIVATE VARIABLES
// ============================================================================
static uint8_t advertising_set_handle = 0xFF;
static bool btn0_prev_pressed = false;
static bool btn1_prev_pressed = false;

#ifndef APP_BTN0_PORT
#define APP_BTN0_PORT gpioPortB
#endif
#ifndef APP_BTN0_PIN
#define APP_BTN0_PIN  2
#endif
#ifndef APP_BTN1_PORT
#define APP_BTN1_PORT gpioPortB
#endif
#ifndef APP_BTN1_PIN
#define APP_BTN1_PIN  3
#endif

#define CMD_MONITORING_ENABLE 1u
#define CMD_ALERTS_ENABLE     2u
#define CMD_THRESHOLD         3u
#define CMD_DEBOUNCE          4u
#define CMD_DEEP_SLEEP        5u
#define CMD_DEVICE_ENABLE     6u

static void app_init_button_inputs(void)
{
  GPIO_PinModeSet(APP_BTN0_PORT, APP_BTN0_PIN, gpioModeInputPullFilter, 1);
  GPIO_PinModeSet(APP_BTN1_PORT, APP_BTN1_PIN, gpioModeInputPullFilter, 1);
}

static bool button_pressed(GPIO_Port_TypeDef port, unsigned int pin)
{
  return (GPIO_PinInGet(port, pin) == 0);
}

static void app_poll_buttons(void)
{
  bool btn0_pressed = button_pressed(APP_BTN0_PORT, APP_BTN0_PIN);
  bool btn1_pressed = button_pressed(APP_BTN1_PORT, APP_BTN1_PIN);

  if (btn0_pressed && !btn0_prev_pressed) {
    device_enabled = true;
    monitoring_enabled = true;
    deep_sleep_enabled = false;
    printf("[CTRL] BTN0 -> DEVICE ON\n");
  }

  if (btn1_pressed && !btn1_prev_pressed) {
    device_enabled = false;
    deep_sleep_enabled = true;
    printf("[CTRL] BTN1 -> DEVICE OFF + DEEP SLEEP\n");
  }

  btn0_prev_pressed = btn0_pressed;
  btn1_prev_pressed = btn1_pressed;
}

void app_apply_control_command(uint8_t command_id, uint8_t value)
{
  switch (command_id) {
    case CMD_MONITORING_ENABLE:
      monitoring_enabled = (value != 0);
      printf("[CTRL] monitoring=%u\n", (unsigned int)monitoring_enabled);
      break;

    case CMD_ALERTS_ENABLE:
      // Trigger policy is app-side; keep command for protocol compatibility.
      printf("[CTRL] alerts_enable=%u\n", (unsigned int)(value != 0));
      break;

    case CMD_THRESHOLD:
      if (value > 100) value = 100;
      app_conf_threshold = value;
      printf("[CTRL] threshold=%u\n", (unsigned int)app_conf_threshold);
      break;

    case CMD_DEBOUNCE:
      if (value < 1) value = 1;
      if (value > 10) value = 10;
      app_debounce_count = value;
      printf("[CTRL] debounce=%u\n", (unsigned int)app_debounce_count);
      break;

    case CMD_DEEP_SLEEP:
      deep_sleep_enabled = (value != 0);
      printf("[CTRL] deep_sleep=%u\n", (unsigned int)deep_sleep_enabled);
      break;

    case CMD_DEVICE_ENABLE:
      device_enabled = (value != 0);
      printf("[CTRL] device_enabled=%u\n", (unsigned int)device_enabled);
      break;

    default:
      printf("[CTRL] unknown command=%u value=%u\n",
             (unsigned int)command_id, (unsigned int)value);
      break;
  }
}

// ============================================================================
// APPLICATION INIT
// ============================================================================
void app_init(void)
{
  printf("[APP] Initializing audio classifier...\n");
  app_init_button_inputs();
  audio_classifier_init();
}

// ============================================================================
// APPLICATION PROCESS ACTION (called from main loop, not used with RTOS)
// ============================================================================
void app_process_action(void)
{
  // Poll physical override buttons in main loop.
  app_poll_buttons();
}

// ============================================================================
// BLE EVENT HANDLER
// ============================================================================
void sl_bt_on_event(sl_bt_msg_t *evt)
{
  sl_status_t sc;

  switch (SL_BT_MSG_ID(evt->header))
  {
    case sl_bt_evt_system_boot_id:
      printf("[BLE] System boot, starting advertiser...\n");

      sc = sl_bt_advertiser_create_set(&advertising_set_handle);
      app_assert_status(sc);

      sc = sl_bt_legacy_advertiser_generate_data(
               advertising_set_handle,
               sl_bt_advertiser_general_discoverable);
      app_assert_status(sc);

      sc = sl_bt_advertiser_set_timing(
               advertising_set_handle,
               160,
               160,
               0,
               0);
      app_assert_status(sc);

      sc = sl_bt_legacy_advertiser_start(
               advertising_set_handle,
               sl_bt_legacy_advertiser_connectable);
      app_assert_status(sc);

      printf("[BLE] Advertising as 'Baby Cry Detector'\n");
      break;

    case sl_bt_evt_connection_opened_id:
      connection_handle = evt->data.evt_connection_opened.connection;
      printf("[BLE] Connected (handle=%d)\n", connection_handle);
      break;

    case sl_bt_evt_connection_closed_id:
      printf("[BLE] Disconnected (reason=0x%02X)\n",
             (unsigned int)evt->data.evt_connection_closed.reason);

      notifications_enabled = false;
      last_class            = 0xFF;
      connection_handle     = 0xFF;

      sc = sl_bt_legacy_advertiser_generate_data(
               advertising_set_handle,
               sl_bt_advertiser_general_discoverable);
      app_assert_status(sc);

      sc = sl_bt_legacy_advertiser_start(
               advertising_set_handle,
               sl_bt_legacy_advertiser_connectable);
      app_assert_status(sc);

      printf("[BLE] Advertising restarted\n");
      break;

    case sl_bt_evt_gatt_server_characteristic_status_id:
      if (evt->data.evt_gatt_server_characteristic_status.characteristic
          == gattdb_inference_result)
      {
        if (evt->data.evt_gatt_server_characteristic_status.status_flags
            == sl_bt_gatt_server_client_config)
        {
          uint16_t flags =
            evt->data.evt_gatt_server_characteristic_status.client_config_flags;

          notifications_enabled = (flags & sl_bt_gatt_notification) ? true : false;

          if (notifications_enabled) {
            last_class = 0xFF;
          }

          printf("[BLE] Notifications %s\n",
                 notifications_enabled ? "ENABLED" : "DISABLED");
        }
      }
      break;

    case sl_bt_evt_gatt_server_attribute_value_id:
      if (evt->data.evt_gatt_server_attribute_value.attribute == gattdb_ai_control) {
        const byte_array *value = &evt->data.evt_gatt_server_attribute_value.value;
        if (value->len >= 2) {
          app_apply_control_command(value->data[0], value->data[1]);
        }
      }
      break;

    default:
      break;
  }
}

// ============================================================================
// BLE NOTIFICATION SENDER
// ============================================================================
void send_ble_notification(uint8_t class_id, uint8_t confidence_pct)
{
  if (!notifications_enabled || connection_handle == 0xFF) {
    return;
  }

  uint8_t payload[2] = {
    class_id,
    confidence_pct
  };

  sl_status_t sc = sl_bt_gatt_server_send_notification(
      connection_handle,
      gattdb_inference_result,
      sizeof(payload),
      payload);

  if (sc == SL_STATUS_OK) {
    printf("[BLE] Notify: class=%d conf=%u%%\n",
           class_id, (unsigned int)payload[1]);
  }
}
