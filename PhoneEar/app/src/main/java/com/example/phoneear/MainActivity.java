package com.example.phoneear;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import android.graphics.Color;

/*
Sources:
https://github.com/ptyagicodecamp/android-recipes/blob/develop/AudioRuntimePermissions/

 */

public class MainActivity extends AppCompatActivity {

    TextView textView;
    boolean isRecording = false;

    SamplingLoop samplingThread = null;
    private AnalyzerParameters analyzerParam = null;

    double dtRMS = 0;
    double dtRMSFromFT = 0;
    double maxAmpDB;
    double maxAmpFreq;
    double[] viewRangeArray = null;
    protected long appStart = SystemClock.uptimeMillis();

    // This is the activity main thread Handler.
    private Handler updateUIHandler = null;

    // Message type code.
    private final static int MESSAGE_UPDATE_TEXT_CHILD_THREAD =1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textView = findViewById(R.id.audio_signal);

        Resources res = getResources();
        analyzerParam = new AnalyzerParameters(res);

        textView.setText(
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

    @Override
    protected void onResume() {
        super.onResume();

        /*
        analyzerViews.graphView.setReady(this);  // TODO: move this earlier?
        analyzerViews.enableSaveWavView(bSaveWav);
         */

        // Used to prevent extra calling to restartSampling() (e.g. in LoadPreferences())
        bSamplingPreparation = true;
    }

    @Override
    protected void onPause() {
        bSamplingPreparation = false;
        if (samplingThread != null) {
            samplingThread.finish();
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public void startRecording(View view) {
        // requestAudioPermissions();
        // Start sampling
        restartSampling(analyzerParam);
    }

    private boolean bSamplingPreparation = false;

    private void restartSampling(final AnalyzerParameters _analyzerParam) {
        // Stop previous sampler if any.
        if (samplingThread != null) {
            samplingThread.finish();
            try {
                samplingThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            samplingThread = null;
        }

        /*
        if (viewRangeArray != null) {
            analyzerViews.graphView.setupAxes(analyzerParam);
            double[] rangeDefault = analyzerViews.graphView.getViewPhysicalRange();
            Log.i(TAG, "restartSampling(): setViewRange: " + viewRangeArray[0] + " ~ " + viewRangeArray[1]);
            analyzerViews.graphView.setViewRange(viewRangeArray, rangeDefault);
            if (! isLockViewRange) viewRangeArray = null;  // do not conserve
        }
         */

        /*
        // Set the view for incoming data
        graphInit = new Thread(new Runnable() {
            public void run() {
                analyzerViews.setupView(_analyzerParam);
            }
        });
        graphInit.start();
         */

        // Check and request permissions
        if (! requestAudioPermissions()) {
            return;
        }

        if (! bSamplingPreparation) {
            return;
        }

        // Start sampling
        samplingThread = new SamplingLoop(this, _analyzerParam);
        samplingThread.start();
    }

    //Requesting run-time permissions

    //Create placeholder for user's consent to record_audio permission.
    //This will be used in handling callback
    private final int MY_PERMISSIONS_RECORD_AUDIO = 1;

    private boolean requestAudioPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            //When permission is not granted by user, show them message why this permission is needed.
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.RECORD_AUDIO)) {
                Toast.makeText(this, "Please grant permissions to record audio", Toast.LENGTH_LONG).show();

                //Give user option to still opt-in the permissions
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        MY_PERMISSIONS_RECORD_AUDIO);

            } else {
                // Show user dialog to grant permission to record audio
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        MY_PERMISSIONS_RECORD_AUDIO);
            }
        }
        //If permission is granted, then go ahead recording audio
        else if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return false;
    }

    //Handling callback
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_RECORD_AUDIO: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // permission was granted, yay!
                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    Toast.makeText(this, "Permissions Denied to record audio", Toast.LENGTH_LONG).show();
                }
                return;
            }
        }
    }
}