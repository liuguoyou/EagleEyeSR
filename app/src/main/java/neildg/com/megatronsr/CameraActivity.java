package neildg.com.megatronsr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Camera;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import neildg.com.megatronsr.camera2.CameraDrawableView;
import neildg.com.megatronsr.camera2.CameraModule;
import neildg.com.megatronsr.camera2.CameraTextureView;
import neildg.com.megatronsr.camera2.CameraUserSettings;
import neildg.com.megatronsr.camera2.ICameraModuleListener;
import neildg.com.megatronsr.camera2.ICameraTextureViewListener;
import neildg.com.megatronsr.camera2.ResolutionPicker;
import neildg.com.megatronsr.camera2.capture.CaptureProcessor;
import neildg.com.megatronsr.io.FileImageWriter;
import neildg.com.megatronsr.platformtools.utils.notifications.NotificationCenter;
import neildg.com.megatronsr.platformtools.utils.notifications.NotificationListener;
import neildg.com.megatronsr.platformtools.utils.notifications.Notifications;
import neildg.com.megatronsr.platformtools.utils.notifications.Parameters;
import neildg.com.megatronsr.ui.ResolutionPickerDialog;

public class CameraActivity extends AppCompatActivity implements ICameraTextureViewListener, ICameraModuleListener, SensorEventListener, View.OnTouchListener, NotificationListener {
    private final static String TAG = "CameraActivity";
    private final static int REQUEST_CAMERA_PERMISSION = 200;

    private String cameraId;

    private CameraModule cameraModule;
    private CameraTextureView cameraTextureView;
    private CameraDrawableView cameraDrawableView;

    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    protected CameraCaptureSession cameraCaptureSessions;
    protected CaptureRequest.Builder captureRequestBuilder;

    private ResolutionPickerDialog resolutionPickerDialog;

    private SensorManager sensorManager;
    private int sensorRotation = 0;

    //private FocusCallback focusCallback = new FocusCallback(this);

    private CaptureProcessor captureProcessor = new CaptureProcessor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        CameraUserSettings.initialize();
        this.initializeCameraModule();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        this.captureProcessor.startBackgroundThread();
        if (this.cameraTextureView.getTextureView().isAvailable()) {
            this.openCamera();
        }

        this.sensorManager = (SensorManager) this.getSystemService(SENSOR_SERVICE);
        this.sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL);

        NotificationCenter.getInstance().addObserver(Notifications.ON_CAPTURE_COMPLETED, this);
    }

    @Override
    public void onPause() {
        super.onPause();
        this.cameraModule.closeCamera();
        this.captureProcessor.cleanup();

        this.sensorManager = (SensorManager) this.getSystemService(SENSOR_SERVICE);
        this.sensorManager.unregisterListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
        this.sensorManager = null;

        NotificationCenter.getInstance().removeObserver(Notifications.ON_CAPTURE_COMPLETED, this);
    }



    @Override
    public void onDestroy() {
        super.onDestroy();
        CameraUserSettings.destroy();
    }

    private void initializeCameraModule() {
        this.cameraModule = new CameraModule(this);
        CameraUserSettings.getInstance().setSelectedCamera(CameraUserSettings.CameraType.BACK);
        TextureView textureView =  (TextureView) this.findViewById(R.id.camera_view);
        this.cameraTextureView = new CameraTextureView(textureView, this, this);
        this.cameraDrawableView = (CameraDrawableView) this.findViewById(R.id.camera_drawable_view);

        ImageButton takePictureBtn = (ImageButton) this.findViewById(R.id.btn_capture_image);
        takePictureBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CameraActivity.this.takePicture();
            }
        });

        Button modeBtn = (Button) this.findViewById(R.id.btn_mode);
        modeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
               resolutionPickerDialog.show();
            }
        });

        final ImageButton switchCamBtn = (ImageButton) this.findViewById(R.id.btn_switch_camera);
        switchCamBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //switch camera
                CameraUserSettings.getInstance().switchCamera();
                CameraActivity.this.closeCamera();
                CameraActivity.this.openCamera();
                switchCamBtn.setImageResource(CameraUserSettings.getInstance().getCameraResource(CameraUserSettings.getInstance().getCameraType()));
            }
        });
    }

    @Override
    public void onCameraTextureViewAvailable(CameraTextureView textureView) {
        this.openCamera();
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "is camera open");
        try {
            this.cameraId = manager.getCameraIdList()[CameraUserSettings.getInstance().getCameraId()];
            ResolutionPicker.updateCameraSettings(this, this.cameraId);
            this.resolutionPickerDialog = new ResolutionPickerDialog(this);
            this.resolutionPickerDialog.setup(ResolutionPicker.getSharedInstance().getAvailableCameraSizes());
            this.cameraTextureView.updateToOptimalSize(ResolutionPicker.getSharedInstance().getAvailableCameraSizes());

            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(CameraActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(this.cameraId, this.cameraModule, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "openCamera X");
        this.printCameraCharacteristics();
    }

    private void closeCamera() {
        this.cameraModule.closeCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(CameraActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /*
     * Determine if specified camera modes are available
     */
    private void printCameraCharacteristics() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(this.cameraId);
            int hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);

            if(hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) {
                Log.d(TAG, "Camera hardware level: INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED");
            }
            else if(hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                Log.d(TAG, "Camera hardware level: INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY");
            }
            else if(hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) {
                Log.d(TAG, "Camera hardware level: INFO_SUPPORTED_HARDWARE_LEVEL_3");
            }
            else if(hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) {
                Log.d(TAG, "Camera hardware level: INFO_SUPPORTED_HARDWARE_LEVEL_FULL");
            }
        } catch(CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /*protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }
    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }*/

    @Override
    public void onCameraOpenedSuccess(CameraDevice cameraDevice) {
        this.createCameraPreview();
    }

    @Override
    public void onCameraDisconnected(CameraDevice camera) {

    }

    protected void createCameraPreview() {
        try {
            TextureView textureView = this.cameraTextureView.getTextureView();
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            Size lastResolutionSize = ResolutionPicker.getSharedInstance().getLastAvailableSize();
            Log.d(TAG, "Last resolution size: " +lastResolutionSize.toString());
            texture.setDefaultBufferSize(lastResolutionSize.getWidth(), lastResolutionSize.getHeight());
            Surface surface = new Surface(texture);
            final CameraDevice cameraDevice = this.cameraModule.getCameraDevice();
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (cameraDevice == null) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(CameraActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    protected void updatePreview() {
        CameraDevice cameraDevice = this.cameraModule.getCameraDevice();
        if(null == cameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        this.captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        //this.captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF);

        try {
            this.cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    protected void takePicture() {
        Size lastResolutionSize = ResolutionPicker.getSharedInstance().getLastAvailableSize();
        Size thumbnailSize = ResolutionPicker.getSharedInstance().getLastAvailableThumbnailSize();
        Size swappedThumbnailSize = new Size(thumbnailSize.getHeight(), thumbnailSize.getWidth());
        TextureView textureView = this.cameraTextureView.getTextureView();

        this.captureProcessor.setup(this.cameraModule.getCameraDevice(), lastResolutionSize, swappedThumbnailSize, this.sensorRotation, CameraUserSettings.getInstance().getCameraType());
        this.captureProcessor.clearSurfaces();
        this.captureProcessor.createSurfaceFromTextureView(textureView);
        this.captureProcessor.performCapture();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        float aX= event.values[0];
        float aY= event.values[1];
        //aZ= event.values[2];
        double angle = Math.atan2(aX, aY)/(Math.PI/180);

        double absAngle = Math.abs(angle);
        if(absAngle >= 0 && absAngle < 90) {
            this.sensorRotation = 90;
        }
        else if(absAngle >= 90 && absAngle < 180) {
            this.sensorRotation = 0;
        }
        else if(absAngle >= 180 && absAngle < 270) {
            this.sensorRotation = 180;
        }
        else {
            this.sensorRotation = 270;
        }

        //Log.d(TAG, "Angle: "+angle+ " Sensor rotation: " +this.sensorRotation);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        Log.d(TAG, "Touch event applied. X:" +event.getX()+ " Y: " +event.getY());
        int x = Math.round(event.getX());
        int y = Math.round(event.getY());
        this.cameraDrawableView.drawFocusRegion(x, y, 1000);
        //this.performAutoFocusOnRegion(x, y);
        return true;
    }

    /*private void performAutoFocusOnRegion(int x, int y) {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(this.cameraId);
            Log.d(TAG, "Max focus count:" + characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)+ " Max auto-exposure count: " +characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE));
            MeteringRectangle[] regionRectList = new MeteringRectangle[1];
            regionRectList[0] = new MeteringRectangle(new Point(x,y), new Size(150,150), MeteringRectangle.METERING_WEIGHT_MAX);
            this.captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START); //trigger Auto-focus algorithm
            this.captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, regionRectList);
            this.cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), this.focusCallback, mBackgroundHandler);

        } catch(CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void unlockAutoFocus() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            this.captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL); //cancel auto-focus
            this.cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), this.focusCallback, mBackgroundHandler);

        } catch(CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private class FocusCallback extends CameraCaptureSession.CaptureCallback {

        private CameraActivity activity;

        public FocusCallback(CameraActivity activity) {
            this.activity = activity;
        }
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
            if(afState == CaptureRequest.CONTROL_AF_STATE_INACTIVE) {
                //Log.d(TAG, "CONTROL_AF_STATE_INACTIVE");
            }
            else if(afState == CaptureRequest.CONTROL_AF_STATE_INACTIVE) {
                //Log.d(TAG, "CONTROL_AF_STATE_INACTIVE");
            }
            else if(afState == CaptureRequest.CONTROL_AF_STATE_PASSIVE_SCAN) {
                //Log.d(TAG, "CONTROL_AF_STATE_PASSIVE_SCAN");
            }
            else if(afState == CaptureRequest.CONTROL_AF_STATE_PASSIVE_FOCUSED) {
                //Log.d(TAG, "CONTROL_AF_STATE_PASSIVE_FOCUSED");
            }
            else if(afState == CaptureRequest.CONTROL_AF_STATE_ACTIVE_SCAN) {
                //Log.d(TAG, "CONTROL_AF_STATE_ACTIVE_SCAN");
            }
            else if(afState == CaptureRequest.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                //Log.d(TAG, "CONTROL_AF_STATE_FOCUSED_LOCKED");
                this.activity.unlockAutoFocus();
            }
            else if(afState == CaptureRequest.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                //Log.d(TAG, "CONTROL_AF_STATE_FOCUSED_LOCKED");
            }
            else if(afState == CaptureRequest.CONTROL_AF_STATE_PASSIVE_UNFOCUSED) {
                //Log.d(TAG, "CONTROL_AF_STATE_FOCUSED_LOCKED");
            }
        }
    }*/

    @Override
    public void onNotify(String notificationString, Parameters params) {
        if(notificationString == Notifications.ON_CAPTURE_COMPLETED) {
            Toast.makeText(this, "Picture saved!", Toast.LENGTH_SHORT).show();
            this.createCameraPreview();
        }
    }
}
