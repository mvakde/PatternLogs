    package com.example.discretelogger

    import android.content.BroadcastReceiver
    import android.content.ContentUris
    import android.content.ContentValues
    import android.content.Context
    import android.content.Intent
    import android.content.IntentFilter
    import android.hardware.Sensor
    import android.hardware.SensorEvent
    import android.hardware.SensorEventListener
    import android.hardware.SensorManager
    import android.os.Bundle
    import android.os.Environment
    import android.os.PowerManager
    import android.os.SystemClock
    import android.provider.MediaStore
    import android.util.Log
    import android.view.MotionEvent
    import android.widget.Button
    import android.widget.EditText
    import androidx.appcompat.app.AppCompatActivity
    import java.io.BufferedWriter
    import java.io.IOException
    import java.io.OutputStreamWriter
    import java.text.SimpleDateFormat
    import java.util.*
    import java.util.concurrent.ExecutorService
    import java.util.concurrent.Executors

    class MainActivity : AppCompatActivity() {

        private lateinit var backgroundExecutor: ExecutorService
        private val touchStartMap = mutableMapOf<Int, Long>()
        private val peakPressureMap = mutableMapOf<Int, Float>()
        private lateinit var sensorManager: SensorManager
        private var pressureSensor: Sensor? = null
        private var currentAmbientPressure: Float = 0.0f

        private var isLoggingEnabled = false
        private lateinit var screenStateReceiver: ScreenStateReceiver
        private val logSubDir = "DiscreteLogger" // Subdirectory in Downloads

        // Reference to our custom text EditText, the send Button,
        // and a variable to remember the last non-empty text.
        private lateinit var customEditText: EditText
        private lateinit var sendButton: Button

        // Holds pending tap events that have not yet been written to file.
        private val pendingTaps = mutableListOf<TapEvent>()

        // Data class to represent a tap event
        data class TapEvent(val startTime: Long, val duration: Long, val peakPressure: Float, var customText: String)
        private val pressureListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_PRESSURE) {
                    // event.values[0] is the raw ambient pressure in hPa
                    currentAmbientPressure = event.values[0]
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // No-op
            }
        }

        private fun requestNotificationPermission() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            requestNotificationPermission()
            setContentView(R.layout.activity_main)

            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)


            // Initialize the executor and the receiver.
            backgroundExecutor = Executors.newSingleThreadExecutor()
            screenStateReceiver = ScreenStateReceiver()

            // Find our custom text EditText and send Button from the layout.
            customEditText = findViewById(R.id.customEditText)
            sendButton = findViewById(R.id.sendButton)
            sendButton.setOnClickListener {
                val newText = customEditText.text.toString().trim()
                if (newText.isNotEmpty()) {
                    // Update all pending taps that don't have a custom note
                    pendingTaps.filter { it.customText.isEmpty() }
                        .forEach { it.customText = newText }

                    // Write all pending taps to the CSV file
                    writePendingTapsToCsv()

                    // Clear the input field after sending
                    customEditText.setText("")
                }
            }
        }

        override fun onResume() {
            super.onResume()
            checkAndBackupLogs()
            registerReceivers()
            pressureSensor?.also { sensor ->
                sensorManager.registerListener(pressureListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            checkScreenState()
        }

        private fun registerReceivers() {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(screenStateReceiver, filter)
        }

        private fun checkScreenState() {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            isLoggingEnabled = pm.isInteractive
        }

        override fun onPause() {
            super.onPause()
            unregisterReceiver(screenStateReceiver)
            sensorManager.unregisterListener(pressureListener)
            stopLogging()
        }

        private fun stopLogging() {
            isLoggingEnabled = false
            touchStartMap.clear()
        }

        override fun onDestroy() {
            shutdownExecutor()
            super.onDestroy()
        }

        private fun shutdownExecutor() {
            backgroundExecutor.shutdown()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isLoggingEnabled) return false

            val action = event.actionMasked
            val pointerIndex = event.actionIndex
            val pointerId = event.getPointerId(pointerIndex)

            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val startTime = convertEventTimeToTimestamp(event)
                    touchStartMap[pointerId] = startTime
                    peakPressureMap[pointerId] = currentAmbientPressure
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val id = event.getPointerId(i)
                        // Update the stored peak pressure with the current ambient pressure if it's higher
                        peakPressureMap[id] = maxOf(peakPressureMap[id] ?: currentAmbientPressure, currentAmbientPressure)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val endTime = convertEventTimeToTimestamp(event)
                    val startTime = touchStartMap[pointerId] ?: return false
                    val duration = endTime - startTime
                    val peakPressure = peakPressureMap[pointerId] ?: currentAmbientPressure

                    logToCsv(startTime, duration, peakPressure)
                    touchStartMap.remove(pointerId)
                    peakPressureMap.remove(pointerId)
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchStartMap.remove(pointerId)
                    peakPressureMap.remove(pointerId)
                }
            }
            return true
        }

        private fun convertEventTimeToTimestamp(event: MotionEvent): Long {
            val eventTimeMillis = event.eventTime
            val uptimeMillis = SystemClock.uptimeMillis()
            return System.currentTimeMillis() - (uptimeMillis - eventTimeMillis)
        }

        /**
         * Retrieves the URI for the CSV file for a given day. If the file does not exist,
         * it is created. Note that when running on Android Q or later, we add a condition on
         * RELATIVE_PATH so that an existing file (with the correct directory) is found.
         */
        // Retrieves the URI for the main log CSV file ("discreteLogs.csv")
        private fun getFileUri(): android.net.Uri? {
            val resolver = contentResolver
            val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }

            // Prepare the query to check if the file exists.
            val queryColumns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val selection: String
            val selectionArgs: Array<String>
            val fileName = "discreteLogs.csv"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                selectionArgs = arrayOf(fileName, "${Environment.DIRECTORY_DOWNLOADS}/$logSubDir/")
            } else {
                selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                selectionArgs = arrayOf(fileName)
            }

            resolver.query(collection, queryColumns, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val id = cursor.getLong(idColumn)
                    // Use ContentUris.withAppendedId to form the proper Uri.
                    return ContentUris.withAppendedId(collection, id)
                }
            }

            // File doesn't exist, so create it.
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$logSubDir")
                }
            }

            return resolver.insert(collection, contentValues)
        }


        private fun logToCsv(startTime: Long, duration: Long, peakPressure: Float) {
            // Create a new tap event with an empty customText (to be updated later)
            val tapEvent = TapEvent(startTime, duration, peakPressure, "")
            // Store it in the list of pending taps
            pendingTaps.add(tapEvent)
        }

        /**
         * Writes all pending tap events to the CSV file. We first get today’s date and then
         * either locate or create the corresponding CSV file. We open the stream in append mode
         * ("wa") so that new events are added to the file.
         */
        private fun writePendingTapsToCsv() {
            backgroundExecutor.execute {
                val uri = getFileUri() ?: run {
                    Log.e("MainActivity", "Failed to create/get file URI")
                    return@execute
                }

                try {
                    // Open the stream in append mode ("wa") so that we do not overwrite previous data.
                    contentResolver.openOutputStream(uri, "wa")?.use { outputStream ->
                        val writer = BufferedWriter(OutputStreamWriter(outputStream))

                        // If the file is empty, write the header
                        if (getFileSize(uri) == 0L) {
                            writer.write("StartTimestamp,Duration(ms),PeakPressure,CustomText")
                            writer.newLine()
                        }

                        // Write all pending tap events
                        pendingTaps.forEach { event ->
                            writer.write("${event.startTime},${event.duration},${event.peakPressure},${event.customText}")
                            writer.newLine()
                        }
                        writer.flush()
                    }

                    // Clear the pending taps after writing them to the file
                    pendingTaps.clear()
                } catch (e: IOException) {
                    Log.e("MainActivity", "Error writing pending taps to file", e)
                }
            }
        }

        private fun getFileSize(uri: android.net.Uri): Long {
            val cursor = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
            return cursor?.use {
                if (it.moveToFirst())
                    it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                else
                    0L
            } ?: 0L
        }

        private fun timestampToDate(timestamp: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

        private inner class ScreenStateReceiver : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> stopLogging()
                    Intent.ACTION_SCREEN_ON -> startLoggingIfForeground()
                }
            }
        }

        private fun startLoggingIfForeground() {
            if (!isFinishing && !isDestroyed) {
                isLoggingEnabled = true
            }
        }
        // NEW: Checks if a backup is needed and performs it.
        private fun checkAndBackupLogs() {
            val prefs = getSharedPreferences("LogPrefs", Context.MODE_PRIVATE)
            val lastBackupDate = prefs.getString("lastBackupDate", "")
            val today = timestampToDate(System.currentTimeMillis())
            if (lastBackupDate != today) {  // first open today
                // Compute yesterday’s date for the backup file name
                val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val yesterday = sdf.format(calendar.time)

                // Get the URI of the main log file ("discreteLogs.csv")
                val logUri = getFileUri() // using your updated method
                if (logUri != null) {
                    backupLogFile(logUri, yesterday)
                }
                // Save today's date as the last backup date
                prefs.edit().putString("lastBackupDate", today).apply()
            }
        }

        // NEW: Copies the current log file to the Backups folder with the provided backup file name.
        private fun backupLogFile(sourceUri: android.net.Uri, backupDate: String) {
            backgroundExecutor.execute {
                try {
                    contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                        val resolver = contentResolver
                        val fileName = "$backupDate.csv"
                        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        } else {
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        }
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$logSubDir/Backups")
                            }
                        }
                        val backupUri = resolver.insert(collection, contentValues)
                        if (backupUri != null) {
                            contentResolver.openOutputStream(backupUri)?.use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.e("MainActivity", "Error backing up log file", e)
                }
            }
        }

    }
