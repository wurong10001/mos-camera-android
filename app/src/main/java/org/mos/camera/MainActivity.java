package org.mos.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Executor;

public class MainActivity extends ComponentActivity {
    private static final int REQUEST_CAMERA = 1001;

    private final Executor mainExecutor = command ->
            new Handler(Looper.getMainLooper()).post(command);
    private GlPreviewView previewView;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private boolean cameraPermissionGranted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.camera_preview);

        if (checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            cameraPermissionGranted = true;
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (previewView != null) {
            previewView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (previewView != null) {
            previewView.onPause();
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) {
            return;
        }

        cameraPermissionGranted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (cameraPermissionGranted) {
            startCamera();
        } else {
            Toast.makeText(this, "相机权限被拒绝", Toast.LENGTH_SHORT).show();
        }
    }

    private void startCamera() {
        if (!cameraPermissionGranted || cameraProviderFuture != null) {
            return;
        }

        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder()
                        .setTargetRotation(previewView.getDisplay() == null
                                ? Surface.ROTATION_0
                                : previewView.getDisplay().getRotation())
                        .build();
                preview.setSurfaceProvider(mainExecutor, previewView);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview);
            } catch (Exception exception) {
                cameraProviderFuture = null;
                Toast.makeText(this, "启动相机失败: " + exception.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }, mainExecutor);
    }
}
