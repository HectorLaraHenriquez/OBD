package cl.hector.tablero.obd;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import android.content.Intent;
import android.os.Build;
import android.view.WindowManager;

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
}
