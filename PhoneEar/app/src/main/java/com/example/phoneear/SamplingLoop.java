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

/*
sources:
(main source) https://github.com/bewantbe/audio-analyzer-for-android
we built on top of that and added multiple functions for the functionality we needed

https://www.quora.com/How-do-I-update-the-UI-from-a-background-thread-in-Android
https://stackoverflow.com/questions/1235179/simple-way-to-repeat-a-string-in-java
 */

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
    private volatile boolean recordingIsPaused;
    private volatile boolean messageStarted = false;
    private volatile boolean waitForNextRound = false;
    private ShortTimeFT stft;
    private final AnalyzerParameters analyzerParam;

    private double[] spectrumDBcopy;   // transfers data from SamplingLoop to text representation
    // initiate array that keeps score how often a frequency was the maximum value
    private int[] frequencyMaxAmount = new int[13];
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

    @Override
    public void run() {
        AudioRecord record;

        long lastUpdate = SystemClock.uptimeMillis();

        if (! recordingIsPaused) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                activity.currentState.setText("Info: Getting ready...");
                activity.decodedMessage.setText("");
                }
            });
        }

        Log.i(TAG, "wait more.." + 500 + " ms");
        // Wait until previous instance of AudioRecord fully released.
        SleepWithoutInterrupt(500);

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
                // keep reading data for overrun checker
                continue;
            }

            stft.feedData(audioSamples, numOfReadShort);

            // If there is new spectrum data, do plot
            if (stft.nElemSpectrumAmp() >= analyzerParam.nFFTAverage) {
                // Update spectrum or spectrogram
                final double[] spectrumDB = stft.getSpectrumAmpDB();
                System.arraycopy(spectrumDB, 0, spectrumDBcopy, 0, spectrumDB.length);

                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        activity.frequenciesTextVisualization.setText(
                            "Phase       : " + convertValuesIntoSigns(spectrumDBcopy[204]) + "\n" +
                            "17.8 kHz ([): " + convertValuesIntoSigns(spectrumDBcopy[207]) + "\n" +
                            "18.0 kHz (0): " + convertValuesIntoSigns(spectrumDBcopy[209]) + "\n" +
                            "18.2 kHz (1): " + convertValuesIntoSigns(spectrumDBcopy[211]) + "\n" +
                            "18.4 kHz (2): " + convertValuesIntoSigns(spectrumDBcopy[214]) + "\n" +
                            "18.6 kHz (3): " + convertValuesIntoSigns(spectrumDBcopy[216]) + "\n" +
                            "18.8 kHz (4): " + convertValuesIntoSigns(spectrumDBcopy[218]) + "\n" +
                            "19.0 kHz (5): " + convertValuesIntoSigns(spectrumDBcopy[221]) + "\n" +
                            "19.2 kHz (6): " + convertValuesIntoSigns(spectrumDBcopy[223]) + "\n" +
                            "19.4 kHz (7): " + convertValuesIntoSigns(spectrumDBcopy[225]) + "\n" +
                            "19.6 kHz (8): " + convertValuesIntoSigns(spectrumDBcopy[228]) + "\n" +
                            "19.8 kHz (9): " + convertValuesIntoSigns(spectrumDBcopy[230]) + "\n" +
                            "20.0 kHz (]): " + convertValuesIntoSigns(spectrumDBcopy[232])
                        );
                        if (messageStarted) {
                            activity.currentState.setText("Info: Receiving message...");
                            //activity.currentState.setText("Message: \n" + Arrays.toString(frequencyMaxAmount));
                        } else {
                            activity.currentState.setText("Info: Waiting for message...");
                        }
                    }
                });

                // update recent value list every 50ms
                if (SystemClock.uptimeMillis()-lastUpdate > 50) {
                    //Log.i(TAG, "SamplingLoop::Run(): measurement " + maxCounter);
                    lastUpdate = SystemClock.uptimeMillis();
                    // average value from 15.8 kHz to 16.8 kHz
                    int averageComparison = (int) ((spectrumDBcopy[183]+spectrumDBcopy[186]+spectrumDBcopy[188]+spectrumDBcopy[190]+spectrumDBcopy[193]+spectrumDBcopy[195])/6);
                    //int averageMain = (int) ((spectrumDBcopy[204]+spectrumDBcopy[207]+spectrumDBcopy[209]+spectrumDBcopy[211]+spectrumDBcopy[214]+spectrumDBcopy[216]+spectrumDBcopy[218]+spectrumDBcopy[221]+spectrumDBcopy[223]+spectrumDBcopy[225]+spectrumDBcopy[228]+spectrumDBcopy[230]+spectrumDBcopy[232])/13);
                    int[] valuesFrequency = new int[13];
                    // assign values for 17 kHz (phase)  and target frequencies 17.8 kHz to 20 kHz
                    valuesFrequency[0] = (int) spectrumDBcopy[197];
                    valuesFrequency[1] = (int) spectrumDBcopy[207];
                    valuesFrequency[2] = (int) spectrumDBcopy[209];
                    valuesFrequency[3] = (int) spectrumDBcopy[211];
                    valuesFrequency[4] = (int) spectrumDBcopy[214];
                    valuesFrequency[5] = (int) spectrumDBcopy[216];
                    valuesFrequency[6] = (int) spectrumDBcopy[218];
                    valuesFrequency[7] = (int) spectrumDBcopy[221];
                    valuesFrequency[8] = (int) spectrumDBcopy[223];
                    valuesFrequency[9] = (int) spectrumDBcopy[225];
                    valuesFrequency[10] = (int) spectrumDBcopy[228];
                    valuesFrequency[11] = (int) spectrumDBcopy[230];
                    valuesFrequency[12] = (int) spectrumDBcopy[232];

                    boolean phaseSignal = false;

                    // get the maximum value of phase and target frequencies and the index in the array
                    int[] maxValueAndIndexCurrent = getMaxValueAndIndex(valuesFrequency);

                    // if the maximum value is higher than the average of the comparison frequencies * 0.9
                    if (maxValueAndIndexCurrent[0] > (int) (averageComparison * 0.9)) {
                        // increase the counter in the amount-of-maxima array
                        frequencyMaxAmount[maxValueAndIndexCurrent[1]]++;

                        // if current maximum is phase frequency
                        if (maxValueAndIndexCurrent[1] == 0) {
                            // set phaseSignal to true
                            phaseSignal = true;
                            //Log.i(TAG, "SamplingLoop::Run(): maxValue: " + maxValueAndIndexCurrent[0]);
                            //Log.i(TAG, "SamplingLoop::Run(): averageComparison * 0.9: " + (int) (averageComparison * 0.9));
                            //Log.i(TAG, "SamplingLoop::Run(): averageMain: " + averageMain * 0.7);
                            // && maxValueAndIndexCurrent[0] > averageMain * 0.7
                        }
                    }

                    maxCounter++;

                    // get the amount of maxima of the phase or target frequency with the most maxima and the index in the array
                    int[] maxValueAndIndexOverall = getMaxValueAndIndex(frequencyMaxAmount);

                    if (messageStarted) {
                        //Log.i(TAG, "SamplingLoop::Run(): maxValueAndIndexOverall: (" + maxValueAndIndexOverall[0] + "," + maxValueAndIndexOverall[1] +")");
                    }

                    // if the amount of maxima is equal to a certain threshold or the phaseSignal
                    if ((maxValueAndIndexOverall[0] == 4 && !waitForNextRound) || phaseSignal) {

                        String newFrequency = "";

                        if (phaseSignal) {
                            newFrequency = "";
                        } else {
                            waitForNextRound = true;
                            switch (maxValueAndIndexOverall[1]) {
                                case 1:
                                    if (! messageStarted) {
                                        messageStarted = true;
                                        newFrequency = "[";
                                    }
                                    break;
                                case 2:
                                    //newFrequency = "18.0 ";
                                    newFrequency = "0";
                                    break;
                                case 3:
                                    //newFrequency = "18.2 ";
                                    newFrequency = "1";
                                    break;
                                case 4:
                                    //newFrequency = "18.4 ";
                                    newFrequency = "2";
                                    break;
                                case 5:
                                    //newFrequency = "18.6 ";
                                    newFrequency = "3";
                                    break;
                                case 6:
                                    //newFrequency = "18.8 ";
                                    newFrequency = "4";
                                    break;
                                case 7:
                                    //newFrequency = "19.0 ";
                                    newFrequency = "5";
                                    break;
                                case 8:
                                    //newFrequency = "19.2 ";
                                    newFrequency = "6";
                                    break;
                                case 9:
                                    //newFrequency = "19.4 ";
                                    newFrequency = "7";
                                    break;
                                case 10:
                                    //newFrequency = "19.6 ";
                                    newFrequency = "8";
                                    break;
                                case 11:
                                    //newFrequency = "19.8 ";
                                    newFrequency = "9";
                                    break;
                                case 12:
                                    if (messageStarted) {
                                        decodeMessage();
                                        messageStarted = false;
                                    }
                                    break;
                                default:
                                    break;
                            }
                        }

                        if (messageStarted || phaseSignal) {
                            appendToDecodedMessage(newFrequency);
                        }
                    }

                    if (maxCounter == 10 || phaseSignal) { // reset maxCounter after 10 x 50 ms or when phase signal is detected
                        if (messageStarted) {
                            //Log.i(TAG, "SamplingLoop::Run(): phaseSignal: " + phaseSignal);
                            //Log.i(TAG, "SamplingLoop::Run(): maxCounter: " + maxCounter);
                            //Log.i(TAG, "SamplingLoop::Run(): maxValueAndIndexOverall[0]: " + maxValueAndIndexOverall[0]);
                            //Log.i(TAG, "---------------------------------------------");
                        }
                        // maxCounter >= 3, because signal needs to have a certain length and is not supposed to be a phase signal (max. length 2)
                        if (messageStarted && maxCounter >= 3 && maxValueAndIndexOverall[0] < 4 && maxValueAndIndexOverall[1] != 1) {
                            appendToDecodedMessage("_");
                        }
                        maxCounter = 0;
                        waitForNextRound = false;
                        frequencyMaxAmount = new int[13];
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
                       "Phase       :\n" +
                       "17.8 kHz ([):\n" +
                       "18.0 kHz (0):\n" +
                       "18.2 kHz (1):\n" +
                       "18.4 kHz (2):\n" +
                       "18.6 kHz (3):\n" +
                       "18.8 kHz (4):\n" +
                       "19.0 kHz (5):\n" +
                       "19.2 kHz (6):\n" +
                       "19.4 kHz (7):\n" +
                       "19.6 kHz (8):\n" +
                       "19.8 kHz (9):\n" +
                       "20.0 kHz (]):"
               );
               activity.currentState.setText("Info: Please start recording :)");
           }
        });
        record.stop();
        record.release();
    }

    private String convertValuesIntoSigns (double value) {
        String str = "|";
        if (value < -100) {
            return str;
        } else if (value < -95) {
            return new String(new char[1]).replace("\0", str);
        } else if (value < -90) {
            return new String(new char[2]).replace("\0", str);
        } else if (value < -85) {
            return new String(new char[3]).replace("\0", str);
        } else if (value < -80) {
            return new String(new char[4]).replace("\0", str);
        } else if (value < -75) {
            return new String(new char[5]).replace("\0", str);
        } else if (value < -70) {
            return new String(new char[6]).replace("\0", str);
        } else if (value < -65) {
            return new String(new char[7]).replace("\0", str);
        } else if (value < -60) {
            return new String(new char[8]).replace("\0", str);
        } else if (value < -55) {
            return new String(new char[9]).replace("\0", str);
        } else if (value < -50) {
            return new String(new char[10]).replace("\0", str);
        } else if (value < -45) {
            return new String(new char[11]).replace("\0", str);
        } else if (value < -40) {
            return new String(new char[12]).replace("\0", str);
        } else if (value < -35) {
            return new String(new char[13]).replace("\0", str);
        } else if (value < -30) {
            return new String(new char[14]).replace("\0", str);
        } else if (value > -20) {
            return new String(new char[15]).replace("\0", str);
        } else {
            return "";
        }
    }

    private int[] getMaxValueAndIndex (int[] values) {
        int[] array = values;

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

    private void appendToDecodedMessage(String newFrequency) {
        final String frequency = newFrequency;
        Log.i(TAG, "SamplingLoop::Run(): added: " + frequency);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.decodedMessage.append(frequency);
            }
        });
    }

    private void decodeMessage() {
        String codedMessage = activity.decodedMessage.getText().toString();

        int startMessage = codedMessage.lastIndexOf("[");
        int endMessage = codedMessage.length();

        String message = codedMessage.substring(startMessage+1, endMessage);
        int messageLength = message.length();

        if (messageLength % 2 == 1) { // in case the length of the message is not even, add a "-" at the end, to provide an even message length
            messageLength += 1;
            message = message.concat("_");
        }

        StringBuffer decodedMessage = new StringBuffer(20);
        for (int i = 0; i < messageLength/2; i++) {
            String encodedCharacter = message.substring(2*i, 2*i+2);
            Log.i(TAG, "SamplingLoop::decodeMessage(): encodedCharacter: " + encodedCharacter);
            if (encodedCharacter.contains("_")) {
                encodedCharacter = "_";
            } else {
                switch (Integer.parseInt(encodedCharacter)) {
                    case 65:
                        encodedCharacter = "A";
                        break;
                    case 66:
                        encodedCharacter = "B";
                        break;
                    case 67:
                        encodedCharacter = "C";
                        break;
                    case 68:
                        encodedCharacter = "D";
                        break;
                    case 69:
                        encodedCharacter = "E";
                        break;
                    case 70:
                        encodedCharacter = "F";
                        break;
                    case 71:
                        encodedCharacter = "G";
                        break;
                    case 72:
                        encodedCharacter = "H";
                        break;
                    case 73:
                        encodedCharacter = "I";
                        break;
                    case 74:
                        encodedCharacter = "J";
                        break;
                    case 75:
                        encodedCharacter = "K";
                        break;
                    case 76:
                        encodedCharacter = "L";
                        break;
                    case 77:
                        encodedCharacter = "M";
                        break;
                    case 78:
                        encodedCharacter = "N";
                        break;
                    case 79:
                        encodedCharacter = "O";
                        break;
                    case 80:
                        encodedCharacter = "P";
                        break;
                    case 81:
                        encodedCharacter = "Q";
                        break;
                    case 82:
                        encodedCharacter = "R";
                        break;
                    case 83:
                        encodedCharacter = "S";
                        break;
                    case 84:
                        encodedCharacter = "T";
                        break;
                    case 85:
                        encodedCharacter = "U";
                        break;
                    case 86:
                        encodedCharacter = "V";
                        break;
                    case 87:
                        encodedCharacter = "W";
                        break;
                    case 88:
                        encodedCharacter = "X";
                        break;
                    case 89:
                        encodedCharacter = "Y";
                        break;
                    case 90:
                        encodedCharacter = "Z";
                        break;
                    default:
                        encodedCharacter = "_";
                        break;
                }
            }
            decodedMessage.append(encodedCharacter);
        }

        final String decodedMessageFinal = decodedMessage.toString();
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.decodedMessage.append("] = " + decodedMessageFinal +"\n");
            }
        });
    }

    void finish() {
        isRunning = false;
        interrupt();
    }
}
