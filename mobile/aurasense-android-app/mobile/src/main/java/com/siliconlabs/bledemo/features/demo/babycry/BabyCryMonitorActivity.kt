package com.siliconlabs.bledemo.features.demo.babycry

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.siliconlabs.bledemo.R
import com.siliconlabs.bledemo.base.activities.BaseDemoActivity
import com.siliconlabs.bledemo.bluetooth.ble.GattCharacteristic
import com.siliconlabs.bledemo.bluetooth.ble.GattService
import com.siliconlabs.bledemo.bluetooth.ble.TimeoutGattCallback
import com.siliconlabs.bledemo.databinding.ActivityBabyCryMonitorBinding
import com.siliconlabs.bledemo.features.demo.health_thermometer.models.TemperatureReading
import com.siliconlabs.bledemo.home_screen.dialogs.SelectDeviceDialog
import com.siliconlabs.bledemo.utils.AppUtil
import com.siliconlabs.bledemo.utils.BLEUtils.getCharacteristic
import com.siliconlabs.bledemo.utils.BLEUtils.setNotificationForCharacteristic
import com.siliconlabs.bledemo.utils.CustomToastManager
import com.siliconlabs.bledemo.utils.Notifications
import kotlin.math.max
import kotlin.math.min

@SuppressLint("MissingPermission")
class BabyCryMonitorActivity : BaseDemoActivity() {
    private lateinit var binding: ActivityBabyCryMonitorBinding

    private var serviceHasBeenSet = false
    private var monitoringEnabled = true
    private var alertsEnabled = true
    private var deviceEnabled = true
    private var deepSleepEnabled = false
    private var confidenceThreshold = 85
    private var debounceCount = 5
    private var ignoredAlerts = 0
    private var consecutiveMatches = 0
    private var escalationActive = false
    private var pendingAlertAcknowledgement = false
    private var lastPrimaryState: String = ""
    private var customRingtoneUri: Uri? = null
    private var activeRingtone: Ringtone? = null
    private var alertRingtone: Ringtone? = null
    private var alertDialog: AlertDialog? = null

    private var windowStartMs = 0L
    private var windowMaxSadConfidence = 0f
    private var windowSawSad = false

    private val gattCallback: TimeoutGattCallback = object : TimeoutGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                onDeviceDisconnected()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            setNotificationForCharacteristic(
                gatt,
                GattService.AiCryService,
                GattCharacteristic.AiInferenceResult,
                Notifications.NOTIFY
            )

            // Optional temperature from Environmental Sensing.
            getCharacteristic(
                gatt,
                GattService.EnvironmentalSensing,
                GattCharacteristic.EnvironmentTemperature
            )?.let { gatt.readCharacteristic(it) }

            // Optional temperature from Health Thermometer profile.
            setNotificationForCharacteristic(
                gatt,
                GattService.HealthThermometer,
                GattCharacteristic.Temperature,
                Notifications.INDICATE
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (descriptor.characteristic?.uuid == GattCharacteristic.AiInferenceResult.uuid) {
                getCharacteristic(
                    gatt,
                    GattService.EnvironmentalSensing,
                    GattCharacteristic.EnvironmentTemperature
                )?.let { gatt.readCharacteristic(it) }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return
            }
            handleTemperatureCharacteristic(characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (characteristic.uuid == GattCharacteristic.Temperature.uuid) {
                handleTemperatureCharacteristic(characteristic)
                return
            }
            if (characteristic.uuid != GattCharacteristic.AiInferenceResult.uuid) return

            val payload = characteristic.value ?: return
            if (payload.size < 2) {
                return
            }

            val classId = payload[0].toInt() and 0xFF
            val rawScore = payload[1].toInt() and 0xFF
            val confidence = scoreToConfidence(rawScore)
            val mapped = mapClass(classId)
            runOnUiThread {
                updateUiFromInference(gatt, classId, rawScore, confidence, mapped)
            }
        }
    }

    private val ringtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                customRingtoneUri = uri
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(PREF_KEY_RINGTONE_URI, uri.toString())
                    .apply()
                CustomToastManager.show(this, getString(R.string.baby_cry_ringtone_selected), 2500)
            } else {
                customRingtoneUri = null
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .remove(PREF_KEY_RINGTONE_URI)
                    .apply()
                CustomToastManager.show(this, getString(R.string.baby_cry_ringtone_default), 2500)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBabyCryMonitorBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        AppUtil.setEdgeToEdge(window, this)
        loadPreferences()
        createNotificationChannel()
        setupToolbar()
        setupControls()
        refreshSettingLabels()
        refreshAlertUi()
    }

    override fun onResume() {
        super.onResume()
        if (serviceHasBeenSet && (service == null || !(service?.isGattConnected(connectionAddress) == true))) {
            onDeviceDisconnected()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val dialog = supportFragmentManager.findFragmentByTag("select_device_tag") as? SelectDeviceDialog
        dialog?.dismiss()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            gatt?.disconnect()
            onBackPressedDispatcher.onBackPressed()
            true
        } else super.onOptionsItemSelected(item)
    }

    override fun onBluetoothServiceBound() {
        serviceHasBeenSet = true
        service?.registerGattCallback(true, gattCallback)
        gatt?.discoverServices()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.matter_back)
            title = getString(R.string.baby_cry_demo_title)
        }
    }

    private fun setupControls() {
        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            monitoringEnabled = isChecked
            sendControlCommand(CMD_MONITORING_ENABLE, if (isChecked) 1 else 0)
            if (!monitoringEnabled) {
                binding.tvCurrentClass.text = getString(R.string.baby_cry_waiting_for_data)
            }
        }

        binding.switchAlerts.setOnCheckedChangeListener { _, isChecked ->
            alertsEnabled = isChecked
            sendControlCommand(CMD_ALERTS_ENABLE, if (isChecked) 1 else 0)
            if (!alertsEnabled) {
                resetTriggerState()
                pendingAlertAcknowledgement = false
                stopEscalationTone()
            }
            refreshAlertUi()
        }

        binding.switchDeviceEnabled.setOnCheckedChangeListener { _, isChecked ->
            deviceEnabled = isChecked
            sendControlCommand(CMD_DEVICE_ENABLE, if (isChecked) 1 else 0)
        }

        binding.switchDeepSleep.setOnCheckedChangeListener { _, isChecked ->
            deepSleepEnabled = isChecked
            sendControlCommand(CMD_DEEP_SLEEP, if (isChecked) 1 else 0)
        }

        binding.seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                confidenceThreshold = progress
                refreshSettingLabels()
                sendControlCommand(CMD_THRESHOLD, confidenceThreshold)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.seekDebounce.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                debounceCount = 1 + progress
                refreshSettingLabels()
                sendControlCommand(CMD_DEBOUNCE, debounceCount)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.btnAcknowledge.setOnClickListener {
            ignoredAlerts = 0
            escalationActive = false
            consecutiveMatches = 0
            pendingAlertAcknowledgement = false
            stopEscalationTone()
            refreshAlertUi()
        }

        binding.btnPickRingtone.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    customRingtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                )
            }
            ringtonePickerLauncher.launch(intent)
        }

        binding.switchMonitoring.isChecked = monitoringEnabled
        binding.switchAlerts.isChecked = alertsEnabled
        binding.switchDeviceEnabled.isChecked = deviceEnabled
        binding.switchDeepSleep.isChecked = deepSleepEnabled
        binding.seekThreshold.max = 100
        binding.seekThreshold.progress = confidenceThreshold
        binding.seekDebounce.progress = debounceCount - 1
    }

    private fun refreshSettingLabels() {
        binding.tvThresholdValue.text = getString(R.string.baby_cry_threshold_value, confidenceThreshold)
        binding.tvDebounceValue.text = getString(R.string.baby_cry_debounce_value, debounceCount)
    }

    private fun updateUiFromInference(
        gatt: BluetoothGatt,
        classId: Int,
        rawScore: Int,
        confidence: Float,
        mapping: ClassMapping
    ) {
        val deviceName = gatt.device.name ?: gatt.device.address ?: "Device"
        binding.connectionBarText.text = getString(R.string.baby_cry_connected_to, deviceName)
        binding.tvRawPacket.text = getString(R.string.baby_cry_raw_packet_value, classId, rawScore)
        binding.tvConfidence.text = getString(R.string.baby_cry_confidence_value, confidence)
        binding.progressConfidence.progress = confidence.toInt().coerceIn(0, 100)
        binding.tvCurrentClass.text = if (monitoringEnabled) mapping.primary else getString(R.string.baby_cry_waiting_for_data)
        binding.tvCrySubclass.text = getString(R.string.baby_cry_subclass_value, mapping.subclass)
        binding.tvLedState.text = getString(mapping.ledStringRes)
        binding.tvEsclationProfile.text = getString(R.string.baby_cry_profile_aurasense)

        if (!monitoringEnabled || !alertsEnabled) {
            resetTriggerState()
            refreshAlertUi()
            return
        }

        // Instant output is always shown, but trigger decision is evaluated once per 5-second window.
        val now = System.currentTimeMillis()
        if (windowStartMs == 0L) windowStartMs = now

        if (mapping.isSad) {
            windowSawSad = true
            if (confidence > windowMaxSadConfidence) {
                windowMaxSadConfidence = confidence
            }
        }

        if (now - windowStartMs >= TRIGGER_WINDOW_MS) {
            val triggerForWindow = windowSawSad && windowMaxSadConfidence >= confidenceThreshold
            evaluateWindowTrigger(triggerForWindow)
            windowStartMs = now
            windowSawSad = mapping.isSad
            windowMaxSadConfidence = if (mapping.isSad) confidence else 0f
        }

        lastPrimaryState = mapping.primary
        refreshAlertUi()
    }

    private fun refreshAlertUi() {
        binding.tvIgnoredAlerts.text = getString(R.string.baby_cry_ignored_alerts_value, ignoredAlerts)
        binding.tvEscalationState.text =
            if (escalationActive) getString(R.string.baby_cry_escalation_active)
            else getString(R.string.baby_cry_escalation_inactive)
    }

    private fun resetTriggerState() {
        consecutiveMatches = 0
        windowStartMs = 0L
        windowSawSad = false
        windowMaxSadConfidence = 0f
    }

    private fun evaluateWindowTrigger(triggerForWindow: Boolean) {
        if (triggerForWindow) {
            consecutiveMatches += 1
        } else {
            consecutiveMatches = 0
        }

        if (triggerForWindow && consecutiveMatches >= debounceCount) {
            onTriggerDetected()
        }
    }

    private fun onTriggerDetected() {
        if (pendingAlertAcknowledgement) {
            ignoredAlerts += 1
        }
        pendingAlertAcknowledgement = true

        playAlertToneOnce()
        showTriggerPopup(
            getString(R.string.baby_cry_trigger_popup_title),
            getString(R.string.baby_cry_trigger_popup_message)
        )
        showLocalNotification(
            getString(R.string.baby_cry_trigger_popup_title),
            getString(R.string.baby_cry_trigger_popup_message)
        )

        if (ignoredAlerts >= ESCALATION_IGNORED_LIMIT) {
            escalationActive = true
            playEscalationTone()
            showTriggerPopup(
                getString(R.string.baby_cry_escalation_popup_title),
                getString(R.string.baby_cry_escalation_popup_message)
            )
            showLocalNotification(
                getString(R.string.baby_cry_escalation_popup_title),
                getString(R.string.baby_cry_escalation_popup_message)
            )
        }
    }

    private fun showTriggerPopup(title: String, message: String) {
        if (isFinishing || isDestroyed || alertDialog?.isShowing == true) return
        alertDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showLocalNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.redesign_ic_demo_health_thermometer)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Baby Monitor Alerts",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun playEscalationTone() {
        stopEscalationTone()
        val uri = customRingtoneUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        activeRingtone = RingtoneManager.getRingtone(this, uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activeRingtone?.isLooping = true
        }
        activeRingtone?.play()
    }

    private fun playAlertToneOnce() {
        alertRingtone?.stop()
        val uri = customRingtoneUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        alertRingtone = RingtoneManager.getRingtone(this, uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            alertRingtone?.isLooping = false
        }
        alertRingtone?.play()
    }

    private fun stopEscalationTone() {
        activeRingtone?.stop()
        activeRingtone = null
        alertRingtone?.stop()
        alertRingtone = null
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        customRingtoneUri = prefs.getString(PREF_KEY_RINGTONE_URI, null)?.let(Uri::parse)
    }

    private fun sendControlCommand(command: Int, value: Int) {
        val bluetoothGatt = gatt ?: return
        val characteristic = getCharacteristic(
            bluetoothGatt,
            GattService.AiCryService,
            GattCharacteristic.AiControl
        ) ?: run {
            if (command == CMD_DEVICE_ENABLE || command == CMD_DEEP_SLEEP) {
                CustomToastManager.show(this, getString(R.string.baby_cry_control_not_supported), 1500)
            }
            return
        }
        characteristic.value = byteArrayOf(
            command.toByte(),
            value.coerceIn(0, 255).toByte()
        )
        bluetoothGatt.writeCharacteristic(characteristic)
    }

    private fun handleTemperatureCharacteristic(characteristic: BluetoothGattCharacteristic) {
        when (characteristic.uuid) {
            GattCharacteristic.EnvironmentTemperature.uuid -> {
                val raw = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT16, 0)
                if (raw != null) {
                    val celsius = convertEnvironmentalTemperature(raw)
                    runOnUiThread {
                        binding.tvTemperature.text = if (celsius != null) {
                            getString(R.string.baby_cry_temperature_value, celsius)
                        } else {
                            getString(R.string.baby_cry_temperature_na)
                        }
                    }
                }
            }

            GattCharacteristic.Temperature.uuid -> {
                val reading = TemperatureReading.fromCharacteristic(characteristic)
                val celsius = reading.getTemperature(TemperatureReading.Type.CELSIUS).toFloat()
                runOnUiThread {
                    binding.tvTemperature.text = getString(R.string.baby_cry_temperature_value, celsius)
                }
            }
        }
    }

    private fun convertEnvironmentalTemperature(raw: Int): Float? {
        // RHT service uses 0x7FFF as invalid/uninitialized temperature.
        if (raw == RHT_INVALID_RAW) return null

        // Standard Environmental Temperature scaling is usually 0.01 C.
        val cBy100 = raw / 100.0f
        if (cBy100 in VALID_TEMP_MIN_C..VALID_TEMP_MAX_C) return cBy100

        // Some firmware variants expose milli-Celsius.
        val cBy1000 = raw / 1000.0f
        if (cBy1000 in VALID_TEMP_MIN_C..VALID_TEMP_MAX_C) return cBy1000

        return null
    }

    private data class ClassMapping(
        val primary: String,
        val subclass: String,
        val isSad: Boolean,
        val ledStringRes: Int
    )

    private fun mapClass(classId: Int): ClassMapping {
        // Unified UI contract:
        // Primary state: CRY vs NOT CRY/BACKGROUND
        // Cry subtype: SAD / LAUGH / UNKNOWN
        return when (classId) {
            0 -> ClassMapping(
                primary = getString(R.string.baby_cry_primary_not_cry),
                subclass = getString(R.string.baby_cry_class_background),
                isSad = false,
                ledStringRes = R.string.baby_cry_led_red
            )

            1 -> ClassMapping(
                primary = getString(R.string.baby_cry_primary_cry),
                subclass = getString(R.string.baby_cry_class_laugh),
                isSad = false,
                ledStringRes = R.string.baby_cry_led_yellow
            )

            2, 3 -> ClassMapping(
                primary = getString(R.string.baby_cry_primary_cry),
                subclass = getString(R.string.baby_cry_class_sad),
                isSad = true,
                ledStringRes = R.string.baby_cry_led_blue
            )

            else -> ClassMapping(
                primary = getString(R.string.baby_cry_primary_not_cry),
                subclass = getString(R.string.baby_cry_class_unknown),
                isSad = false,
                ledStringRes = R.string.baby_cry_led_red
            )
        }
    }

    private fun scoreToConfidence(rawScore: Int): Float {
        val clamped = max(0, min(100, rawScore))
        return clamped.toFloat()
    }

    override fun onDestroy() {
        stopEscalationTone()
        alertDialog?.dismiss()
        alertDialog = null
        super.onDestroy()
    }

    companion object {
        private const val TRIGGER_WINDOW_MS = 5000L
        private const val ESCALATION_IGNORED_LIMIT = 5
        private const val RHT_INVALID_RAW = 0x7FFF
        private const val VALID_TEMP_MIN_C = -40.0f
        private const val VALID_TEMP_MAX_C = 125.0f

        private const val ALERT_CHANNEL_ID = "baby_monitor_alerts"

        private const val PREFS_NAME = "baby_cry_monitor_prefs"
        private const val PREF_KEY_RINGTONE_URI = "ringtone_uri"

        // App-level custom control protocol over AiControl characteristic.
        private const val CMD_MONITORING_ENABLE = 1
        private const val CMD_ALERTS_ENABLE = 2
        private const val CMD_THRESHOLD = 3
        private const val CMD_DEBOUNCE = 4
        private const val CMD_DEEP_SLEEP = 5
        private const val CMD_DEVICE_ENABLE = 6
    }
}
