package wwz.com.assistivemagnifier;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Camera;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.support.v4.app.ActivityCompat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;
import android.graphics.ColorMatrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public class AssistiveMagnifier extends AppCompatActivity implements View.OnTouchListener, ActivityCompat.OnRequestPermissionsResultCallback
{

    private static final int REQUEST_CAMERA_PERMISSION = 0;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION = 1;
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAIT_LOCK = 1;
    private static final String CAMERA_FRONT = "1";
    private static final String CAMERA_BACK = "0";
    private double zoom_level = 1;
    private float finger_spacing = 0;
    private int aCaptureState = STATE_PREVIEW;
    private String aCameraId;
    private Size aPreviewSize;
    private CameraManager flashCameraManager;
    private String flashCameraId = CAMERA_BACK;
    private String zoomCameraId = CAMERA_BACK;
    private ImageButton flashlightButton;
    private boolean flashlightOn = false;
    private CaptureRequest.Builder aCaptureRequestBuilder;
    private HandlerThread aBackgroundHandlerThread;
    private Handler aBackgroundHandler;
    private File screenshotFolder;
    private String folderFileName;
    private int totalRotation;
    private int sensorOrientation;
    private Activity activity;
    private boolean focused = false;
    private CameraCaptureSession aCameraCaptureSession;
    private CameraCaptureSession.CaptureCallback mCameraCaptureCallback = new
            CameraCaptureSession.CaptureCallback()
            {
              private void process(CaptureResult captureResult)
              {
                  switch (aCaptureState)
                  {
                      case STATE_PREVIEW:
                          //Do nothing
                          break;
                      case STATE_WAIT_LOCK:
                          Integer afState = captureResult.get(CaptureResult.CONTROL_AF_STATE);
                          if(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState)
                          {
                              focused = true;
                          }
                          break;
                  }
              }


              @Override
              public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result)
              {
                  super.onCaptureCompleted(session, request, result);

                  process(result);
              }
            };
    private Size aImageSize;
    private ImageReader aImageReader;
    private final ImageReader.OnImageAvailableListener aOnPhotoAvailableListener = new
            ImageReader.OnImageAvailableListener()
            {
                @Override
                public void onImageAvailable(ImageReader imageReader)
                {
                    aBackgroundHandler.post(new ImageSaver(imageReader.acquireLatestImage()));
                }
            };
    private class ImageSaver implements Runnable
    {
        private final Image aImage;
        public ImageSaver(Image image)
        {
            aImage = image;
        }

        @Override
        public void run()
        {
            ByteBuffer byteBuffer = aImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);

            FileOutputStream outputStream = null;
            try
            {
                outputStream = new FileOutputStream(folderFileName);
                outputStream.write(bytes);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
            finally
            {
                aImage.close();
                if(outputStream != null)
                {
                    try
                    {
                        outputStream.close();
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        }
    }


    //Compare resolution size while rotating screen
    private static class CompareSizeByArea implements Comparator<Size>
    {
        @Override
        public int compare(Size lhs, Size rhs)
        {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
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

    //Set app to fullscreen (fingers crossed hopes this work)
    public void onWindowFocusChanged(boolean hasFocus)
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
        createScreenshotFolder();


        aTextureView = (TextureView) findViewById(R.id.textureView);

        //onTouch(View v,MotionEvent event);

        //check if device has flashlight feature
        final boolean hasCameraFlash = getApplicationContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
        flashCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try
        {
            flashCameraId = flashCameraManager.getCameraIdList()[0];
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
        //flashlight button
        flashlightButton = (ImageButton) findViewById(R.id.flash_light_onoff_button);
        flashlightButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick (View view)
            {
                try
                {
                    if(hasCameraFlash)
                    {
                        if(flashlightOn)
                        {
                            flashlightButton.setImageResource(R.mipmap.flash_light_button);
                            offFlash();
                        }
                        else
                        {
                            flashlightButton.setImageResource(R.mipmap.flashlight_off_button);
                            onFlash();
                        }
                    }
                    else
                    {
                        Toast.makeText(getApplicationContext(), "No flash feature in this device.", Toast.LENGTH_SHORT).show();
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });


        //screenshot button
        ImageButton screenshotButton = (ImageButton) findViewById(R.id.screenshot_button);
        screenshotButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                checkWriteStoragePermission();
                lockFocus();
                captureStillPicture();
                Toast.makeText(getApplicationContext(), "Photo captured and saved.", Toast.LENGTH_SHORT).show();
            }
        });

        //focus button
        ImageButton manualFocusButton = (ImageButton) findViewById(R.id.focus_button);
        manualFocusButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                lockFocus();
                if(focused)
                {
                    Toast.makeText(getApplicationContext(), "Camera focused.", Toast.LENGTH_SHORT).show();
                }
            }
        });


        //zoom in
        ImageButton zoomInButton = (ImageButton) findViewById(R.id.zoom_in_button);
        zoomInButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                zoom_in();
            }
        });

        //zoom_out
        ImageButton zoomOutButton = (ImageButton) findViewById(R.id.zoom_out_button);
        zoomOutButton.setOnClickListener(new View.OnClickListener()
        {
          @Override
          public void onClick(View view)
          {
              zoom_out();
          }
        });

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
        if(flashlightOn)
        {
            onFlash();
        }
    }


    //When first launched or when camera access was denied, pops out asking to access camera
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

        if(requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION)
        {
            if(grantResults[0] != PackageManager.PERMISSION_GRANTED)
            {
                Toast.makeText(getApplicationContext(), "Application needs permission to save photos", Toast.LENGTH_SHORT).show();
            }
        }
    }

    //Starting to preview from camera
    private void startPreview()
    {
        SurfaceTexture surfaceTexture = aTextureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(aPreviewSize.getWidth(), aPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        try
        {
            aCaptureRequestBuilder = aCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            aCaptureRequestBuilder.addTarget(previewSurface);

            aCameraDevice.createCaptureSession(Arrays.asList(previewSurface, aImageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession)
                {
                    aCameraCaptureSession = cameraCaptureSession;

                    try
                    {
                        //autofocus continuously
                        aCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

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


    //Close camera when this app is switched to background
    @Override
    protected void onPause()
    {
        closeCamera();

        stopBackgroundThread();
        super.onPause();
        if(flashlightOn)
        {
            offFlash();
        }
    }


    //close camera when this app is closed
    private void closeCamera()
    {
        if(aCameraDevice != null)
        {
            aCameraDevice.close();
            aCameraDevice = null;
        }
        if(flashlightOn)
        {
            offFlash();
        }
    }

    //Getting correct Camera ID (only back lens in this app)
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
                sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                totalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
                boolean swapRotation = false;
                switch (deviceOrientation)
                {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_180:
                        if(sensorOrientation == 90 || sensorOrientation == 270)
                        {
                            swapRotation = true;
                        }
                        break;
                    case Surface.ROTATION_270:
                        if(sensorOrientation == 0 || sensorOrientation == 180)
                        {
                            swapRotation = true;
                        }
                        break;
                    default:
                        Toast.makeText(getApplicationContext(), "Display rotation invalid", Toast.LENGTH_SHORT).show();

                }
                int rotatedWidth = width;
                int rotatedHeight = height;
                if (swapRotation)
                {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }
                aPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight);
                aImageSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), rotatedWidth, rotatedHeight);
                aImageReader = ImageReader.newInstance(aImageSize.getWidth(), aImageSize.getHeight(), ImageFormat.JPEG, 1);
                aImageReader.setOnImageAvailableListener(aOnPhotoAvailableListener, aBackgroundHandler);
                aCameraId = cameraId;
                return;
            }
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }


    //Connects camera with background thread (TextureView)
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

    //Start background thread
    private void startBackgroundThread()
    {
        aBackgroundHandlerThread = new HandlerThread("AssistiveMagnifierBackground");
        aBackgroundHandlerThread.start();
        aBackgroundHandler = new Handler(aBackgroundHandlerThread.getLooper());
    }

    //Stops background thread
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


    //Senses the rotation value of devices
    private int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation)
    {
        sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        return (sensorOrientation + deviceOrientation + 270) % 360;
        //return (sensorOrientation - deviceOrientation + 270) % 360;
        //return (sensorOrientation - deviceOrientation + 360) % 360;
    }


    //Choosing optimal size when rotating the screen
    private static Size chooseOptimalSize(Size[] choices, int width, int height)
    {
        List<Size> bigEnough = new ArrayList<>();
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

    //get autofocus from camera
    private void lockFocus()
    {
        try
        {
            //tell camera to focus
            aCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            //tell capturecallback to wait for lock
            aCaptureState = STATE_WAIT_LOCK;
            aCameraCaptureSession.capture(aCaptureRequestBuilder.build(), mCameraCaptureCallback, aBackgroundHandler);
        }
        catch(CameraAccessException e)
        {
            e.printStackTrace();
        }

    }

    //capture picture while preview is still
    private void captureStillPicture()
    {
        try
        {
            aCaptureRequestBuilder = aCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            aCaptureRequestBuilder.addTarget(aImageReader.getSurface());
            aCaptureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, totalRotation);

            CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback()
            {
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber);

                    try
                    {
                        createScreenshotFileName();
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }
                }
            };

            aCameraCaptureSession.capture(aCaptureRequestBuilder.build(), captureCallback, null);
        }
        catch(CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    //create a file to store screenshots
    private void createScreenshotFolder()
    {
        File imageFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        screenshotFolder = new File(imageFile, "AssistiveMagnifierScreenshot");
        if(!screenshotFolder.exists())
        {
            screenshotFolder.mkdirs();
        }
    }

    private File createScreenshotFileName() throws IOException
    {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prepend = "IMAGE_" + timeStamp + "_";
        File imageFile = File.createTempFile(prepend, ".jpg", screenshotFolder);
        folderFileName = imageFile.getAbsolutePath();
        return imageFile;
    }

    //check storage permission to save photo
    private void checkWriteStoragePermission()
    {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED)
            {
                try
                {
                    createScreenshotFileName();
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
            else
            {
                if(shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                {
                    Toast.makeText(this, "A folder needs to be created", Toast.LENGTH_SHORT).show();
                }
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION);
            }
        }
        else
        {
            try
            {
                createScreenshotFileName();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    //turn on flashlight
    private void onFlash()
    {
        try
        {
            if(flashCameraId.equals(CAMERA_BACK))
            {
                aCaptureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                aCameraCaptureSession.setRepeatingRequest(aCaptureRequestBuilder.build(), null, null);
                flashlightOn = true;
            }
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }

    }

    //turn off flashlight
    private void offFlash()
    {
        try
        {
            if(flashCameraId.equals(CAMERA_BACK))
            {
                aCaptureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                aCameraCaptureSession.setRepeatingRequest(aCaptureRequestBuilder.build(), null, null);
                flashlightOn = false;
            }
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    //zoom in using button
    private void zoom_in()
    {
        int minWidth, minHeight, difWidth, difHeight, cropWidth, cropHeight;
        try
        {
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(flashCameraId);
            zoom_level += 20;
            float maxZoom = (cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))*10;

            Rect centerCanvas = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            if(zoom_level >= maxZoom-20)
            {
                zoom_level -= 20;
            }
            else if (zoom_level < maxZoom) {
                minWidth = (int) (centerCanvas.width() / maxZoom);
                minHeight = (int) (centerCanvas.height() / maxZoom);
                difWidth = centerCanvas.width() - minWidth;
                difHeight = centerCanvas.height() - minHeight;
                cropWidth = difWidth / 100 * (int) zoom_level;
                cropHeight = difHeight / 100 * (int) zoom_level;
                cropWidth -= cropWidth & 3;
                cropHeight -= cropHeight & 3;
                Rect zoom = new Rect(cropWidth, cropHeight, centerCanvas.width() - cropWidth, centerCanvas.height() - cropHeight);
                aCaptureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom);
            }
            try
            {
                aCameraCaptureSession.setRepeatingRequest(aCaptureRequestBuilder.build(), null, null);
            }
            catch (CameraAccessException e)
            {
                e.printStackTrace();
            }
            catch (NullPointerException ex)
            {
                ex.printStackTrace();
            }
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    //zoom out
    private void zoom_out()
    {
        int minWidth, minHeight, difWidth, difHeight, cropWidth, cropHeight;
        try
        {
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(flashCameraId);
            zoom_level -= 20;
            float maxZoom = (cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))*10;

            Rect centerCanvas = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            if(zoom_level == 1)
            {
                zoom_level += 20;
            }
            else if (zoom_level <= maxZoom+50) {
                minWidth = (int) (centerCanvas.width() / maxZoom);
                minHeight = (int) (centerCanvas.height() / maxZoom);
                difWidth = centerCanvas.width() - minWidth;
                difHeight = centerCanvas.height() - minHeight;
                cropWidth = difWidth / 100 * (int) zoom_level;
                cropHeight = difHeight / 100 * (int) zoom_level;
                cropWidth -= cropWidth & 3;
                cropHeight -= cropHeight & 3;
                Rect zoom = new Rect(cropWidth, cropHeight, centerCanvas.width() - cropWidth, centerCanvas.height() - cropHeight);
                aCaptureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom);
            }
            try
            {
                aCameraCaptureSession.setRepeatingRequest(aCaptureRequestBuilder.build(), null, null);
            }
            catch (CameraAccessException e)
            {
                e.printStackTrace();
            }
            catch (NullPointerException ex)
            {
                ex.printStackTrace();
            }
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }


    //-------------------------------------------------------
    //Functions which are not working yet
    //-------------------------------------------------------

    //Setting screen rotation resolution values
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();
    static
    {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }



    //zoom using pinch
    public boolean onTouch(View v, MotionEvent event)
    {
        try
        {
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(aCameraId);
            float maxZoom = (cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))*10;
            Rect m = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            int action = event.getAction();
            float current_finger_spacing;

            if(event.getPointerCount() > 1 )
            {
                current_finger_spacing = getFingerSpacing(event);
                if(finger_spacing != 0)
                {
                    if(current_finger_spacing > finger_spacing && maxZoom > zoom_level)
                    {
                        zoom_level = zoom_level +.4;
                        //zoom_level++;
                    }
                    else if(current_finger_spacing < finger_spacing && zoom_level > 1)
                    {
                        zoom_level = zoom_level - .4;
                        //zoom_level--;
                    }
                    int minWidth = (int) (m.width() / maxZoom);
                    int minHeight = (int) (m.height() / maxZoom);
                    int difWidth = m.width() - minWidth;
                    int difHeight = m.height() - minHeight;
                    int cropWidth = difWidth / 100 * (int)zoom_level;
                    int cropHeight = difHeight / 100 * (int)zoom_level;
                    cropWidth -= cropWidth & 3;
                    cropHeight -= cropHeight & 3;
                    Rect zoom = new Rect(cropWidth, cropHeight, m.width() - cropWidth, m.height() - cropHeight);
                    aCaptureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom);
                }
                finger_spacing = current_finger_spacing;
            }
            else
            {
                if(action == MotionEvent.ACTION_UP){}
            }
            try
            {
                aCameraCaptureSession.setRepeatingRequest(aCaptureRequestBuilder.build(), mCameraCaptureCallback, null);
                //super.onTouchEvent(event);
            }
            catch (CameraAccessException e)
            {
                e.printStackTrace();
            }
        }
        catch (CameraAccessException e)
        {
            throw new RuntimeException("Can't access camera.", e);
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private float getFingerSpacing(MotionEvent event)
    {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    static public MotionEvent obtain(long downTime, long eventTime, int action, float x, float y, int metastate) {
        return null;
    }


}
