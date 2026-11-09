package cl.hector.tablero.obd;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.InputStream;
import java.util.UUID;

/** Bluetooth clásico (SPP / RFCOMM) para adaptadores ELM327 vinculados en Android. */
@CapacitorPlugin(
    name = "Spp",
    permissions = {@Permission(strings = {Manifest.permission.POST_NOTIFICATIONS}, alias = "notifications")}
)
public class SppPlugin extends Plugin {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private volatile BluetoothSocket socket;
    private volatile boolean open = false;

    @SuppressLint("MissingPermission")
    private BluetoothSocket tryOpen(BluetoothDevice dev) throws Exception {
        Exception last = null;
        BluetoothSocket s = null;
        try {
            s = dev.createRfcommSocketToServiceRecord(SPP);
            s.connect();
            return s;
        } catch (Exception e) {
            last = e;
            closeQuiet(s);
        }
        try {
            s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
            s.connect();
            return s;
        } catch (Exception e) {
            last = e;
            closeQuiet(s);
        }
        try {
            s = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
            s.connect();
            return s;
        } catch (Exception e) {
            last = e;
            closeQuiet(s);
        }
        throw last;
    }

    private void closeQuiet(BluetoothSocket s) {
        try {
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
    }

    private void closeAll() {
        open = false;
        closeQuiet(socket);
        socket = null;
    }

    @SuppressLint("MissingPermission")
    @PluginMethod
    public void connect(final PluginCall call) {
        final String address = call.getString("address");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                    if (ad == null) throw new Exception("Este teléfono no tiene Bluetooth");
                    if (!ad.isEnabled()) throw new Exception("El Bluetooth está apagado");
                    closeAll();
                    try {
                        ad.cancelDiscovery();
                    } catch (Exception ignored) {
                    }
                    BluetoothDevice dev = ad.getRemoteDevice(address);
                    final BluetoothSocket sock = tryOpen(dev);
                    socket = sock;
                    open = true;
                    final InputStream in = sock.getInputStream();
                    Thread reader = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            byte[] buf = new byte[512];
                            try {
                                while (open) {
                                    int n = in.read(buf);
                                    if (n < 0) break;
                                    if (n > 0) {
                                        JSObject o = new JSObject();
                                        o.put("value", new String(buf, 0, n, "ISO-8859-1"));
                                        notifyListeners("data", o);
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                            if (open) {
                                open = false;
                                notifyListeners("disconnected", new JSObject());
                            }
                        }
                    });
                    reader.start();
                    call.resolve();
                } catch (Exception e) {
                    call.reject("SPP " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }).start();
    }

    @PluginMethod
    public void write(PluginCall call) {
        try {
            BluetoothSocket s = socket;
            if (s == null) throw new Exception("sin conexión");
            String v = call.getString("value");
            s.getOutputStream().write(v.getBytes("ISO-8859-1"));
            s.getOutputStream().flush();
            call.resolve();
        } catch (Exception e) {
            call.reject("write: " + e.getMessage());
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        closeAll();
        call.resolve();
    }

    /** Servicio en primer plano: mantiene viva la app (y la grabación) con la pantalla apagada. */
    @PluginMethod
    public void startService(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "notifCallback");
            return;
        }
        doStart(call);
    }

    @PermissionCallback
    private void notifCallback(PluginCall call) {
        doStart(call);
    }

    private void doStart(PluginCall call) {
        try {
            ContextCompat.startForegroundService(getContext(), new Intent(getContext(), ObdService.class));
            call.resolve();
        } catch (Exception e) {
            call.reject("servicio: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopService(PluginCall call) {
        try {
            getContext().stopService(new Intent(getContext(), ObdService.class));
            call.resolve();
        } catch (Exception e) {
            call.reject("servicio: " + e.getMessage());
        }
    }

    /** Mantiene la pantalla encendida mientras la app está visible. */
    @PluginMethod
    public void keepScreenOn(final PluginCall call) {
        Boolean b = call.getBoolean("on", true);
        final boolean on = b == null || b;
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (on) {
                    getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                call.resolve();
            }
        });
    }

    // ---------------- Burbuja flotante (sobre otras apps) ----------------
    private WindowManager wm;
    private LinearLayout bubble;
    private TextView b1, b2, b3;
    private GradientDrawable bubbleBg;
    private WindowManager.LayoutParams bubbleLp;
    private boolean overlayWanted = false;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getContext().getResources().getDisplayMetrics());
    }

    private boolean canDraw() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext());
    }

    private TextView makeText(Context c, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    @PluginMethod
    public void overlayCanDraw(PluginCall call) {
        JSObject o = new JSObject();
        o.put("value", canDraw());
        call.resolve(o);
    }

    @PluginMethod
    public void overlayRequest(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("ajustes: " + e.getMessage());
        }
    }

    @PluginMethod
    public void overlayEnable(PluginCall call) {
        Boolean b = call.getBoolean("on", false);
        overlayWanted = b != null && b;
        if (!overlayWanted) hideBubble();
        call.resolve();
    }

    @PluginMethod
    public void overlayUpdate(PluginCall call) {
        final String l1 = call.getString("l1", "");
        final String l2 = call.getString("l2", "");
        final String l3 = call.getString("l3", "");
        final String st = call.getString("state", "");
        call.resolve();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (bubble == null) return;
                b1.setText(l1);
                b2.setText(l2);
                b3.setText(l3);
                int c = 0xFF2C3644;
                if ("bad".equals(st)) c = 0xFFEF5B5B;
                else if ("warn".equals(st)) c = 0xFFF08A3C;
                bubbleBg.setStroke(dp(2), c);
            }
        });
    }

    private void openApp() {
        try {
            Intent i = getContext().getPackageManager().getLaunchIntentForPackage(getContext().getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                getContext().startActivity(i);
            }
        } catch (Exception ignored) {
        }
    }

    private void showBubble() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (bubble != null || !canDraw()) return;
                try {
                    Context c = getContext().getApplicationContext();
                    wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                    LinearLayout ll = new LinearLayout(c);
                    ll.setOrientation(LinearLayout.VERTICAL);
                    ll.setPadding(dp(10), dp(6), dp(10), dp(6));
                    bubbleBg = new GradientDrawable();
                    bubbleBg.setColor(0xDD11151B);
                    bubbleBg.setCornerRadius(dp(12));
                    bubbleBg.setStroke(dp(2), 0xFF2C3644);
                    ll.setBackground(bubbleBg);
                    b1 = makeText(c, 18, 0xFFF2A93B, true);
                    b2 = makeText(c, 13, 0xFFE7EAF0, false);
                    b3 = makeText(c, 12, 0xFF8C96A6, false);
                    b1.setText("Tablero OBD");
                    b2.setText("esperando datos…");
                    b3.setText("");
                    ll.addView(b1);
                    ll.addView(b2);
                    ll.addView(b3);
                    int type = Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    bubbleLp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT);
                    bubbleLp.gravity = Gravity.TOP | Gravity.START;
                    bubbleLp.x = dp(8);
                    bubbleLp.y = dp(140);
                    ll.setOnTouchListener(new View.OnTouchListener() {
                        private float sx, sy;
                        private int ox, oy;
                        private boolean moved;

                        @Override
                        public boolean onTouch(View v, MotionEvent e) {
                            int a = e.getAction();
                            if (a == MotionEvent.ACTION_DOWN) {
                                sx = e.getRawX();
                                sy = e.getRawY();
                                ox = bubbleLp.x;
                                oy = bubbleLp.y;
                                moved = false;
                                return true;
                            } else if (a == MotionEvent.ACTION_MOVE) {
                                float dx = e.getRawX() - sx;
                                float dy = e.getRawY() - sy;
                                if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                                bubbleLp.x = ox + (int) dx;
                                bubbleLp.y = oy + (int) dy;
                                try {
                                    wm.updateViewLayout(bubble, bubbleLp);
                                } catch (Exception ignored) {
                                }
                                return true;
                            } else if (a == MotionEvent.ACTION_UP) {
                                if (!moved) openApp();
                                return true;
                            }
                            return false;
                        }
                    });
                    wm.addView(ll, bubbleLp);
                    bubble = ll;
                } catch (Exception e) {
                    bubble = null;
                }
            }
        });
    }

    private void hideBubble() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (bubble != null) {
                    try {
                        wm.removeView(bubble);
                    } catch (Exception ignored) {
                    }
                    bubble = null;
                }
            }
        });
    }

    @Override
    protected void handleOnPause() {
        if (overlayWanted) showBubble();
    }

    @Override
    protected void handleOnResume() {
        hideBubble();
    }

    @Override
    protected void handleOnDestroy() {
        hideBubble();
    }
}
