package com.example.phoneear;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

/*
Source:
https://stackoverflow.com/questions/6959930/android-need-to-record-mic-input
 */

/*
 * Thread to manage live recording/playback of voice input from the device's microphone.
 */
public class Audio extends Thread
{
    private boolean stopped = false;

    /**
     * Give the thread high priority so that it's not canceled unexpectedly, and start it
     */
    Audio()
    {
        //android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        start();
    }

    @Override
    public void run()
    {
        Log.i("Audio", "Running Audio Thread");
        AudioRecord recorder = null;
        short[][]   buffers  = new short[256][160];
        int ix = 0;

        /*
         * Initialize buffer to hold continuously recorded audio data, start recording, and start
         * playback.
         */
        try
        {
            int N = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, N*10);
            recorder.startRecording();

            /*
             * Loops until something outside of this thread stops it.
             * Reads the data from the recorder and writes it to the audio track for playback.
             */
            while(!stopped)
            {
                Log.i("Map", "Writing new data to buffer");
                short[] buffer = buffers[ix++ % buffers.length];
                N = recorder.read(buffer,0,buffer.length);
            }
        }
        catch(Throwable x)
        {
            Log.w("Audio", "Error reading voice audio", x);
        }

        /*
         * Frees the thread's resources after the loop completes so that it can be run again
         */
        finally
        {
            recorder.stop();
            recorder.release();
        }
    }

    /**
     * Called from outside of the thread in order to stop the recording/playback loop
     */
    public void close()
    {
        stopped = true;
    }

}