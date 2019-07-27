package com.example.phoneear;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import android.graphics.Color;

/*
Sources:
https://github.com/ptyagicodecamp/android-recipes/blob/develop/AudioRuntimePermissions/

 */

public class MainActivity extends AppCompatActivity {

    TextView textView;
    boolean isRecording = false;

    // for visualization of fft magnitude data
    private GraphView fft_graph;
    private LineGraphSeries<DataPoint> fftMagnitude;
    // for visualization of fft magnitude data
    private double[] frequency;
    private double[] magnitudeValuesTotal;
    private int windowSize = 128;
    private int fftIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textView = findViewById(R.id.audio_signal);
        // FFT Magnitude and its representation
        fft_graph = (GraphView) findViewById(R.id.graph);
        fft_graph.setTitle("FFT Magnitude");
        fft_graph.setBackgroundColor(Color.WHITE);
        fft_graph.setTitleColor(Color.BLACK);
        fft_graph.getViewport().setXAxisBoundsManual(true);
        fft_graph.getViewport().setYAxisBoundsManual(true);
        fft_graph.getViewport().setMinX(0.0);
        fft_graph.getViewport().setMaxX(160.0);
        fft_graph.getViewport().setMinY(0.0);
        fft_graph.getViewport().setMaxY(5000.0);
        fft_graph.getViewport().setScalable(false);
        fft_graph.getViewport().setScrollable(false);

        fftMagnitude = new LineGraphSeries<>();
        fftMagnitude.setColor(Color.LTGRAY);
        fft_graph.addSeries(fftMagnitude);

        magnitudeValuesTotal = new double[windowSize];

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public void startRecording(View view) {
        requestAudioPermissions();
    }

    private void recordAudio() {
        if (!isRecording) {
            try {
                isRecording = true;
                // start recording thread
                new RecordAudioInBackground().execute(isRecording);
            } catch (Exception e) {
                Log.e("Audio", "prepare() failed");
            }
        } else if (isRecording) {
            isRecording = false;
            // stop recording thread
        }
    }

    //Requesting run-time permissions

    //Create placeholder for user's consent to record_audio permission.
    //This will be used in handling callback
    private final int MY_PERMISSIONS_RECORD_AUDIO = 1;

    private void requestAudioPermissions() {
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

            //Go ahead with recording audio now
            recordAudio();
        }
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
                    //Go ahead with recording audio now
                    recordAudio();
                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    Toast.makeText(this, "Permissions Denied to record audio", Toast.LENGTH_LONG).show();
                }
                return;
            }
        }
    }

    private class RecordAudioInBackground extends AsyncTask<Boolean, Void, Void> {

        @Override
        protected Void doInBackground(Boolean... isRunning) {
            if (!isCancelled()) {
                Log.i("Audio", "Audio Async running");
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
                    while(isRecording)
                    {
                        //Log.i("Map", "Writing new data to buffer");
                        short[] buffer = buffers[ix++ % buffers.length];
                        N = recorder.read(buffer,0,buffer.length);
                        //Log.i("Map", "Writing new data to buffer: " + Arrays.toString(buffer));

                        while (fftIndex != windowSize) {
                            //Log.i("Map", "fftIndex: " + fftIndex);
                            //Log.i("Map", "windowSize: " + windowSize);
                            // restart
                            if (fftIndex > windowSize) {
                                magnitudeValuesTotal = new double[windowSize];
                                fftIndex = 0;
                            }
                            // fill with magnitude values
                            else if (fftIndex < windowSize) {
                                magnitudeValuesTotal[fftIndex] = buffer[fftIndex];
                                //Log.i("Map", "magnitudeValuesTotal: " + Arrays.toString(magnitudeValuesTotal));
                            }
                            ++fftIndex;
                        }
                        if (fftIndex == windowSize) {
                            // perform fft on magnitude values
                            new FFTAsync(windowSize).execute(magnitudeValuesTotal);
                            ++fftIndex;
                        }
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
            } else {
                Log.i("Audio", "Audio Async cancelled");
            }
            return null;
        }
    }

    // fft needs to run on a separate thread because it's quite resource intense
    private class FFTAsync extends AsyncTask<double[], Void, double[]>{
        private int size_Window;

        FFTAsync(int size){
            size_Window = size;
        }

        @Override
        protected double[] doInBackground(double[]... values){
            // clone, otherwise it's just a reference
            double[] rea = values[0].clone();
            double[] img = new double[size_Window];
            double[] magnitude = new  double[size_Window];

            // start the fft with window size
            FFT fft = new FFT(size_Window);
            fft.fft(rea, img);

            for(int i = 0; i < size_Window; i++){
                magnitude[i] = Math.sqrt(rea[i] * rea[i] + img[i] * img[i]);
            }

            return magnitude;
        }

        @Override
        protected void onPostExecute(double[] values){
            frequency = values;

            DataPoint[] fftPoints = new DataPoint[size_Window];
            for(int i = 0; i < size_Window; i++){
                fftPoints[i] = new DataPoint(i, frequency[i]);
            }
            fftMagnitude.resetData(fftPoints);
        }
    }
}