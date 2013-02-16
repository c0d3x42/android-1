package com.twofours.surespot.activities;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.twofours.surespot.CameraPreview;
import com.twofours.surespot.R;
import com.twofours.surespot.chat.ChatUtils;
import com.twofours.surespot.common.FileUtils;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.network.IAsyncCallback;

public class ImageSelectActivity extends SherlockActivity {
	private static final String TAG = "ImageSelectActivity";
	public static final int REQUEST_EXISTING_IMAGE = 1;
	public static final int REQUEST_CAPTURE_IMAGE = 2;
	private static final String COMPRESS_SUFFIX = "compress";
	private static final String CAPTURE_SUFFIX = "capture";

	private ImageView mImageView;
	private Button mSendButton;
	private Button mCancelButton;
	private Button mCaptureButton;
	private File mCapturedImagePath;
	private File mCompressedImagePath;
	private int mSource;
	private Camera mCamera;
	private OrientationEventListener mOrientationEventListener;
	private int mOrientation;
	private int mCaptureOrientation;
	private int mCameraOrientation;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_image_select);

		mOrientationEventListener = new OrientationEventListener(this) {

			@Override
			public void onOrientationChanged(int orientation) {
				// SurespotLog.v(TAG, "orientation: " + orientation);
				mOrientation = orientation;
			}
		};

		final String to = getIntent().getStringExtra("to");

		mSendButton = (Button) this.findViewById(R.id.send);
		mSendButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				Intent dataIntent = new Intent();
				dataIntent.setData(Uri.fromFile(mCompressedImagePath));
				dataIntent.putExtra("to", to);
				dataIntent.putExtra("filename", mCompressedImagePath.getPath());
				setResult(Activity.RESULT_OK, dataIntent);
				finish();
			}
		});

		mCaptureButton = (Button) this.findViewById(R.id.capture);
		mCaptureButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				// if we have an image captured already, they are clicking reject
				if (mCompressedImagePath != null) {
					deleteCompressedImage();
					mCaptureButton.setText("capture");
					mSendButton.setEnabled(false);
					mCamera.startPreview();
				}
				else {
					mCaptureOrientation = mOrientation;
					mCamera.takePicture(null, null, new PictureCallback() {

						@Override
						public void onPictureTaken(byte[] data, Camera camera) {
							// SurespotLog.v(TAG, "onPictureTaken");

							if (data == null) {
								SurespotLog.e(TAG, "onPictureTaken", new IOException("could not get postview image data"));
							}
							else {
								try {
									deleteCapturedImage();
									mCapturedImagePath = createImageFile(CAPTURE_SUFFIX);
									FileOutputStream fos = new FileOutputStream(mCapturedImagePath);
									fos.write(data);
									fos.close();

									int rotation = (mCaptureOrientation + 45) / 90 * 90;

									// leave it upside down
									if (rotation == 360 || rotation == 180) {
										rotation = 0;
									}

									// compress image
									compressImage(Uri.fromFile(mCapturedImagePath), rotation + mCameraOrientation,
											new IAsyncCallback<Bitmap>() {

												@Override
												public void handleResponse(Bitmap result) {
													if (result != null) {
														mCaptureButton.setText("reject");
														mSendButton.setEnabled(true);
													}
													else {
														Utils.makeToast(ImageSelectActivity.this, "could not capture image");
														mSendButton.setEnabled(false);
													}

													deleteCapturedImage();
												}
											});

								}
								catch (FileNotFoundException e) {
									SurespotLog.w(TAG, "File not found: " + e.getMessage());
									Utils.makeToast(ImageSelectActivity.this, "could not capture image");
								}
								catch (IOException e) {
									SurespotLog.w(TAG, "Error accessing file: " + e.getMessage());
									Utils.makeToast(ImageSelectActivity.this, "could not capture image");
								}

							}
						}

					});
				}

			}

		});

		mCancelButton = (Button) this.findViewById(R.id.cancel);
		mCancelButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				deleteCapturedImage();
				deleteCompressedImage();
				finish();
			}
		});

		mSource = getIntent().getIntExtra("source", REQUEST_EXISTING_IMAGE);

		switch (mSource) {
		case REQUEST_EXISTING_IMAGE:
			mImageView = (ImageView) this.findViewById(R.id.image);
			mImageView.setVisibility(View.VISIBLE);
			Intent intent = new Intent();
			// TODO paid version allows any file
			intent.setType("image/*");
			intent.setAction(Intent.ACTION_GET_CONTENT);
			Utils.configureActionBar(this, "select image", to, true);
			startActivityForResult(Intent.createChooser(intent, "select Image"),
					SurespotConstants.IntentRequestCodes.REQUEST_EXISTING_IMAGE);
			break;

		case REQUEST_CAPTURE_IMAGE:
			mCaptureButton.setVisibility(View.VISIBLE);
			Utils.configureActionBar(this, "capture image", to, true);
			mCamera = getCameraInstance();
			if (mCamera != null) {
				CameraPreview cameraPreview = new CameraPreview(this, mCamera);
				FrameLayout preview = (FrameLayout) findViewById(R.id.camera_preview);
				preview.setVisibility(View.VISIBLE);
				preview.addView(cameraPreview);
			}
			break;

		default:
			finish();
		}

	}

	/** A safe way to get an instance of the Camera object. */
	public Camera getCameraInstance() {
		Camera c = null;
		try {
			c = Camera.open(); // attempt to get a Camera instance
			setCameraDisplayOrientation(this, 0, c);
		}
		catch (Exception e) {
			SurespotLog.w(TAG, "getCameraInstance", e);
			// Camera is not available (in use or does not exist)
		}

		return c; // returns null if camera is unavailable
	}

	// TODO handle forward camera
	public void setCameraDisplayOrientation(Activity activity, int cameraId, android.hardware.Camera camera) {
		android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
		android.hardware.Camera.getCameraInfo(cameraId, info);
		int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
		int degrees = 0;
		switch (rotation) {
		case Surface.ROTATION_0:
			degrees = 0;
			break;
		case Surface.ROTATION_90:
			degrees = 90;
			break;
		case Surface.ROTATION_180:
			degrees = 180;
			break;
		case Surface.ROTATION_270:
			degrees = 270;
			break;
		}

		int result;
		if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
			result = (info.orientation + degrees) % 360;
			result = (360 - result) % 360; // compensate the mirror
		}
		else { // back-facing
			SurespotLog.v(TAG, "camera orientation: " + info.orientation);
			mCameraOrientation = info.orientation;
			result = (info.orientation - degrees + 360) % 360;
		}

		camera.setDisplayOrientation(result);
	}

	@SuppressWarnings("static-access")
	private synchronized File createImageFile(String suffix) throws IOException {

		// Create a unique image file name
		String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		String imageFileName = "image" + "_" + timeStamp + "_" + suffix;

		File dir = FileUtils.getImageCaptureDir(this);
		if (FileUtils.ensureDir(dir)) {
			File file = new File(dir.getPath(), imageFileName);
			file.createNewFile();
			// SurespotLog.v(TAG, "createdFile: " + file.getPath());
			return file;
		}
		else {
			throw new IOException("Could not create image temp file dir: " + dir.getPath());
		}

	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getSupportMenuInflater().inflate(R.menu.activity_image_select, menu);
		return true;
	}

	private void deleteCompressedImage() {
		if (mCompressedImagePath != null) {
			// SurespotLog.v(TAG, "deleteCompressedImage: " + mCompressedImagePath.getPath());
			mCompressedImagePath.delete();
			mCompressedImagePath = null;
		}
	}

	private void deleteCapturedImage() {
		if (mCapturedImagePath != null) {
			// SurespotLog.v(TAG, "deleteCapturedImage: " + mCapturedImagePath.getPath());
			// Thread.dumpStack();
			mCapturedImagePath.delete();
			mCapturedImagePath = null;
		}
	}

	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (resultCode == RESULT_OK && requestCode == SurespotConstants.IntentRequestCodes.REQUEST_EXISTING_IMAGE) {
			// TODO on thread
			Uri uri = data.getData();
			// scale, compress and save the image

			compressImage(uri, -1, new IAsyncCallback<Bitmap>() {

				@Override
				public void handleResponse(Bitmap result) {
					if (result != null) {
						mImageView.setImageBitmap(result);
						mSendButton.setEnabled(true);
					}
					else {
						mSendButton.setEnabled(false);
					}
				}
			});

		}

	}

	private synchronized void compressImage(final Uri uri, final int rotate, final IAsyncCallback<Bitmap> callback) {
		new AsyncTask<Void, Void, Bitmap>() {

			@Override
			protected Bitmap doInBackground(Void... params) {

				// scale, compress and save the image
				Bitmap bitmap = ChatUtils.decodeSampledBitmapFromUri(ImageSelectActivity.this, uri, rotate);
				try {
					deleteCompressedImage();
					if (bitmap != null) {
						mCompressedImagePath = createImageFile(COMPRESS_SUFFIX);
						// SurespotLog.v(TAG, "compressingImage to: " + mCompressedImagePath);
						FileOutputStream fos = new FileOutputStream(mCompressedImagePath);

						bitmap.compress(Bitmap.CompressFormat.JPEG, 75, fos);

						fos.close();
						// SurespotLog.v(TAG, "done compressingImage to: " + mCompressedImagePath);
					}
					return bitmap;

				}
				catch (IOException e) {
					SurespotLog.w(TAG, "onActivityResult", e);
					if (mCompressedImagePath != null) {
						mCompressedImagePath.delete();
						mCompressedImagePath = null;
					}
					return null;
				}

			}

			protected void onPostExecute(Bitmap result) {
				callback.handleResponse(result);

			};
		}.execute();

	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case android.R.id.home:
			finish();

			return true;
		default:
			return super.onOptionsItemSelected(item);
		}

	}

	@Override
	protected void onResume() {
		super.onResume();
		mOrientationEventListener.enable();
	}

	@Override
	protected void onPause() {
		super.onPause();
		mOrientationEventListener.disable();
		releaseCamera();
	}

	private void releaseCamera() {
		if (mCamera != null) {
			mCamera.release(); // release the camera for other applications
			mCamera = null;
		}
	}
}
