/* Copyright 2014 Eddy Xiao <bewantbe@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.phoneear;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.audiofx.AutomaticGainControl;
import android.os.SystemClock;
import android.util.Log;
import android.widget.ToggleButton;

import java.util.Arrays;

// source: https://github.com/bewantbe/audio-analyzer-for-android

/**
 * Read a snapshot of audio data at a regular interval, and compute the FFT
 * @author suhler@google.com
 *         bewantbe@gmail.com
 * Ref:
 *   https://developer.android.com/guide/topics/media/mediarecorder.html#example
 *   https://developer.android.com/reference/android/media/audiofx/AutomaticGainControl.html
 */

class SamplingLoop extends Thread {
    private final String TAG = "SamplingLoop";
    private volatile boolean isRunning = true;
    private volatile boolean recordingIsPaused = false;
    private ShortTimeFT stft;   // use with care
    private final AnalyzerParameters analyzerParam;

    private double[] spectrumDBcopy;   // transfers data from SamplingLoop to AnalyzerGraphic

    private final MainActivity activity;

    SamplingLoop(MainActivity _activity, AnalyzerParameters _analyzerParam) {
        activity = _activity;
        analyzerParam = _analyzerParam;

        recordingIsPaused = ! ((ToggleButton) activity.findViewById(R.id.recordBtn)).isChecked();
    }

    private void SleepWithoutInterrupt(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private double baseTimeMs = SystemClock.uptimeMillis();

    private void LimitFrameRate(double updateMs) {
        // Limit the frame rate by wait `delay' ms.
        baseTimeMs += updateMs;
        long delay = (int) (baseTimeMs - SystemClock.uptimeMillis());
//      Log.i(TAG, "delay = " + delay);
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Log.i(TAG, "Sleep interrupted");  // seems never reached
            }
        } else {
            baseTimeMs -= delay;  // get current time
            // Log.i(TAG, "time: cmp t="+Long.toString(SystemClock.uptimeMillis())
            //            + " v.s. t'=" + Long.toString(baseTimeMs));
        }
    }

    private double[] mdata;

    // Generate test data.
    private int readTestData(short[] a, int offsetInShorts, int sizeInShorts, int id) {
        if (mdata == null || mdata.length != sizeInShorts) {
            mdata = new double[sizeInShorts];
        }
        Arrays.fill(mdata, 0.0);
        switch (id - 1000) {
            case 1:
                break;
            case 0:
                break;
            case 2:
                for (int i = 0; i < sizeInShorts; i++) {
                    a[i] = (short) (analyzerParam.SAMPLE_VALUE_MAX * (2.0*Math.random() - 1));
                }
                break;
            default:
                Log.w(TAG, "readTestData(): No this source id = " + analyzerParam.audioSourceId);
        }
        // Block this thread, so that behave as if read from real device.
        LimitFrameRate(1000.0*sizeInShorts / analyzerParam.sampleRate);
        return sizeInShorts;
    }

    @Override
    public void run() {
        AudioRecord record;

        long tStart = SystemClock.uptimeMillis();
        long tEnd = SystemClock.uptimeMillis();
        if (tEnd - tStart < 500) {
            Log.i(TAG, "wait more.." + (500 - (tEnd - tStart)) + " ms");
            // Wait until previous instance of AudioRecord fully released.
            SleepWithoutInterrupt(500 - (tEnd - tStart));
        }

        int minBytes = AudioRecord.getMinBufferSize(analyzerParam.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "SamplingLoop::run(): Invalid AudioRecord parameter.\n");
            return;
        }

        /*
          Develop -> Reference -> AudioRecord
             Data should be read from the audio hardware in chunks of sizes
             inferior to the total recording buffer size.
         */
        // Determine size of buffers for AudioRecord and AudioRecord::read()
        int readChunkSize    = analyzerParam.hopLen;  // Every hopLen one fft result (overlapped analyze window)
        readChunkSize        = Math.min(readChunkSize, 2048);  // read in a smaller chunk, hopefully smaller delay
        int bufferSampleSize = Math.max(minBytes / analyzerParam.BYTE_OF_SAMPLE, analyzerParam.fftLen/2) * 2;
        // tolerate up to about 1 sec.
        bufferSampleSize = (int)Math.ceil(1.0 * analyzerParam.sampleRate / bufferSampleSize) * bufferSampleSize;

        // Use the mic with AGC turned off. e.g. VOICE_RECOGNITION for measurement
        // The buffer size here seems not relate to the delay.
        // So choose a larger size (~1sec) so that overrun is unlikely.
        try {
            if (analyzerParam.audioSourceId < 1000) {
                record = new AudioRecord(analyzerParam.audioSourceId, analyzerParam.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, analyzerParam.BYTE_OF_SAMPLE * bufferSampleSize);
            } else {
                record = new AudioRecord(analyzerParam.RECORDER_AGC_OFF, analyzerParam.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, analyzerParam.BYTE_OF_SAMPLE * bufferSampleSize);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Fail to initialize recorder.");
            return;
        }

        // Check Auto-Gain-Control status.
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl agc = AutomaticGainControl.create(
                    record.getAudioSessionId());
            if (agc.getEnabled())
                Log.i(TAG, "SamplingLoop::Run(): AGC: enabled.");
            else
                Log.i(TAG, "SamplingLoop::Run(): AGC: disabled.");
        } else {
            Log.i(TAG, "SamplingLoop::Run(): AGC: not available.");
        }

        Log.i(TAG, "SamplingLoop::Run(): Starting recorder... \n" +
                "  source          : " + analyzerParam.getAudioSourceName() + "\n" +
                String.format("  sample rate     : %d Hz (request %d Hz)\n", record.getSampleRate(), analyzerParam.sampleRate) +
                String.format("  min buffer size : %d samples, %d Bytes\n", minBytes / analyzerParam.BYTE_OF_SAMPLE, minBytes) +
                String.format("  buffer size     : %d samples, %d Bytes\n", bufferSampleSize, analyzerParam.BYTE_OF_SAMPLE*bufferSampleSize) +
                String.format("  read chunk size : %d samples, %d Bytes\n", readChunkSize, analyzerParam.BYTE_OF_SAMPLE*readChunkSize) +
                String.format("  FFT length      : %d\n", analyzerParam.fftLen) +
                String.format("  nFFTAverage     : %d\n", analyzerParam.nFFTAverage));
        analyzerParam.sampleRate = record.getSampleRate();

        if (record.getState() == AudioRecord.STATE_UNINITIALIZED) {
            Log.e(TAG, "SamplingLoop::run(): Fail to initialize AudioRecord()");
            return;
        }

        short[] audioSamples = new short[readChunkSize];
        int numOfReadShort;

        stft = new ShortTimeFT(analyzerParam);
        stft.setAWeighting(analyzerParam.isAWeighting);
        if (spectrumDBcopy == null || spectrumDBcopy.length != analyzerParam.fftLen/2+1) {
            spectrumDBcopy = new double[analyzerParam.fftLen/2+1];
        }

        RecorderMonitor recorderMonitor = new RecorderMonitor(analyzerParam.sampleRate, bufferSampleSize, "SamplingLoop::run()");
        recorderMonitor.start();

        // Start recording
        try {
            record.startRecording();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Fail to start recording.");
            return;
        }

        // Main loop
        // When running in this loop (including when paused), you can not change properties
        // related to recorder: e.g. audioSourceId, sampleRate, bufferSampleSize
        while (isRunning) {
            // Read data
            if (analyzerParam.audioSourceId >= 1000) {
                numOfReadShort = readTestData(audioSamples, 0, readChunkSize, analyzerParam.audioSourceId);
            } else {
                numOfReadShort = record.read(audioSamples, 0, readChunkSize);   // pulling
            }

            if (recordingIsPaused) {
//          fpsCounter.inc();
                // keep reading data, for overrun checker and for write wav data
                continue;
            }

            stft.feedData(audioSamples, numOfReadShort);

            // If there is new spectrum data, do plot
            if (stft.nElemSpectrumAmp() >= analyzerParam.nFFTAverage) {
                // Update spectrum or spectrogram
                final double[] spectrumDB = stft.getSpectrumAmpDB();
                System.arraycopy(spectrumDB, 0, spectrumDBcopy, 0, spectrumDB.length);

                // source: https://www.quora.com/How-do-I-update-the-UI-from-a-background-thread-in-Android
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        activity.textView.setText(
                            "17.800 Hz : " + convertValuesIntoSigns(spectrumDBcopy[207]) + "\n" +
                            "18.000 Hz : " + convertValuesIntoSigns(spectrumDBcopy[209]) + "\n" +
                            "18.200 Hz : " + convertValuesIntoSigns(spectrumDBcopy[211]) + "\n" +
                            "18.400 Hz : " + convertValuesIntoSigns(spectrumDBcopy[214]) + "\n" +
                            "18.600 Hz : " + convertValuesIntoSigns(spectrumDBcopy[216]) + "\n" +
                            "18.800 Hz : " + convertValuesIntoSigns(spectrumDBcopy[218]) + "\n" +
                            "19.000 Hz : " + convertValuesIntoSigns(spectrumDBcopy[221]) + "\n" +
                            "19.200 Hz : " + convertValuesIntoSigns(spectrumDBcopy[223]) + "\n" +
                            "19.400 Hz : " + convertValuesIntoSigns(spectrumDBcopy[225]) + "\n" +
                            "19.600 Hz : " + convertValuesIntoSigns(spectrumDBcopy[228]) + "\n" +
                            "19.800 Hz : " + convertValuesIntoSigns(spectrumDBcopy[230]) + "\n" +
                            "20.000 Hz : " + convertValuesIntoSigns(spectrumDBcopy[232])
                        );
                    }
                });

                stft.calculatePeak();
                activity.maxAmpFreq = stft.maxAmpFreq;
                activity.maxAmpDB = stft.maxAmpDB;

                // get RMS
                activity.dtRMS = stft.getRMS();
                activity.dtRMSFromFT = stft.getRMSFromFT();
            }
        }
        Log.i(TAG, "SamplingLoop::Run(): Actual sample rate: " + recorderMonitor.getSampleRate());
        Log.i(TAG, "SamplingLoop::Run(): Stopping and releasing recorder.");
        activity.runOnUiThread(new Runnable() {
           @Override
           public void run() {
               activity.textView.setText(
                       "17.800 Hz :\n" +
                               "18.000 Hz :\n" +
                               "18.200 Hz :\n" +
                               "18.400 Hz :\n" +
                               "18.600 Hz :\n" +
                               "18.800 Hz :\n" +
                               "19.000 Hz :\n" +
                               "19.200 Hz :\n" +
                               "19.400 Hz :\n" +
                               "19.600 Hz :\n" +
                               "19.800 Hz :\n" +
                               "20.000 Hz :"
               );
           }
        });
        record.stop();
        record.release();
    }

    void setAWeighting(boolean isAWeighting) {
        if (stft != null) {
            stft.setAWeighting(isAWeighting);
        }
    }

    void setPause(boolean pause) {
        this.recordingIsPaused = pause;
    }

    boolean getPause() {
        return this.recordingIsPaused;
    }

    // with help of: https://stackoverflow.com/questions/1235179/simple-way-to-repeat-a-string-in-java
    String convertValuesIntoSigns (double value) {
        String str = "|||||";
        if (value < -90) {
            return str;
        } else if (value < -85) {
            return new String(new char[2]).replace("\0", str);
        } else if (value < -80) {
            return new String(new char[3]).replace("\0", str);
        } else if (value < -75) {
            return new String(new char[4]).replace("\0", str);
        } else if (value < -70) {
            return new String(new char[5]).replace("\0", str);
        } else if (value < -65) {
            return new String(new char[6]).replace("\0", str);
        } else if (value < -60) {
            return new String(new char[7]).replace("\0", str);
        } else if (value < -55) {
            return new String(new char[8]).replace("\0", str);
        } else if (value < -50) {
            return new String(new char[9]).replace("\0", str);
        } else if (value < -45) {
            return new String(new char[10]).replace("\0", str);
        } else if (value < -40) {
            return new String(new char[11]).replace("\0", str);
        } else if (value < -35) {
            return new String(new char[12]).replace("\0", str);
        } else if (value < -30) {
            return new String(new char[13]).replace("\0", str);
        } else if (value < -25) {
            return new String(new char[14]).replace("\0", str);
        } else if (value < -20) {
            return new String(new char[15]).replace("\0", str);
        } else {
            return "";
        }
    }

    void finish() {
        isRunning = false;
        interrupt();
    }
}
