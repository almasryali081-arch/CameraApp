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
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CameraApp";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA};

    private PreviewView previewView;
    private View nightVisionOverlay;
    private TextView flashButton;
    private TextView timerButton;
    private TextView zoomText;
    private TextView countdownText;
    private TextView nightModeLabel;
    private ImageView thumbnailView;

    private ImageCapture imageCapture;
    private Camera camera;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;
    private boolean nightModeOn = false;
    private boolean timerOn = false;
    private boolean counting = false;
    private Uri lastPhotoUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        nightVisionOverlay = findViewById(R.id.nightVisionOverlay);
        flashButton = findViewById(R.id.flashButton);
        timerButton = findViewById(R.id.timerButton);
        zoomText = findViewById(R.id.zoomText);
        countdownText = findViewById(R.id.countdownText);
        nightModeLabel = findViewById(R.id.nightModeLabel);
        thumbnailView = findViewById(R.id.thumbnailView);

        findViewById(R.id.captureButton).setOnClickListener(v -> onCaptureRequested());
        findViewById(R.id.switchCameraButton).setOnClickListener(v -> switchCamera());
        flashButton.setOnClickListener(v -> cycleFlash());
        timerButton.setOnClickListener(v -> toggleTimer());
        nightModeLabel.setOnClickListener(v -> toggleNightVision());
        thumbnailView.setOnClickListener(v -> openLastPhoto());

        setupTouchControls();
        updateFlashLabel();
        updateTimerLabel();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
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
        if (timerOn) {
            timerButton.setText("⏱ 2 ث");
            timerButton.setTextColor(0xFFFFD700);
        } else {
            timerButton.setText("⏱ إيقاف");
            timerButton.setTextColor(0xFFFFFFFF);
        }
    }

    private void toggleNightVision() {
        nightModeOn = !nightModeOn;
        if (nightModeOn) {
            nightVisionOverlay.setVisibility(View.VISIBLE);
            nightModeLabel.setTextColor(0xFF00FF00);
            nightModeLabel.setTypeface(null, Typeface.BOLD);
        } else {
            nightVisionOverlay.setVisibility(View.GONE);
            nightModeLabel.setTextColor(0xFFFFFFFF);
            nightModeLabel.setTypeface(null, Typeface.NORMAL);
        }
    }

    private void switchCamera() {
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

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            startCamera();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                ListenableFuture<ExtensionsManager> extensionsManagerFuture =
                        ExtensionsManager.getInstanceAsync(getApplicationContext(), cameraProvider);

                extensionsManagerFuture.addListener(() -> {
                    try {
                        ExtensionsManager extensionsManager = extensionsManagerFuture.get();

                        CameraSelector baseCameraSelector = new CameraSelector.Builder()
                                .requireLensFacing(lensFacing)
                                .build();
                        CameraSelector cameraSelector = baseCameraSelector;

                        if (extensionsManager.isExtensionAvailable(baseCameraSelector, ExtensionMode.NIGHT)) {
                            cameraSelector = extensionsManager.getExtensionEnabledCameraSelector(
                                    baseCameraSelector, ExtensionMode.NIGHT);
                        }

                        Preview preview = new Preview.Builder().build();
                        preview.setSurfaceProvider(previewView.getSurfaceProvider());

                        imageCapture = new ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setFlashMode(flashMode)
                                .build();

                        cameraProvider.unbindAll();
                        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                        zoomText.setText("1.0x");

                    } catch (Exception e) {
                        Log.e(TAG, "Camera error: " + e.getMessage());
                        Toast.makeText(this, "تعذر تشغيل هذه الكاميرا", Toast.LENGTH_SHORT).show();
                    }
                }, ContextCompat.getMainExecutor(this));

            } catch (Exception e) {
                Log.e(TAG, "Provider error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void onCaptureRequested() {
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
