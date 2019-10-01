package com.example.phoneear;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/*
Sources:
https://github.com/ptyagicodecamp/android-recipes/blob/develop/AudioRuntimePermissions/
https://github.com/bewantbe/audio-analyzer-for-android
https://stackoverflow.com/questions/1748977/making-textview-scrollable-on-android/13972249#13972249
https://stackoverflow.com/questions/3506696/auto-scrolling-textview-in-android-to-bring-text-into-view
 */

public class MainActivity extends AppCompatActivity {

    // for visualizing the measured amplitude of the different frequencies
    TextView frequenciesTextVisualization;
    // for informing about the current state of the app
    TextView currentState;
    // for displaying the coded and decoded message
    TextView decodedMessage;

    SamplingLoop samplingThread = null;
    private AnalyzerParameters analyzerParam = null;

    private boolean bSamplingPreparation = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        frequenciesTextVisualization = findViewById(R.id.audio_signal);
        currentState = findViewById(R.id.current_state);
        decodedMessage = findViewById(R.id.decode_message);
        decodedMessage.setMovementMethod(new ScrollingMovementMethod());

        Resources res = getResources();
        analyzerParam = new AnalyzerParameters(res);

        frequenciesTextVisualization.setText(
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
        currentState.setText("Info: Please start recording :)");
    }

    @Override
    protected void onResume() {
        super.onResume();

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
        else return ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        return false;
    }

    //Handling callback
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_RECORD_AUDIO: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // permission was granted, yay!
                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    Toast.makeText(this, "Permissions denied to record audio", Toast.LENGTH_LONG).show();
                }
                return;
            }
        }
    }
}