package app.jroidk.com.videorecorder;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
//import android.media.MediaRecorder;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import org.bytedeco.javacv.FFmpegFrameRecorder;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ShortBuffer;

//import static org.bytedeco.javacpp.opencv_core.IPL_DEPTH_8U;

/*
 * According to the MediaRecorder StateMachine
 * the RecordService is only responsible for
 * 1. move prepared state to recording state by calling MediaRecorder.start() -- starts the thread
 * 2. move recording state to Initial state by calling MediaRecorder.stop() -- stops the thread
 * ALL other states should be maintained by MainActivity
 * */
public class RecorderService extends Service  {

    protected enum RecorderStatus{
        INITIAL, INITIALIZED, CONFIGURED,PREPARED, RECORDING, RELEASED
    }

    private final static int BUF_SIZE = 8192;

    protected static FFmpegFrameRecorder frecorder;
    //private MediaRecorder recorder = null;
    private static Thread recordingThread = null;
    private static InputStream in = null;
    private static FileOutputStream out = null;
    private static ParcelFileDescriptor[] pipe = null;
    private static RecorderStatus mStatus = RecorderStatus.INITIAL;

    //this value is for main thread to turn on/off the recording thread
    private static NotificationManager mNotificationManager;
    private static Notification notification ;
    private static final int HELLO_ID = 1;

    private final static String TAG = "jroidk/RecordService";
    long[] timestamps;
    ShortBuffer[] samples;
   // int imagesIndex, samplesIndex;
    long startTime = 0;
    private static boolean recording = false;

    //private IplImage yuvIplimage = null;

    /* audio data getting thread */
    protected static AudioRecord audioRecord;
    private AudioRecordRunnable audioRecordRunnable;
    private Thread audioThread;
  //  private Thread frecorderInitThread;
    volatile boolean runAudioThread = false;
    private static CameraView mCameraView;

    @Override
    public void onCreate() {
        //NOTE:!! when creating the service
        //we assume MediaRecorder recorder is created in MainActivity
        //
        Log.d(TAG, "Service onCreate "+SystemClock.currentThreadTimeMillis() );
        mCameraView = CameraView.getCVinstance(getApplicationContext());
        initRecorder();
        super.onCreate();

    }



    // Stop recording and remove SurfaceView
    @Override
    public void onDestroy() {
        Log.d(TAG, "RecordService onDestroy " );
       // stopNot();
        Log.d(TAG, "recordingthreas set to null");
        try {
            audioThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        recordingThread =  null;
        super.onDestroy();

    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Toast.makeText(this, "Start Recording", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "onStartCommand.");
        recordingThread = new Thread(new Runnable() {
            @Override
            public void run() {
  //              initRecorder();

                startFFRecording();
               //startRecording(recorder);

                while(MainActivity.isRecordingFromActivity == true)
                {
                    Log.d(TAG, "Thread running " );
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    //as long as the isRecording = true; trap here
                }


                Log.d(TAG, "!!!!!!!!!!!!!!recoding Thread STopped" );
                stopFFRecording();
                //stopRecording(recorder);//this will set status to INITIAL and allow MainActivity to release th recorder
                stopSelf();

            }
        },"jroidk/videorecorder Thread");

        try
        {
            recordingThread.start();
          //  startNot();

        }
        catch(IllegalThreadStateException e)
        {
            Log.d(TAG, "IllegalStateException start thread: " + e.getMessage());
        }
        //return super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }


    private void startNot(){
        // Start foreground service to avoid unexpected kill
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        int icon = R.drawable.ic_launcher;
        CharSequence tickerText = getString(R.string.tmp);
        long when = System.currentTimeMillis();

        notification = new Notification(icon, tickerText, when);
        notification.flags = Notification.FLAG_ONGOING_EVENT;

        Context context = getApplicationContext();
        CharSequence contentTitle = getString(R.string.tmp);
        CharSequence contentText = getString(R.string.tmp);

        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        notification.setLatestEventInfo(context, contentTitle, contentText, contentIntent);

        mNotificationManager.notify(HELLO_ID, notification);

        // startForeground(1234, notification);
    }

    private void stopNot(){
        if(mNotificationManager != null) mNotificationManager.cancel(HELLO_ID);
    }

    public void startFFRecording()  {
     //   initRecorder();
        Log.d(TAG, "wait till  frecorder init thread is finished "+ SystemClock.currentThreadTimeMillis());
        try {
            if(MainActivity.frecorderInitThread!=null)  MainActivity.frecorderInitThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        Log.d(TAG, "frecorder init Thread is done "+ SystemClock.currentThreadTimeMillis());
        try {
            frecorder.start();
            Log.d(TAG , "!!!!!!!!!!NOW START!!!!!!");
            startTime = System.currentTimeMillis();
            setRecordingStatus(true);

            audioThread.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initRecorder() {


                frecorder = new FFmpegFrameRecorder(CONSTANTS.ffmpeg_link, MainActivity.getPreviewWidth(), MainActivity.getPreviewHeight(), 1);
                Log.d(TAG, "frecorder newed at new frecorderInitThread "+" with H: "+MainActivity.getPreviewWidth()+" with W: "+ MainActivity.getPreviewHeight());
                if(frecorder == null)
                {
                    Toast.makeText(getApplicationContext(), "NULL MediaRecorder", Toast.LENGTH_SHORT).show();
                    java.lang.System.exit(1);
                }

                Log.i(TAG, "ffmpeg_url: " + CONSTANTS.ffmpeg_link);
                frecorder.setFormat("mp4");
                frecorder.setSampleRate(CONSTANTS.sampleAudioRateInHz);
                // Set in the surface changed method
                frecorder.setFrameRate(CONSTANTS.frameRate);

                Log.i(TAG, "recorder initialize success"+ SystemClock.currentThreadTimeMillis());


        audioRecordRunnable = new AudioRecordRunnable();
        audioThread = new Thread(audioRecordRunnable, "AudioRunnable");
        runAudioThread = true;

    }

    public void stopFFRecording() {
        runAudioThread = false;

        try {
            audioThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        audioRecordRunnable = null;
        audioThread = null;
        MainActivity.frecorderInitThread =  null;
        setRecordingStatus(false);

        Log.v(TAG,"Finishing recording, calling stop and release on recorder");
        try {
            if(frecorder != null)
            frecorder.stop();
            while(CameraView.doneFrameWrite!= true){
                Log.d(TAG, "wait for CameraView to finish last Frame");
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            frecorder.release();
        } catch (FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        }
        frecorder = null;
    }

    //---------------------------------------------
    // audio thread, gets and encodes audio data
    //---------------------------------------------
    class AudioRecordRunnable implements Runnable {

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            //ShortBuffer audioData;
            int bufferReadResult;
            int tmpBufSize = DataArray.bufferSize;
            int bufferSize;

            try {
               if(MainActivity.bufferInitThread !=null) MainActivity.bufferInitThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            Log.d(TAG, "AudioRecord Min buffersize: "+ DataArray.bufferSize);
           try {
               audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, CONSTANTS.sampleAudioRateInHz,
                       AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, DataArray.bufferSize);
           }catch (IllegalArgumentException e){
               e.printStackTrace();
           }
            int audioState = audioRecord.getState();
            if(audioState == AudioRecord.STATE_INITIALIZED) {
                audioRecord.startRecording();
                Log.d(TAG, "audioRecord.startRecording()");

            }
            /* ffmpeg_audio encoding loop */
            while (runAudioThread) {

                bufferReadResult = audioRecord.read(DataArray.audioData.array(), 0, DataArray.audioData.capacity());
                Log.v(TAG,"recording?  " + recording+ " readin result: "+ bufferReadResult);

                DataArray.audioData.limit(bufferReadResult);
                if (bufferReadResult > 0) {
                    Log.v(TAG,"bufferReadResult: " + bufferReadResult);
                    // If "recording" isn't true when start this thread, it never get's set according to this if statement...!!!
                    // Why?  Good question...
                    if (getRecordingStatus() == true) {
                        try {
                            frecorder.recordSamples(DataArray.audioData);
                            //Log.v(LOG_TAG,"recording " + 1024*i + " to " + 1024*i+1024);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.v(TAG,e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
            Log.v(TAG,"AudioThread Finished, release audioRecord");

            /* encoding finish, release recorder */
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
                Log.v(TAG,"audioRecord released");
            }
        }
    }


    private static synchronized void setRecordingStatus(boolean status){
        Log.d(TAG,"set RecordingStatus from "+recording+ " to "+status);
        recording = status;
    }
    protected static synchronized  boolean getRecordingStatus(){
        Log.d(TAG, "getRecordingStatus = "+ recording);
        return recording;
    }
/*
    private void initFFMepegRecorder(){

        MainActivity.frecorderInitThread = new Thread(new Runnable() {
            @Override
            public void run() {
                //    public FFmpegFrameRecorder(String filename, int imageWidth, int imageHeight, int audioChannels) {
                frecorder = new FFmpegFrameRecorder(CONSTANTS.ffmpeg_link, MainActivity.getPreviewWidth(), MainActivity.getPreviewHeight(), 1);
                Log.d(TAG, "frecorder newed at new frecorderInitThread "+" with H: "+MainActivity.getPreviewWidth()+" with W: "+ MainActivity.getPreviewHeight());
                if(frecorder == null)
                {
                    Toast.makeText(getApplicationContext(), "NULL MediaRecorder", Toast.LENGTH_SHORT).show();
                    java.lang.System.exit(1);
                }

                Log.i(TAG, "ffmpeg_url: " + CONSTANTS.ffmpeg_link);
                frecorder.setFormat("mp4");
                frecorder.setSampleRate(CONSTANTS.sampleAudioRateInHz);
                // Set in the surface changed method
                frecorder.setFrameRate(CONSTANTS.frameRate);

                Log.i(TAG, "recorder initialize success"+ SystemClock.currentThreadTimeMillis());
            }
        }, "Frecorder Init Thread");

        try {
            MainActivity.bufferInitThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Log.d(TAG, "start frecorderInitThread "+SystemClock.currentThreadTimeMillis());
        MainActivity.frecorderInitThread.start();

    }
    */
}
