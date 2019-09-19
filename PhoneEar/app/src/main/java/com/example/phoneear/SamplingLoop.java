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
    private volatile boolean messageStarted = false;
    private ShortTimeFT stft;   // use with care
    private final AnalyzerParameters analyzerParam;

    private double[] spectrumDBcopy;   // transfers data from SamplingLoop to text representation
    // initiate array that keeps score how often a frequency was the maximum value
    private int[] frequencyMaxAmount = new int[12];
    private int maxCounter;

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

    @Override
    public void run() {
        AudioRecord record;

        long tStart = SystemClock.uptimeMillis();
        long tEnd = SystemClock.uptimeMillis();
        long lastUpdate = SystemClock.uptimeMillis();

        if (! recordingIsPaused) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.currentState.setText("Message: Getting ready...");
                }
            });
        }

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
            numOfReadShort = record.read(audioSamples, 0, readChunkSize);   // pulling

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
                        activity.frequenciesTextVisualization.setText(
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
                        //activity.currentState.setText("Message: Waiting for begin of message...");
                        activity.currentState.setText("Message: " + Arrays.toString(frequencyMaxAmount));
                    }
                });

                stft.calculatePeak();
                activity.maxAmpFreq = stft.maxAmpFreq;
                activity.maxAmpDB = stft.maxAmpDB;

                // get RMS
                activity.dtRMS = stft.getRMS();
                activity.dtRMSFromFT = stft.getRMSFromFT();

                // update recent value list every 50ms
                if (SystemClock.uptimeMillis()-lastUpdate > 100) {
                    lastUpdate = SystemClock.uptimeMillis();
                    // average value from 15.8 kHz to 17 kHz
                    int average = (int) ((spectrumDBcopy[183]+spectrumDBcopy[186]+spectrumDBcopy[188]+spectrumDBcopy[190]+spectrumDBcopy[193]+spectrumDBcopy[195]+spectrumDBcopy[197])/7);
                    int[] valuesFrequency = new int[12];
                    valuesFrequency[0] = (int) spectrumDBcopy[207];
                    valuesFrequency[1] = (int) spectrumDBcopy[209];
                    valuesFrequency[2] = (int) spectrumDBcopy[211];
                    valuesFrequency[3] = (int) spectrumDBcopy[214];
                    valuesFrequency[4] = (int) spectrumDBcopy[216];
                    valuesFrequency[5] = (int) spectrumDBcopy[218];
                    valuesFrequency[6] = (int) spectrumDBcopy[221];
                    valuesFrequency[7] = (int) spectrumDBcopy[223];
                    valuesFrequency[8] = (int) spectrumDBcopy[225];
                    valuesFrequency[9] = (int) spectrumDBcopy[228];
                    valuesFrequency[10] = (int) spectrumDBcopy[230];
                    valuesFrequency[11] = (int) spectrumDBcopy[232];

                    int[] maxValueAndIndexCurrent = getMaxValueAndIndex(valuesFrequency);
                    if (maxValueAndIndexCurrent[0] > average * 1.3) {
                        frequencyMaxAmount[maxValueAndIndexCurrent[1]]++;
                    }
                    maxCounter++;

                    int[] maxValueAndIndexOverall = getMaxValueAndIndex(frequencyMaxAmount);
                    if (maxValueAndIndexOverall[0] >= 7) {
                        maxCounter = 0;
                        frequencyMaxAmount = new int[12];
                        String newFrequency = "";
                        switch (maxValueAndIndexOverall[1]) {
                            case 0:
                                if (! messageStarted) {
                                    messageStarted = true;
                                    newFrequency = "Start: ";
                                }
                                break;
                            case 1:
                                newFrequency = "18.0; ";
                                break;
                            case 2:
                                newFrequency = "18.2; ";
                                break;
                            case 3:
                                newFrequency = "18.4; ";
                                break;
                            case 4:
                                newFrequency = "18.6; ";
                                break;
                            case 5:
                                newFrequency = "18.8; ";
                                break;
                            case 6:
                                newFrequency = "19.0; ";
                                break;
                            case 7:
                                newFrequency = "19.2; ";
                                break;
                            case 8:
                                newFrequency = "19.4; ";
                                break;
                            case 9:
                                newFrequency = "19.6; ";
                                break;
                            case 10:
                                newFrequency = "19.8; ";
                                break;
                            case 11:
                                if (messageStarted) {
                                    appendToDecodedMessage("End");
                                    messageStarted = false;
                                }
                                break;
                            default:
                                break;
                        }
                        if (messageStarted) {
                            appendToDecodedMessage(newFrequency);
                        }
                    }

                    if (maxCounter == 10) { // reset maxCounter after 10 x 50 ms
                        maxCounter = 0;
                        frequencyMaxAmount = new int[12];
                    }
                }
            }
        }
        Log.i(TAG, "SamplingLoop::Run(): Actual sample rate: " + recorderMonitor.getSampleRate());
        Log.i(TAG, "SamplingLoop::Run(): Stopping and releasing recorder.");
        activity.runOnUiThread(new Runnable() {
           @Override
           public void run() {
               activity.frequenciesTextVisualization.setText(
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
               activity.currentState.setText("Message: Waiting for the start of the recording.");
               activity.decodedMessage.setText("");
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
        } else if (value > -20) {
            return new String(new char[14]).replace("\0", str);
        } else {
            return "";
        }
    }

    int[] getMaxValueAndIndex (int[] values) {
        int array[] = values;

        int max = array[0];
        int index = 0;

        for (int i = 0; i < array.length; i++) {
            if (max < array[i])
            {
                max = array[i];
                index = i;
            }
        }
        int[] result = {max, index};

        return result;
    }

    void appendToDecodedMessage(String newFrequency) {
        final String frequency = newFrequency;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.decodedMessage.append(frequency);
            }
        });
    }

    void finish() {
        isRunning = false;
        interrupt();
    }
}
