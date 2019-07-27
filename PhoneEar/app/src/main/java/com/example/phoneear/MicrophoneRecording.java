package com.example.phoneear;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Log;
import android.view.View;

import java.io.File;
import java.io.IOException;

/*
Source:
https://github.com/ptyagicodecamp/android-recipes/blob/develop/AudioRuntimePermissions/
 */

public class MicrophoneRecording extends Thread {

    private MediaRecorder mRecorder = null;
    private MediaPlayer mPlayer = null;

    File mAudioFile;

    boolean isRecording = false;

    private MicrophoneRecording()
    {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        start();
    }

//    @Override
//    public void run() {
//        try {
//            mAudioFile = createAudioFile(this, "demo");
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }

    private static File createAudioFile(Context context, String audioName) throws IOException {
        File storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS);
        File audio = File.createTempFile(
                audioName,  /* prefix */
                ".3gp",         /* suffix */
                storageDir      /* directory */
        );

        return audio;
    }

    private void recordAudio() {
        if (mRecorder == null) {
            mRecorder = new MediaRecorder();
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mRecorder.setOutputFile(mAudioFile.getAbsolutePath());
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
        }

        if (!isRecording) {
            try {
                mRecorder.prepare();
                mRecorder.start();
                isRecording = true;
            } catch (IOException e) {
                Log.e("Audio", "prepare() failed");
            }
        } else if (isRecording) {
            isRecording = false;
            stopRecording();
        }
    }

    public void stopRecording() {
        if (mRecorder != null) {
            mRecorder.stop();
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
        }
    }

    public void startPlaying(View view) {
        mPlayer = new MediaPlayer();
        try {
            mPlayer.setDataSource(mAudioFile.getAbsolutePath());
            mPlayer.prepare();
            mPlayer.start();
        } catch (IOException e) {
            Log.e("Audio", "prepare() failed");
        }
    }

    public void stopPlaying() {
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }

}
