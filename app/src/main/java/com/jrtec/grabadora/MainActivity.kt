package com.jrtec.grabadora

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.jrtec.grabadora.Silero.Vad
import com.jrtec.grabadora.Silero.VadListener
import com.jrtec.grabadora.Silero.VadSilero
import com.jrtec.grabadora.Silero.config.FrameSize
import com.jrtec.grabadora.Silero.config.Mode
import com.jrtec.grabadora.Silero.config.SampleRate
import com.jrtec.grabadora.databinding.ActivityMainBinding
import com.jrtec.grabadora.VoiceRecorder.AudioCallback
import com.jrtec.grabadora.VoiceRecorder
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity(),
    AudioCallback{

    private val DEFAULT_SAMPLE_RATE = SampleRate.SAMPLE_RATE_48K
    private val DEFAULT_FRAME_SIZE = FrameSize.FRAME_SIZE_512
    private val DEFAULT_MODE = Mode.NORMAL
    private val DEFAULT_SILENCE_DURATION_MS = 50
    private val DEFAULT_SPEECH_DURATION_MS = 100

    private lateinit var recordingButton: Button
    private lateinit var speechTextView: TextView


    private lateinit var recorder: VoiceRecorder
    private lateinit var vad: VadSilero
    private var isRecording = false
    private lateinit var binding: ActivityMainBinding
    var player: MediaPlayer? = null
    var archivo: File? = null
    var status:String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastValue: String? = null
    private val onChangeTimeout: Long = 6000
    private lateinit var statusChangeDetector: StatusChangeDetector

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityMainBinding.inflate(layoutInflater)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        statusChangeDetector = StatusChangeDetector {
            executeFunction()
        }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                // Log.d( "BLUETOOTH HOME","Audio SCO state: $state")
                if (AudioManager.SCO_AUDIO_STATE_CONNECTED == state) {
//                    unregisterReceiver(this)
                    // Log.d("BLUETOOTH HOME", "Audion Connected")
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    am.mode= AudioManager.MODE_IN_COMMUNICATION
                    am.isBluetoothScoOn=true
                    // Log.i("BLUETOOTH HOME", " Model   ${am.mode}")
                 }
            }
        }, IntentFilter().apply{addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)})

        Log.d("BLUETOOTH", "starting bluetooth")
        am.startBluetoothSco()


        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                applicationContext, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                applicationContext, Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                applicationContext, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.INTERNET,
                ),
                1000
            )
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }
        registerReceiver(broadCastReceiverBluetooth, filter)

        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)

        vad = Vad.builder()
            .setContext(binding.root.context)
            .setSampleRate(DEFAULT_SAMPLE_RATE)
            .setFrameSize(DEFAULT_FRAME_SIZE)
            .setMode(DEFAULT_MODE)
            .setSilenceDurationMs(DEFAULT_SILENCE_DURATION_MS)
            .setSpeechDurationMs(DEFAULT_SPEECH_DURATION_MS)
            .build()

        recorder = VoiceRecorder(this)
        speechTextView = binding.textView
        recordingButton = binding.btnRecord
        recordingButton.setOnClickListener{
            if (!isRecording) {
                startRecording()
            } else {
                stopRecording()
            }
            }

        binding.btnPlay.setOnClickListener{
            startPlaying()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)


    private fun bleConnection(){
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var speakerDevice: AudioDeviceInfo? = null
        val devices: List<AudioDeviceInfo> = audioManager.availableCommunicationDevices
        audioManager.mode = AudioManager.MODE_IN_CALL
        for (device in devices) {
            Log.i("device ", device.toString())
            Log.i("device type", device.type.toString())
            if (device!!.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                speakerDevice = device
                break
            }
        }
        if (speakerDevice != null) {
            // Turn speakerphone ON.
            Log.i("speakerDevice ", speakerDevice.toString())
            val result = audioManager.setCommunicationDevice(speakerDevice)
            Log.i("result ", result.toString())
            if (!result) {
                // Handle error.
            }
            // Turn speakerphone OFF.
            //audioManager.clearCommunicationDevice()
        }
    }

//    private fun startRecording() {
//        Toast.makeText(applicationContext, "Recording", Toast.LENGTH_LONG).show()
//        try {
//            archivo = File.createTempFile("temporal", ".m4a", applicationContext.cacheDir)
//        }catch (e:IOException){
//
//            Log.e("error archive", "$e")
//        }
//        recorder = MediaRecorder().apply {
//            setAudioSource(MediaRecorder.AudioSource.MIC)
//            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
//            setOutputFile(archivo!!.absolutePath)
//            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
//            try {
//                prepare()
//                start()
//            }catch (e:IllegalStateException ) {
//                Log.e("error", "prepare() failed ${e.printStackTrace()}")
//            } catch (e: IOException) {
//                Log.e("error", "prepare() failed")
//            }
//        }
//    }
//
//    private fun stopRecording() {
//        recorder?.apply {
//            stop()
//            release()
//        }
//        recorder = null
//        Handler(Looper.getMainLooper()).post {
//            Toast.makeText(applicationContext, "Stop", Toast.LENGTH_SHORT).show()
//        }
//
//    }
    private fun startPlaying() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "Play the message", Toast.LENGTH_SHORT).show()
        }
        player = MediaPlayer()
        try {
//            Log.i("path playing",recorder!!.outputFile!!.absolutePath )
//            player!!.setDataSource(recorder!!.outputFile!!.absolutePath)
//            player!!.setAudioAttributes(
//                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
//                    .setUsage(AudioAttributes.USAGE_MEDIA)
//                    .build()
//            )
        } catch (e: IOException) {
        }
        try {
            player!!.prepare()
        } catch (e: IOException) {
        }
        player?.start()


    }
    private val broadCastReceiverBluetooth = object : BroadcastReceiver() {
        @SuppressLint("ResourceType")
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Handler(Looper.getMainLooper()).postDelayed(
                        {
                            am.startBluetoothSco()
                            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
//                            Log.i("BLUETOOTH HOME","************ AUDIO $state *************")
                        },2000)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
//                    Log.i("BLUETOOTH HOME","************ DISCONECTED *************")
                    am.mode= AudioManager.MODE_NORMAL
                    am.stopBluetoothSco()
//                    Log.i("BLUETOOTH HOME", " Model   ${am.mode}")

                }
            }
        }
    }


    override fun onAudio(audioData: ShortArray) {
        vad.setContinuousSpeechListener(audioData, object : VadListener {
            override fun onSpeechDetected() {
                statusChangeDetector.updateVariable("speech")
                this@MainActivity.runOnUiThread { speechTextView.setText(R.string.speech_detected)
                }
            }

            override fun onNoiseDetected() {
                statusChangeDetector.updateVariable("noise")
                this@MainActivity.runOnUiThread { speechTextView.setText(R.string.noise_detected) }
        }
        })
    }


    private fun startRecording() {
        isRecording = true
        recorder.start(vad.sampleRate.value, vad.frameSize.value)
        statusChangeDetector.startMonitoring()
        recordingButton.setBackgroundResource(R.drawable.ic_baseline_stop_circle_24)
    }

    private fun stopRecording() {
        isRecording = false
        recorder.stop()
        recordingButton.setBackgroundResource(R.drawable.ic_baseline_fiber_manual_record_24)
        speechTextView.text = "Start recording"
        statusChangeDetector.stopMonitoring()

    }



    private fun executeFunction() {
        // This function will be called if the variable hasn't changed its value in the specified timeout
        stopRecording()
        Log.i("STATUS", "Updating variable in MainActivity")

    }
}

