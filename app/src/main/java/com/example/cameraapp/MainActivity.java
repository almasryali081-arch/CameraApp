package com.example.cameraapp;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CameraApp";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUESTED_PERMISSIONS = {
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    private static final int MODE_PHOTO = 0;
    private static final int MODE_VIDEO = 1;

    private PreviewView previewView;
    private View nightVisionOverlay;
    private View captureButton;
    private TextView flashButton;
    private TextView timerButton;
    private TextView zoomText;
    private TextView countdownText;
    private TextView nightModeLabel;
    private TextView portraitLabel;
    private TextView photoLabel;
    private TextView videoLabel;
    private ImageView thumbnailView;

    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private Camera camera;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int captureMode = MODE_PHOTO;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;
    private boolean nightModeOn = false;
    private boolean portraitOn = false;
    private boolean timerOn = false;
    private boolean counting = false;
    private Uri lastPhotoUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        nightVisionOverlay = findViewById(R.id.nightVisionOverlay);
        captureButton = findViewById(R.id.captureButton);
        flashButton = findViewById(R.id.flashButton);
        timerButton = findViewById(R.id.timerButton);
        zoomText = findViewById(R.id.zoomText);
        countdownText = findViewById(R.id.countdownText);
        nightModeLabel = findViewById(R.id.nightModeLabel);
        portraitLabel = findViewById(R.id.portraitLabel);
        photoLabel = findViewById(R.id.photoLabel);
        videoLabel = findViewById(R.id.videoLabel);
        thumbnailView = findViewById(R.id.thumbnailView);

        captureButton.setOnClickListener(v -> onCaptureRequested());
        findViewById(R.id.switchCameraButton).setOnClickListener(v -> switchCamera());
        flashButton.setOnClickListener(v -> cycleFlash());
        timerButton.setOnClickListener(v -> toggleTimer());
        nightModeLabel.setOnClickListener(v -> toggleNight());
        portraitLabel.setOnClickListener(v -> togglePortrait());
        photoLabel.setOnClickListener(v -> setMode(MODE_PHOTO));
        videoLabel.setOnClickListener(v -> setMode(MODE_VIDEO));
        thumbnailView.setOnClickListener(v -> openLastPhoto());

        setupTouchControls();
        updateFlashLabel();
        updateTimerLabel();
        updateModeLabels();

        if (cameraGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUESTED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private boolean cameraGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean audioGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS && cameraGranted()) {
            startCamera();
        }
    }

    private void setupTouchControls() {
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (camera == null) return true;
                        ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
                        if (zoomState == null) return true;
                        float newRatio = zoomState.getZoomRatio() * detector.getScaleFactor();
                        newRatio = Math.max(zoomState.getMinZoomRatio(),
                                Math.min(newRatio, zoomState.getMaxZoomRatio()));
                        camera.getCameraControl().setZoomRatio(newRatio);
                        zoomText.setText(String.format(Locale.US, "%.1fx", newRatio));
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        focusAt(e.getX(), e.getY());
                        return true;
                    }
                });

        previewView.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            if (!scaleDetector.isInProgress()) {
                gestureDetector.onTouchEvent(event);
            }
            return true;
        });
    }

    private void focusAt(float x, float y) {
        if (camera == null) return;
        MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point).build();
        camera.getCameraControl().startFocusAndMetering(action);
    }

    private void cycleFlash() {
        if (flashMode == ImageCapture.FLASH_MODE_OFF) {
            flashMode = ImageCapture.FLASH_MODE_ON;
        } else if (flashMode == ImageCapture.FLASH_MODE_ON) {
            flashMode = ImageCapture.FLASH_MODE_AUTO;
        } else {
            flashMode = ImageCapture.FLASH_MODE_OFF;
        }
        if (imageCapture != null) {
            imageCapture.setFlashMode(flashMode);
        }
        updateFlashLabel();
    }

    private void updateFlashLabel() {
        if (flashMode == ImageCapture.FLASH_MODE_ON) {
            flashButton.setText("⚡ تشغيل");
            flashButton.setTextColor(0xFFFFD700);
        } else if (flashMode == ImageCapture.FLASH_MODE_AUTO) {
            flashButton.setText("⚡ تلقائي");
            flashButton.setTextColor(0xFFFFD700);
        } else {
            flashButton.setText("⚡ إيقاف");
            flashButton.setTextColor(0xFFFFFFFF);
        }
    }

    private void toggleTimer() {
        timerOn = !timerOn;
        updateTimerLabel();
    }

    private void updateTimerLabel() {
        if (recording != null) {
            timerButton.setText("● تسجيل");
            timerButton.setTextColor(0xFFFF3B30);
        } else if (timerOn) {
            timerButton.setText("⏱ 2 ث");
            timerButton.setTextColor(0xFFFFD700);
        } else {
            timerButton.setText("⏱ إيقاف");
            timerButton.setTextColor(0xFFFFFFFF);
        }
    }

    private void setLabel(TextView label, boolean active, int activeColor) {
        label.setTextColor(active ? activeColor : 0xFFFFFFFF);
        label.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void updateModeLabels() {
        setLabel(nightModeLabel, nightModeOn, 0xFF00FF00);
        setLabel(portraitLabel, portraitOn, 0xFFFFD700);
        setLabel(photoLabel, captureMode == MODE_PHOTO, 0xFFFFD700);
        setLabel(videoLabel, captureMode == MODE_VIDEO, 0xFFFFD700);
        nightVisionOverlay.setVisibility(nightModeOn ? View.VISIBLE : View.GONE);
    }

    private void toggleNight() {
        if (recording != null) return;
        nightModeOn = !nightModeOn;
        if (nightModeOn) portraitOn = false;
        updateModeLabels();
        startCamera();
    }

    private void togglePortrait() {
        if (recording != null) return;
        if (captureMode == MODE_VIDEO) {
            Toast.makeText(this, "البورتريه متاح في وضع الصورة فقط", Toast.LENGTH_SHORT).show();
            return;
        }
        portraitOn = !portraitOn;
        if (portraitOn) nightModeOn = false;
        updateModeLabels();
        startCamera();
    }

    private void setMode(int mode) {
        if (recording != null) {
            Toast.makeText(this, "أوقف التسجيل أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        if (captureMode == mode) return;
        captureMode = mode;
        if (mode == MODE_VIDEO) portraitOn = false;
        updateModeLabels();
        startCamera();
    }

    private void switchCamera() {
        if (recording != null) return;
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            lensFacing = CameraSelector.LENS_FACING_FRONT;
        } else {
            lensFacing = CameraSelector.LENS_FACING_BACK;
        }
        startCamera();
    }

    private void openLastPhoto() {
        if (lastPhotoUri == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(lastPhotoUri, "image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لا يوجد تطبيق لعرض الصورة", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) {
                onCaptureRequested();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);

        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();

                ListenableFuture<ExtensionsManager> extFuture =
                        ExtensionsManager.getInstanceAsync(getApplicationContext(), provider);

                extFuture.addListener(() -> {
                    try {
                        ExtensionsManager extManager = extFuture.get();

                        CameraSelector base = new CameraSelector.Builder()
                                .requireLensFacing(lensFacing)
                                .build();

                        Preview preview = new Preview.Builder().build();
                        preview.setSurfaceProvider(previewView.getSurfaceProvider());

                        provider.unbindAll();

                        if (captureMode == MODE_VIDEO) {
                            imageCapture = null;
                            Recorder recorder = new Recorder.Builder()
                                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                                    .build();
                            videoCapture = VideoCapture.withOutput(recorder);
                            camera = provider.bindToLifecycle(this, base, preview, videoCapture);
                        } else {
                            videoCapture = null;
                            CameraSelector selector = base;

                            int ext = ExtensionMode.NONE;
                            if (nightModeOn) {
                                ext = ExtensionMode.NIGHT;
                            } else if (portraitOn) {
                                ext = ExtensionMode.BOKEH;
                            }

                            if (ext != ExtensionMode.NONE) {
                                if (extManager.isExtensionAvailable(base, ext)) {
                                    selector = extManager.getExtensionEnabledCameraSelector(base, ext);
                                } else if (ext == ExtensionMode.BOKEH) {
                                    portraitOn = false;
                                    updateModeLabels();
                                    Toast.makeText(this, "البورتريه غير مدعوم على هذا الجهاز",
                                            Toast.LENGTH_LONG).show();
                                } else {
                                    Toast.makeText(this,
                                            "الوضع الليلي الحقيقي غير مدعوم على جهازك، تم تفعيل التأثير الأخضر فقط",
                                            Toast.LENGTH_LONG).show();
                                }
                            }

                            imageCapture = new ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                    .setFlashMode(flashMode)
                                    .build();
                            camera = provider.bindToLifecycle(this, selector, preview, imageCapture);
                        }
                        zoomText.setText("1.0x");

                    } catch (Exception e) {
                        Log.e(TAG, "Camera error: " + e.getMessage());
                        Toast.makeText(this, "تعذر تشغيل الكاميرا بهذا الوضع", Toast.LENGTH_SHORT).show();
                    }
                }, ContextCompat.getMainExecutor(this));

            } catch (Exception e) {
                Log.e(TAG, "Provider error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void onCaptureRequested() {
        if (captureMode == MODE_VIDEO) {
            toggleRecording();
            return;
        }
        if (counting) return;
        if (timerOn) {
            counting = true;
            countdownText.setText("2");
            countdownText.setVisibility(View.VISIBLE);
            handler.postDelayed(() -> countdownText.setText("1"), 1000);
            handler.postDelayed(() -> {
                countdownText.setVisibility(View.GONE);
                counting = false;
                takePhoto();
            }, 2000);
        } else {
            takePhoto();
        }
    }

    private void toggleRecording() {
        if (videoCapture == null) return;

        if (recording != null) {
            recording.stop();
            return;
        }

        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis());

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraApp");
        }

        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(
                getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build();

        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, options);
        if (audioGranted()) {
            pending = pending.withAudioEnabled();
        }

        recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Start) {
                captureButton.setAlpha(0.5f);
                updateTimerLabel();
            } else if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) event;
                recording = null;
                captureButton.setAlpha(1.0f);
                updateTimerLabel();
                if (!finalizeEvent.hasError()) {
                    Toast.makeText(this, "تم حفظ الفيديو", Toast.LENGTH_SHORT).show();
                } else {
                    Log.e(TAG, "Video error code: " + finalizeEvent.getError());
                    Toast.makeText(this, "فشل حفظ الفيديو", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis());

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraApp");
        }

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
        ).build();

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                        Uri uri = outputFileResults.getSavedUri();
                        if (uri != null) {
                            lastPhotoUri = uri;
                            thumbnailView.setImageURI(uri);
                        }
                        Toast.makeText(getBaseContext(), "تم حفظ الصورة", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(ImageCaptureException exception) {
                        Log.e(TAG, "Capture error: " + exception.getMessage());
                        Toast.makeText(getBaseContext(), "فشل الالتقاط", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }
}
