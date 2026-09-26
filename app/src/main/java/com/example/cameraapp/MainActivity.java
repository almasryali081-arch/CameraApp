package com.example.cameraapp;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CameraApp";
    private PreviewView previewView;
    private View nightVisionOverlay;
    private ImageCapture imageCapture;
    private boolean nightModeOn = false;
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.previewView);
        nightVisionOverlay = findViewById(R.id.nightVisionOverlay);

        ImageButton captureButton = findViewById(R.id.captureButton);
        captureButton.setOnClickListener(v -> takePhoto());

                .findViewById(android.R.id.content);

        TextView nightModeText = findNightModeTextView();
        if (nightModeText != null) {
            nightModeText.setOnClickListener(v -> toggleNightVision(nightModeText));
        }

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private TextView findNightModeTextView() {
        View root = findViewById(android.R.id.content);
        return findTextViewByText(root, "ليلي");
    }

    private TextView findTextViewByText(View view, String text) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            if (text.equals(tv.getText().toString())) {
                return tv;
            }
        } else if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findTextViewByText(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }

    private void toggleNightVision(TextView label) {
        nightModeOn = !nightModeOn;
        if (nightModeOn) {
            nightVisionOverlay.setVisibility(View.VISIBLE);
            label.setTextColor(0xFF00FF00);
            label.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            nightVisionOverlay.setVisibility(View.GONE);
            label.setTextColor(0xFFFFFFFF);
            label.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
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
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            }
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
                        CameraSelector baseCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                        CameraSelector cameraSelector = baseCameraSelector;

                        if (extensionsManager.isExtensionAvailable(baseCameraSelector, ExtensionMode.NIGHT)) {
                            cameraSelector = extensionsManager.getExtensionEnabledCameraSelector(
                                    baseCameraSelector, ExtensionMode.NIGHT);
                        }

                        Preview preview = new Preview.Builder().build();
                        preview.setSurfaceProvider(previewView.getSurfaceProvider());

                        imageCapture = new ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .build();

                        cameraProvider.unbindAll();
                        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

                    } catch (ExecutionException | InterruptedException e) {
                        Log.e(TAG, "Extensions error: " + e.getMessage());
                    }
                }, ContextCompat.getMainExecutor(this));

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
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
                        Toast.makeText(getBaseContext(), "تم حفظ الصورة", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(ImageCaptureException exception) {
                        Log.e(TAG, "خطأ بالتقاط الصورة: " + exception.getMessage());
                        Toast.makeText(getBaseContext(), "فشل الالتقاط", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }
}
