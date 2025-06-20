package com.example.myscreencaster;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.util.Range;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import android.os.Handler;
import android.os.Looper;


public class MainActivity extends AppCompatActivity {

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private Surface inputSurface;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    private Thread streamingThread;
    private volatile boolean streaming = false;

    private Socket socket;
    private OutputStream outputStream;

    private EditText editIP, editPort;
    private Button btnStream;

    private ActivityResultLauncher<Intent> projectionLauncher;

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

        projectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        mediaProjection = projectionManager.getMediaProjection(result.getResultCode(), result.getData());
                        setupEncoder();
                        
                        mediaProjection.registerCallback(new MediaProjection.Callback() {
                                @Override
                                public void onStop() {
                                    stopStreaming();
                                    Log.i("ScreenCapture", "MediaProjection stopped.");
                                }
                            }, new Handler(Looper.getMainLooper()));

                        setUpVirtualDisplay();
                        startStreaming();
                        btnStream.setText("Stop");
                        streaming = true;
                    } else {
                        Toast.makeText(this, "Screen Cast Permission Denied", Toast.LENGTH_SHORT).show();
                    }
                });

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

                Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                ContextCompat.startForegroundService(this, serviceIntent);

                if (mediaProjection != null) {
                    stopStreaming();
                }

                Intent intent = projectionManager.createScreenCaptureIntent();
                projectionLauncher.launch(intent);
            } else {
                stopStreaming();
            }
        });
    }

    private void setUpVirtualDisplay() {
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                0,
                inputSurface,
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

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            MediaFormat outFormat = encoder.getOutputFormat(); // start() sonrası alınır
            ByteBuffer csd0 = outFormat.getByteBuffer("csd-0");
            ByteBuffer csd1 = outFormat.getByteBuffer("csd-1");

            send(outputStream, csd0);
            send(outputStream, csd1);

            while (streaming) {
                int idx = encoder.dequeueOutputBuffer(info, 10000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // ignore, config already sent
                } else if (idx >= 0) {
                    ByteBuffer buff = encoder.getOutputBuffer(idx);
                    if (buff != null) {
                        send(outputStream, buff, info.size);
                    }
                    encoder.releaseOutputBuffer(idx, false);
                }
            }

            try {
                encoder.stop();
            } catch (Exception e) {
                Log.e("ENCODER", "Stop failed: " + e.getMessage());
            }
            try {
                encoder.release();
            } catch (Exception e) {
                Log.e("ENCODER", "Release failed: " + e.getMessage());
            }

            runOnUiThread(() -> btnStream.setText("Start"));

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

    private void setupEncoder() {
        try {
            String codecName = null;
            int adjustedWidth = screenWidth;
            int adjustedHeight = screenHeight;

            MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            for (MediaCodecInfo codecInfo : codecs) {
                if (!codecInfo.isEncoder()) continue;
                if (!codecInfo.getName().toLowerCase().contains("avc")) continue;

                MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType("video/avc");
                if (caps == null) continue;

                MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                Range<Integer> widthRange = videoCaps.getSupportedWidths();
                Range<Integer> heightRange = videoCaps.getSupportedHeights();

                if (screenWidth < widthRange.getLower() || screenWidth > widthRange.getUpper()) {
                    adjustedWidth = Math.min(Math.max(screenWidth, widthRange.getLower()), widthRange.getUpper());
                }
                if (screenHeight < heightRange.getLower() || screenHeight > heightRange.getUpper()) {
                    adjustedHeight = Math.min(Math.max(screenHeight, heightRange.getLower()), heightRange.getUpper());
                }

                for (int fmt : caps.colorFormats) {
                    if (fmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                        codecName = codecInfo.getName();
                        break;
                    }
                }

                if (codecName != null) break;
            }

            if (codecName == null) throw new RuntimeException("Uygun AVC codec bulunamadı");

            MediaFormat format = MediaFormat.createVideoFormat("video/avc", adjustedWidth, adjustedHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, adjustedWidth * adjustedHeight * 4);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);

            

            encoder = MediaCodec.createByCodecName(codecName);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void send(OutputStream out, ByteBuffer buf) throws Exception {
        if (buf == null) return;
        buf.position(0);
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        out.write(intToByteArray(data.length));
        out.write(data);
        out.flush();
    }

    private void send(OutputStream out, ByteBuffer buf, int size) throws Exception {
        if (buf == null || size <= 0) return;
        buf.position(0);
        byte[] data = new byte[size];
        buf.get(data, 0, size);
        out.write(intToByteArray(size));
        out.write(data);
        out.flush();
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

        if (streamingThread != null) {
            try {
                streamingThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            streamingThread = null;
        }

        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception e) {
                Log.e("ENCODER", "Stop failed: " + e.getMessage());
            }
            try {
                encoder.release();
            } catch (Exception e) {
                Log.e("ENCODER", "Release failed: " + e.getMessage());
            }
            encoder = null;
        }

        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        runOnUiThread(() -> btnStream.setText("Start"));
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStreaming();
    }
}

