package com.company.PDRApp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pdrapp.ui.theme.PDRAppTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var magSensor: Sensor? = null

    // 🔥 Step data
    private var totalSteps by mutableStateOf(0)
    private var sessionSteps by mutableStateOf(0)
    private var sessionActive by mutableStateOf(false)

    // 🔥 Step detection logic (unchanged & stable)
    private var lastMagnitude = 0f
    private val stepThreshold = 11.5f
    private var lastStepTime = 0L

    // 🧭 Compass
    private var accelValues = FloatArray(3)
    private var magValues = FloatArray(3)
    private var heading by mutableStateOf(0f)

    // 📍 Human-readable movement
    private var forwardMeters by mutableStateOf(0f)   // ⬆️⬇️
    private var rightMeters by mutableStateOf(0f)     // ⬅️➡️

    private val stepLength = 0.7f   // average human step
    private lateinit var database: DatabaseReference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Firebase test (KEEP THIS – it works)
        val firebaseDatabase = FirebaseDatabase.getInstance()
        val testRef = firebaseDatabase.getReference("test")
        testRef.setValue("HELLO_FROM_PHONE")

        // ✅ Main database reference for PDR
        database = firebaseDatabase.getReference("pdr")

        // ✅ CREATE HANDLER (THIS WAS MISSING)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        // ✅ Firebase sender every 1 second
        handler.postDelayed(object : Runnable {
            override fun run() {

                if (sessionActive) {
                    val stepData = mapOf(
                        "steps" to sessionSteps,
                        "heading" to heading,
                        "forward_m" to forwardMeters,
                        "right_m" to rightMeters,
                        "timestamp" to System.currentTimeMillis()
                    )

                    database.child("live").setValue(stepData)
                }

                handler.postDelayed(this, 1000)
            }
        }, 1000)

        // ✅ Sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // ✅ UI
        setContent {
            PDRAppTheme {
                PDRScreen(
                    sessionSteps = sessionSteps,
                    heading = heading,
                    forwardMeters = forwardMeters,
                    rightMeters = rightMeters,
                    sessionActive = sessionActive,
                    onStartSession = {
                        sessionSteps = 0
                        forwardMeters = 0f
                        rightMeters = 0f
                        sessionActive = true
                    },
                    onResetSession = {
                        sessionSteps = 0
                        forwardMeters = 0f
                        rightMeters = 0f
                        sessionActive = false
                    }
                )
            }
        }
    }
    override fun onResume() {
        super.onResume()
       accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
       magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {

        when (event?.sensor?.type) {

            Sensor.TYPE_ACCELEROMETER -> {
                accelValues = event.values.clone()

                val x = accelValues[0]
                val y = accelValues[1]
                val z = accelValues[2]

                val magnitude = sqrt(x * x + y * y + z * z)
                val now = System.currentTimeMillis()

                if (
                    magnitude > stepThreshold &&
                    lastMagnitude <= stepThreshold &&
                    (now - lastStepTime) > 500
                ) {
                    totalSteps++
                    if (sessionActive) {
                        this@MainActivity.runOnUiThread {

                            // 1️⃣ Increase step count
                            sessionSteps++

                            // 2️⃣ Convert heading to radians
                            val rad = Math.toRadians(heading.toDouble())

                            // 3️⃣ Update position (simple PDR)
                            forwardMeters += (stepLength * cos(rad)).toFloat()
                            rightMeters += (stepLength * sin(rad)).toFloat()
                        }

                        // 🔥 Send PDR data to Firebase
                        val stepData = mapOf(
                            "steps" to sessionSteps,
                            "heading" to heading,
                            "forward_m" to forwardMeters,
                            "right_m" to rightMeters,
                            "timestamp" to System.currentTimeMillis()
                        )

                        database.child("live").setValue(stepData)
                    }
                    lastStepTime = now
                }

                lastMagnitude = magnitude
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                magValues = event.values.clone()
            }
        }

        // 🧭 Compass calculation
        val R = FloatArray(9)
        val O = FloatArray(3)

        if (SensorManager.getRotationMatrix(R, null, accelValues, magValues)) {
            SensorManager.getOrientation(R, O)
            heading = Math.toDegrees(O[0].toDouble()).toFloat()
            if (heading < 0) heading += 360f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Composable
fun PDRScreen(
    sessionSteps: Int,
    heading: Float,
    forwardMeters: Float,
    rightMeters: Float,
    sessionActive: Boolean,
    onStartSession: () -> Unit,
    onResetSession: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text("PDR Navigation", fontSize = 24.sp, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.height(16.dp))

            Text("Steps: $sessionSteps", fontSize = 44.sp, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.height(12.dp))

            Text("Heading: ${heading.toInt()}°", fontSize = 18.sp)

            Spacer(modifier = Modifier.height(20.dp))

            // ⬆️⬇️⬅️➡️ Direction arrows
            Text(
                text = when {
                    forwardMeters > 0.3f -> "⬆️ Moving Forward"
                    forwardMeters < -0.3f -> "⬇️ Moving Backward"
                    rightMeters > 0.3f -> "➡️ Moving Right"
                    rightMeters < -0.3f -> "⬅️ Moving Left"
                    else -> "⏸ Standing Still"
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row {
                Button(onClick = onStartSession) {
                    Text(if (sessionActive) "Session Running" else "Start Session")
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(onClick = onResetSession) {
                    Text("Reset")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewUI() {
    PDRAppTheme {
        PDRScreen(
            sessionSteps = 12,
            heading = 90f,
            forwardMeters = 3.5f,
            rightMeters = -1.2f,
            sessionActive = true,
            onStartSession = {},
            onResetSession = {}
        )
    }
}