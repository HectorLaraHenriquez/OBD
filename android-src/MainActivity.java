package cl.hector.tablero.obd;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SppPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
