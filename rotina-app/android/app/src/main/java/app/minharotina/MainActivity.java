package app.minharotina;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RotinaPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
