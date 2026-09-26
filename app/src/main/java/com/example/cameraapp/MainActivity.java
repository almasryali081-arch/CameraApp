package com.example.cameraapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CameraApp";
    private PreviewView previewView;
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.previewView);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
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

                        boolean nightAvailable = extensionsManager.isExtensionAvailable(
                                baseCameraSelector, ExtensionMode.NIGHT);

                        if (nightAvailable) {
                            Log.d(TAG, "الوضع الليلي متوفر على هذا الجهاز ✅");
                            cameraSelector = extensionsManager.getExtensionEnabledCameraSelector(
                                    baseCameraSelector, ExtensionMode.NIGHT);
                        } else {
                            Log.d(TAG, "الوضع الليلي غير مدعوم على هذا الجهاز ❌ - سيتم استخدام الكاميرا العادية");
                        }

                        Preview preview = new Preview.Builder().build();
                        preview.setSurfaceProvider(previewView.getSurfaceProvider());

                        cameraProvider.unbindAll();
                        cameraProvider.bindToLifecycle(this, cameraSelector, preview);

                    } catch (ExecutionException | InterruptedException e) {
                        Log.e(TAG, "خطأ بتفعيل الإضافات: " + e.getMessage());
                    }
                }, ContextCompat.getMainExecutor(this));

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }
}
