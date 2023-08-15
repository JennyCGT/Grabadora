package com.jrtec.grabadora

import android.annotation.SuppressLint
import android.media.*
import android.os.Process
import android.util.Log
import com.jrtec.grabadora.Silero.config.SampleRate
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil



class VoiceRecorder(val callback: AudioCallback) {

    private val TAG = VoiceRecorder::class.java.simpleName

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private var threadRecord: Thread? = null
    private var isListening = false

    private var sampleRate: Int = 0
    private var frameSize: Int = 0
    var outputFile: File? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var audioTrackIndex = -1
    private var isRecording = false
    private var minBufferSize: Int = 0
    private lateinit var audioData: ByteArray
    private lateinit var outputStream: FileOutputStream
    private val bufferLock = Object()


    fun start(sampleRate: Int, frameSize: Int) {
        this.sampleRate = sampleRate
        this.frameSize = frameSize
        stop()

        audioRecord = createAudioRecord()
        if (audioRecord != null) {
            isListening = true
            mediaCodec?.start()

            audioRecord?.startRecording()

            thread = Thread(ProcessVoice())
            thread?.start()
            threadRecord = Thread(RecordVoice())
            threadRecord?.start()
            isRecording = true

            Log.i("STATUS", "Start recording")

        }
    }

    fun stop() {
        isListening = false
        thread?.interrupt()
        thread = null
        threadRecord?.interrupt()
        threadRecord = null
        Log.i("STATUS", "STop recording")


        mediaCodec?.stop()
        mediaCodec?.release()
        mediaCodec = null

        mediaMuxer?.stop()
        mediaMuxer?.release()
        mediaMuxer = null
        // Stop and release the audio record properly
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null


    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        try {
            minBufferSize = maxOf(
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ),
                2 * frameSize
            )
            Log.i("DATA AUDIO", "DATA  SAMPLE RATE $sampleRate   BUFFER $minBufferSize")

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize
            )

            outputFile = File.createTempFile("audiorecordin1", ".m4a")
            Log.i("STATUS", "pathfile ${outputFile!!.absolutePath.toString()}")
            audioData = ByteArray(minBufferSize)
            outputStream = FileOutputStream(outputFile)

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

            val mediaFormat =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 256000)  //128000
            mediaFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
            mediaCodec?.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            mediaMuxer =
                MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            audioTrackIndex = mediaMuxer?.addTrack(mediaCodec?.outputFormat!!)!!



            if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                return audioRecord
            } else {
                audioRecord.release()
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Error can't create AudioRecord ", e)
        }
        return null
    }

    private inner class ProcessVoice : Runnable {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val size = frameSize

            while (!Thread.interrupted() && isListening) {
                val buffer = ShortArray(size)
                synchronized(bufferLock) {
                    audioRecord?.read(buffer, 0, buffer.size)
                }
//                val output =downsample(buffer, SampleRate.SAMPLE_RATE_16K.value.toFloat(), frameSize)
                callback.onAudio(buffer)
            }
        }
        private fun downsample(inputBuffer: ShortArray, sampleRateRatio: Float, frameSize:Int): ShortArray {
//            val outputSize = ceil(inputBuffer.size.toFloat() / SampleRate.SAMPLE_RATE_16K.value).toInt()

            val outputBuffer = ShortArray(frameSize)

            for (i in 0 until frameSize) {
                val pos = (i * sampleRateRatio).toInt()
                val alpha = i * sampleRateRatio - pos
                outputBuffer[i] = (inputBuffer[pos] * (1 - alpha) + inputBuffer[pos + 1] * alpha).toInt()
                    .toShort()
            }

            return outputBuffer
        }
    }

    private inner class RecordVoice : Runnable {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val size = frameSize
            val bufferInfo = MediaCodec.BufferInfo()
            val buffer = ByteArray(minBufferSize)
            var isMuxerStarted = false
            while (!Thread.interrupted() && isListening) {
                try {
                    var bytesRead: Int? = null
                    synchronized(bufferLock) {
                        bytesRead = audioRecord?.read(buffer, 0, minBufferSize)
                    }
                    if (bytesRead != null && bytesRead!! > 0) {
                        val inputBufferIndex = mediaCodec?.dequeueInputBuffer(-1) ?: -1
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                            inputBuffer?.clear()
                            inputBuffer?.put(buffer, 0, bytesRead!!)
                            mediaCodec?.queueInputBuffer(inputBufferIndex, 0, bytesRead!!, 0, 0)
                        }
                        val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                        if (outputBufferIndex >= 0) {
                            val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)
                            outputBuffer?.position(bufferInfo.offset)
                            outputBuffer?.limit(bufferInfo.offset + bufferInfo.size)
                            if (!isMuxerStarted) {
                                mediaMuxer?.start()
                                isMuxerStarted = true
                            }
                            mediaMuxer?.writeSampleData(audioTrackIndex, outputBuffer!!, bufferInfo)
                            mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }
                } catch (e: Exception) {
                    // Handle any exceptions that occur during audio processing

                    e.printStackTrace()
                }

            }
//            if (isMuxerStarted) {
//                mediaMuxer?.stop()
//                mediaMuxer?.release()
//                mediaMuxer = null
//            }

        }
    }


    interface AudioCallback {
        fun onAudio(audioData: ShortArray)
    }


}