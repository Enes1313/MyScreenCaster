package com.example.myscreencaster;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.InetAddresses;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.Surface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.core.content.ContextCompat;


import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE = 1000;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    private Thread streamingThread;
    private volatile boolean streaming = false;

    private Socket socket;
    private OutputStream outputStream;

    private EditText editIP, editPort;
    private Button btnStream;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editIP = findViewById(R.id.edit_ip);
        editPort = findViewById(R.id.edit_port);
        btnStream = findViewById(R.id.btn_stream);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenDensity = metrics.densityDpi;
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        btnStream.setOnClickListener(v -> {
            if (!streaming) {
                String ip = editIP.getText().toString();
                String portStr = editPort.getText().toString();
                if (ip.isEmpty() || portStr.isEmpty()) {
                    Toast.makeText(this, "IP and Port required", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!InetAddresses.isNumericAddress(ip)) {
                    Toast.makeText(this, "Invalid IP address", Toast.LENGTH_SHORT).show();
                    return;
                }

                int port = Integer.parseInt(portStr);
Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
ContextCompat.startForegroundService(this, serviceIntent);

                startProjection();

            } else {
                stopStreaming();
            }
        });
    }

    private void startProjection() {
    
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(intent, REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                setUpVirtualDisplay();
                startStreaming();
                btnStream.setText("Stop");
                streaming = true;
            } else {
                Toast.makeText(this, "Screen Cast Permission Denied", Toast.LENGTH_SHORT).show();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void setUpVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, 0x1, 2); // PixelFormat RGBA_8888 = 0x1
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                0,
                imageReader.getSurface(),
                null,
                null
        );
    }

private void startStreaming() {
    streamingThread = new Thread(() -> {
        try {
            String ip = editIP.getText().toString();
            int port = Integer.parseInt(editPort.getText().toString());

            socket = new Socket(ip, port);
            outputStream = socket.getOutputStream();

            Bitmap reusableBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888);

            while (streaming) {
                Image image = imageReader.acquireLatestImage();
                if (image == null) {
                    Thread.sleep(5);
                    continue;
                }

                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * screenWidth;

                Bitmap tempBitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888);
                tempBitmap.copyPixelsFromBuffer(buffer);
                image.close();

                Bitmap bitmap = Bitmap.createBitmap(tempBitmap, 0, 0, screenWidth, screenHeight);
                tempBitmap.recycle();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos); // JPEG = daha küçük ve hızlı
                byte[] jpegBytes = baos.toByteArray();
                bitmap.recycle();

                outputStream.write(intToByteArray(jpegBytes.length));
                outputStream.write(jpegBytes);
                outputStream.flush();

                Thread.sleep(16); // ~60 FPS hedefi
            }

            outputStream.close();
            socket.close();

        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Streaming error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                btnStream.setText("Start");
                streaming = false;
            });
        }
    });
    streamingThread.start();
}

    private byte[] intToByteArray(int value) {
        return new byte[]{
                (byte)(value >> 24),
                (byte)(value >> 16),
                (byte)(value >> 8),
                (byte)(value)
        };
    }

    private void stopStreaming() {
        streaming = false;
        btnStream.setText("Start");
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (streamingThread != null) {
            try {
                streamingThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            streamingThread = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStreaming();
    }
}

