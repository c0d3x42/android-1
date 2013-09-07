package com.twofours.surespot.voice;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Activity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaRecorder.AudioSource;
import android.view.View;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

import com.todoroo.aacenc.AACEncoder;
import com.todoroo.aacenc.AACToM4A;
import com.twofours.surespot.R;
import com.twofours.surespot.activities.MainActivity;
import com.twofours.surespot.chat.ChatController;
import com.twofours.surespot.chat.SurespotMessage;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.network.IAsyncCallback;
import com.twofours.surespot.network.NetworkController;

public class VoiceController {
	private static final String TAG = "VoiceController";

	private static String mFileName = null;
	private static String mUsername = null;

	private static RehearsalAudioRecorder mRecorder = null;

	private ChatController mChatController;
	private NetworkController mNetworkController;
	private static HashMap<String, VoiceMessageContainer> mContainers;
	static TimerTask mCurrentTimeTask;
	static boolean mRecording = false;
	private static SeekBarThread mSeekBarThread;
	private static SurespotMessage mMessage;

	static Timer mTimer;
	private static File mAudioFile;
	static MediaPlayer mPlayer;
	static SeekBar mSeekBar;
	static boolean mPlaying = false;

	enum State {
		INITIALIZING, READY, STARTED, RECORDING
	};

	private final static int[] sampleRates = { 44100, 22050, 11025, 8000 };

	private static State mState;
	private static AACEncoder mEncoder;

	private static int mDuration;

	private long mTimeAtStart;

	static {

		// mChatController = chatController;
		// mNetworkController = networkController;
		mState = State.STARTED;
		mEncoder = new AACEncoder();
		mContainers = new HashMap<String, VoiceMessageContainer>();

	}

	static int getMaxAmplitude() {
		if (mRecorder == null || mState != State.RECORDING)
			return 0;
		return mRecorder.getMaxAmplitude();
	}

	private static void startTimer(final Activity activity) {
		if (mTimer != null) {
			mTimer.cancel();
			mTimer.purge();
		}
		mTimer = new Timer();
		mCurrentTimeTask = new TimerTask() {
			public void run() {
				activity.runOnUiThread(new Runnable() {
					public void run() {
						VolumeEnvelopeView mEnvelopeView = (VolumeEnvelopeView) activity.findViewById(R.id.volume_envelope);
						if (mState == State.RECORDING) {
							// mCurrentTime.setText(mSessionPlayback.playTimeFormatter().format(mRecordService.getTimeInRecording()));
							// if(mVolumeEnvelopeEnabled)
							mEnvelopeView.setNewVolume(getMaxAmplitude());
							return;
						}

						mEnvelopeView.clearVolume();
					}
				});
			}
		};
		mTimer.scheduleAtFixedRate(mCurrentTimeTask, 0, 100);

	}

	private synchronized static void startRecording(final Activity activity) {
		if (mState != State.STARTED)
			return;

		try {
			// MediaRecorder has major delay issues on gingerbread so we record raw PCM then convert natively to m4a
			if (mFileName != null) {
				new File(mFileName).delete();
			}

			// create a temp file to hold the uncompressed audio data
			mFileName = File.createTempFile("voice", ".wav").getAbsolutePath();
			SurespotLog.v(TAG, "recording to: %s", mFileName);

			int i = 0;
			int sampleRate = 44100;

			do {
				if (mRecorder != null)
					mRecorder.release();
				sampleRate = sampleRates[i];
				mRecorder = new RehearsalAudioRecorder(true, AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_CONFIGURATION_MONO,
						AudioFormat.ENCODING_PCM_16BIT);
			}
			while ((++i < sampleRates.length) & !(mRecorder.getState() == RehearsalAudioRecorder.State.INITIALIZING));

			SurespotLog.v(TAG, "sampleRate: %d", sampleRate);
			VolumeEnvelopeView mEnvelopeView = (VolumeEnvelopeView) activity.findViewById(R.id.volume_envelope);
			mEnvelopeView.setVisibility(View.VISIBLE);
			mRecorder.setOutputFile(mFileName);
			mRecorder.prepare();
			mRecorder.start();

			startTimer(activity);
			// mTimeAtStart = new Date().getTime();
			mState = State.RECORDING;
			// Utils.makeToast(context, "sample rate: " + sampleRate);
		}
		catch (IOException e) {
			SurespotLog.e(TAG, e, "prepare() failed");
		}

	}

	private synchronized static void stopRecordingInternal() {
		// state must be RECORDING
		if (mState != State.RECORDING)
			return;
		try {

			mTimer.cancel();
			mTimer.purge();
			mTimer = null;
			mCurrentTimeTask = null;
			mRecorder.stop();
			mRecorder.release();
			mRecorder = null;

			mState = State.STARTED;
		}
		catch (RuntimeException stopException) {

		}

	}

	// Play unencrypted audio file from path and optionally delete it

	private synchronized static void startPlaying(Context context, final SeekBar seekBar, final SurespotMessage message, final boolean delete) {
		SurespotLog.v(TAG, "startPlaying");
		// updateSeekBar(context, message, seekBar, new IAsyncCallback<Integer>() {
		//
		// @Override
		// public void handleResponse(Integer result) {
		// if (result > 0) {
		// SurespotLog.v(TAG, "startPlaying playing");
		// VoiceMessageContainer vmc = mContainers.get(message.getIv());
		// vmc.play(seekBar, 0);
		// }
		// }
		// });
		if (mPlaying) {
			mPlayer.stop();
			playCompleted();
		}

		if (!mPlaying) {
			mPlaying = true;

			if (mSeekBarThread == null) {

				mSeekBarThread = new SeekBarThread();
			}

			mPlayer = new MediaPlayer();
			try {
				if (mAudioFile != null) {
					mAudioFile.delete();
				}

				mAudioFile = File.createTempFile("sound", ".m4a");

				FileOutputStream fos = new FileOutputStream(mAudioFile);
				fos.write(message.getPlainBinaryData());
				fos.close();

				mPlayer.setDataSource(mAudioFile.getAbsolutePath());
				mPlayer.prepare();
			}
			catch (Exception e) {
				SurespotLog.w(TAG, e, "prepAndPlay error");
				mPlaying = false;
				return;
			}

			// if (progress > 0) {
			// mPlayer.seekTo(progress);
			// }

			mMessage = message;
			mSeekBar = seekBar;
			mDuration = message.getPlainBinaryData().length;
			new Thread(mSeekBarThread).start();
			mPlayer.start();

			mPlayer.setOnCompletionListener(new OnCompletionListener() {

				@Override
				public void onCompletion(MediaPlayer mp) {

					playCompleted();
				}
			});
		}

	}

	private static void playCompleted() {
		mSeekBarThread.completed();
		mMessage.setPlayMedia(false);
		if (mAudioFile != null) {
			mAudioFile.delete();
		}
		mPlayer.release();
		mPlayer = null;
		mPlaying = false;

	}

	public synchronized void destroy() {
		if (mRecorder != null) {
			mRecorder.release();
			mRecorder = null;
		}

		// for (MediaPlayer mp : mMediaPlayers.values()) {
		// mp.stop();
		// mp.release();
		// }

		mChatController = null;
		mNetworkController = null;

	}

	public static synchronized void startRecording(Activity context, String username) {

		if (!mRecording) {

			mUsername = username;
			Utils.makeToast(context, "recording");
			startRecording(context);
			mRecording = true;
		}

	}

	public synchronized static void stopRecording(MainActivity activity, IAsyncCallback<Boolean> callback) {
		if (mRecording) {
			stopRecordingInternal();
			sendVoiceMessage(activity, callback);
			Utils.makeToast(activity, "encrypting and transmitting");
			VolumeEnvelopeView mEnvelopeView = (VolumeEnvelopeView) activity.findViewById(R.id.volume_envelope);
			mEnvelopeView.setVisibility(View.GONE);
			mRecording = false;
		}
		else {
			callback.handleResponse(true);
		}
	}

	private synchronized static void sendVoiceMessage(MainActivity activity, final IAsyncCallback<Boolean> callback) {
		// convert to AAC
		// TODO bg thread?
		FileInputStream fis;
		try {
			fis = new FileInputStream(mFileName);
			Date start = new Date();

			String outFile = File.createTempFile("voice", ".aac").getAbsolutePath();
			mEncoder.init(16000, 1, 44100, 16, outFile);

			mEncoder.encode(Utils.inputStreamToBytes(fis));
			mEncoder.uninit();

			// delete raw pcm
			new File(mFileName).delete();

			// convert to m4a (gingerbread can't play the AAC for some bloody reason).
			final String m4aFile = File.createTempFile("voice", ".m4a").getAbsolutePath();
			new AACToM4A().convert(activity, outFile, m4aFile);

			// delete aac
			new File(outFile).delete();

			SurespotLog.v(TAG, "AAC encoding end, time: %d ms", (new Date().getTime() - start.getTime()));

			FileInputStream m4aStream = new FileInputStream(m4aFile);

			MainActivity.getChatController().sendVoiceMessage(mUsername, Utils.inputStreamToBytes(m4aStream), SurespotConstants.MimeTypes.M4A);

			// ChatUtils.uploadVoiceMessageAsync(activity, mChatController, mNetworkController, Uri.fromFile(new File(m4aFile)), mUsername,
			// new IAsyncCallback<Boolean>() {
			//
			// @Override
			// public void handleResponse(Boolean result) {
			// if (result) {
			// // delete m4a
			// new File(m4aFile).delete();
			// }
			//
			// if (callback != null) {
			// callback.handleResponse(result);
			// }
			// }
			// });

		}

		catch (IOException e) {
			Utils.makeToast(activity, "error sending message");
			SurespotLog.w(TAG, e, "sendVoiceMessage");
		}

	}

	public static void playVoiceMessage(Context context, final SeekBar seekBar, final SurespotMessage message) {
		SurespotLog.v(TAG, "playVoiceMessage");
		startPlaying(context, seekBar, message, true);
	}

	public static void updateSeekBar(Context context, SurespotMessage message, SeekBar seekBar, IAsyncCallback<Integer> durationCallback) {
		SurespotLog.v(TAG, "updateSeekBar, seekBar: %s, message: %s", seekBar, message.getIv());

		VoiceMessageContainer messageVmc = mContainers.get(message.getIv());

		if (messageVmc == null) {

			// if they are the same do nothing
			// otherwise update

			SurespotLog.v(TAG, "updateSeekBar, creating voice message container");
			messageVmc = new VoiceMessageContainer(context, MainActivity.getNetworkController(), message);
			mContainers.put(message.getIv(), messageVmc);

			// messageVmc.attach(seekBar);
			messageVmc.prepareAudio(seekBar, durationCallback);

		}
		else {
			// messageVmc.attach(seekBar);
			if (durationCallback != null) {
				durationCallback.handleResponse(messageVmc.getDuration());
			}
		}

		seekBar.setTag(messageVmc);

	}

	public static void attach(SurespotMessage message, SeekBar seekBar) {
		// mSeekBarReference = new WeakReference<SeekBar>(seekBar);

		// if this message is the one that's being played, attach the seekbar
		if (message.equals(mMessage)) {
			mSeekBar = seekBar;
		}
		else {
			mSeekBar = null;
		}

		// VoiceMessageDownloader.DecryptionTaskWrapper task = seekBar.getTag();
		// seekBar.setOnSeekBarChangeListener(new MyOnSeekBarChangedListener());
		// setDatetimeAndDuration(seekBar);
	}

	private static class SeekBarThread implements Runnable {
		private boolean mRun = true;

		@Override
		public void run() {

			// VoiceMessageContainer seekContainer = (VoiceMessageContainer) seekBar.getTag();

			// if (VoiceMessageContainer.this == seekContainer) {

			mRun = true;

			if (mSeekBar != null) {
				mSeekBar.setMax(100);
			}

			while (mRun) {

				int progress = 0;
				if (mDuration > -1) {
					int currentPosition = mPlayer.getCurrentPosition();

					progress = (int) (((float) currentPosition / (float) mDuration) * 101);
					SurespotLog.v(TAG, "SeekBarThread: %s, currentPosition: %d, duration: %d, percent: %d", this, currentPosition, mDuration, progress);
					if (progress < 0)
						progress = 0;
					if (progress > 90)
						progress = 100;

					if (mSeekBar != null) {

						SurespotLog.v(TAG, "setting seekBar: %s, progress: %d", mSeekBar, progress);

						if (currentPosition < mDuration) {
							mSeekBar.setProgress(progress);

						}
						else {
							mSeekBar.setProgress(0);
						}
					}

				}
				try {
					Thread.sleep(100);
				}
				catch (InterruptedException e) {
					e.printStackTrace();
				}
				//
				// if (progress == 100) {
				// completed();
				// }

			}

		}

		public void completed() {
			SurespotLog.v(TAG, "SeekBarThread completed");
			mRun = false;

			if (mSeekBar != null) {
				mSeekBar.setProgress(100);
			}
		}

		public void reset() {
			SurespotLog.v(TAG, "SeekBarThread reset");
			mRun = false;
			if (mSeekBar != null) {
				mSeekBar.setProgress(100);
			}
		}
	}

	private class MyOnSeekBarChangedListener implements OnSeekBarChangeListener {

		@Override
		public synchronized void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch) {
			// if (seekBar.getTag() != VoiceMessageContainer.this) {
			// return;
			// }
			//
			// if (fromTouch && mDuration > -1 && mLoaded && mPlayer != null) {
			// SurespotLog.v(TAG, "onProgressChanged, progress: %d, seekBar: %s", progress, seekBar);
			// int time = (int) (mPlayer.getDuration() * (float) progress / 101);
			// // if (mVoiceController.)
			//
			// if (mPlayer.isPlaying()) {
			// SurespotLog.v(TAG, "onProgressChanged,  seekingTo: %d", progress);
			// mPlayer.seekTo(time);
			//
			// }
			// else {
			// SurespotLog.v(TAG, "onProgressChanged,  playing from: %d", progress);
			// play(seekBar, time);
			//
			// }
			// }
		}

		@Override
		public void onStartTrackingTouch(SeekBar seekBar) {
			// Empty method
		}

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
			// Empty method
		}
	}

}