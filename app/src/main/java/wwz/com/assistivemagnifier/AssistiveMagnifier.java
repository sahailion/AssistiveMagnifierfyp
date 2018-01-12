package wwz.com.assistivemagnifier;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AssistiveMagnifier extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 0;
    private String aCameraId;
    private Size aPreviewSize;
    private CaptureRequest.Builder aCaptureRequestBuilder;
    private HandlerThread aBackgroundHandlerThread;
    private Handler aBackgroundHandler;
    private CameraCaptureSession aCameraCaptureSession;

    private static SparseIntArray ORIENTATIONS = new SparseIntArray();
    static
    {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }


    static class CompareSizeByArea implements Comparator<Size>
    {
        @Override
        public int compare(Size lhs, Size rhs)
        {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() /
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    //Listening TextureView that holds Camera2 API
    private TextureView aTextureView;
    private TextureView.SurfaceTextureListener aSurfaceTextureListener = new TextureView.SurfaceTextureListener()
    {
        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height)
        {
            setupCamera(width, height);
            connectCamera();
            //Toast.makeText(getApplicationContext(), "TextureView is available", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height)
        {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture)
        {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture)
        {

        }
    };

    //Set app to fullscreen (doesn't work yet)
    public void magnifierFullScreen(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        if(hasFocus)
        {
            decorView.setSystemUiVisibility
                    (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    //Camera2 API
    private CameraDevice aCameraDevice;
    private CameraDevice.StateCallback aCameraDeviceStateCallback = new CameraDevice.StateCallback()
    {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice)
        {
            aCameraDevice = cameraDevice;
            Toast.makeText(getApplicationContext(), "Camera access success", Toast.LENGTH_SHORT).show();
            startPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice)
        {
            cameraDevice.close();
            aCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error)
        {
            cameraDevice.close();
            aCameraDevice = null;
        }
    };

    //On launching the app
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assistive_magnifier);
        aTextureView = (TextureView) findViewById(R.id.textureView);
    }

    //Ensures TextureView is available when app is opened
    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onResume()
    {
        super.onResume();

        startBackgroundThread();

        if(aTextureView.isAvailable())
        {
            setupCamera(aTextureView.getWidth(), aTextureView.getHeight());
            connectCamera();
        }
        else
        {
            aTextureView.setSurfaceTextureListener(aSurfaceTextureListener);
        }
    }


    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQUEST_CAMERA_PERMISSION)
        {
            if(grantResults[0] != PackageManager.PERMISSION_GRANTED)
            {
                Toast.makeText(getApplicationContext(), "Application will not run without camera access", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startPreview()
    {
        SurfaceTexture surfaceTexture = aTextureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(aPreviewSize.getWidth(), aPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        try
        {
            aCaptureRequestBuilder = aCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            aCaptureRequestBuilder.addTarget(previewSurface);

            aCameraDevice.createCaptureSession(Arrays.asList(previewSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession)
                {
                    aCameraCaptureSession = cameraCaptureSession;

                    try
                    {
                        //can add autofocus feature in here

                        aCameraCaptureSession.setRepeatingRequest(aCaptureRequestBuilder.build(), null, aBackgroundHandler);
                    }
                    catch (CameraAccessException e)
                    {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession)
                {
                    Toast.makeText(getApplicationContext(), "Preview fails have faith dont give up", Toast.LENGTH_SHORT).show();
                }
            }, null);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    @Override
    protected void onPause()
    {
        closeCamera();

        stopBackgroundThread();
        super.onPause();
    }


    private void closeCamera()
    {
        if(aCameraDevice != null)
        {
            aCameraDevice.close();
            aCameraDevice = null;
        }
    }

    private void setupCamera(int width, int height)
    {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try
        {
            for(String cameraId : cameraManager.getCameraIdList())
            {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                if(cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                {
                    continue;
                }
                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                int totalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
                boolean swapRotation = totalRotation == 90 || totalRotation == 270;
                int rotatedWidth = width;
                int rotatedHeight = height;
                if (swapRotation)
                {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }
                aPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight);
                aCameraId = cameraId;
                return;
            }
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }


    @TargetApi(Build.VERSION_CODES.M)
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void connectCamera()
    {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try
        {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            {
                if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                {
                    cameraManager.openCamera(aCameraId, aCameraDeviceStateCallback, aBackgroundHandler);
                }
                else
                {
                    if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                    {
                        Toast.makeText(this, "Camera access is required.", Toast.LENGTH_SHORT).show();
                    }
                    requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                }
            }
            else
            {
                cameraManager.openCamera(aCameraId, aCameraDeviceStateCallback, aBackgroundHandler);
            }
        }
        catch(CameraAccessException e)
        {
            e.printStackTrace();
        }
    }


    private void startBackgroundThread()
    {
        aBackgroundHandlerThread = new HandlerThread("AssistiveMagnifierBackground");
        aBackgroundHandlerThread.start();
        aBackgroundHandler = new Handler(aBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread()
    {
        aBackgroundHandlerThread.quitSafely();
        try
        {
            aBackgroundHandlerThread.join();
            aBackgroundHandlerThread = null;
            aBackgroundHandler = null;
        }
        catch(InterruptedException e)
        {
            e.printStackTrace();
        }
    }


    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation)
    {
        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        return (sensorOrientation + deviceOrientation + 360) % 360;
    }


    private static Size chooseOptimalSize(Size[] choices, int width, int height)
    {
        List<Size> bigEnough = new ArrayList<Size>();
        for (Size option : choices)
        {
            if(option.getHeight() == option.getWidth() * height / width &&
                    option.getWidth() >= width && option.getHeight() >= height)
            {
                bigEnough.add(option);
            }
        }
        if(bigEnough.size() > 0)
        {
            return Collections.min(bigEnough, new CompareSizeByArea());
        }
        else
        {
            return choices[0];
        }
    }
}
