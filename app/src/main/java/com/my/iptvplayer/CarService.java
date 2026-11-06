package com.my.iptvplayer;

import android.os.Bundle;
import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarActivityService;

public class CarService extends CarActivityService {

    @Override
    public Class<? extends CarActivity> getCarActivity() {
        return MainCarActivity.class;
    }

    public static class MainCarActivity extends CarActivity {
        private IptvSessionController controller;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            controller = new IptvSessionController(this, true, null, null);
            setContentView(controller.getRootView());
        }

        @Override
        public void onResume() {
            super.onResume();
            if (controller != null) {
                controller.syncFromStore();
            }
        }

        @Override
        public void onDestroy() {
            if (controller != null) {
                controller.release();
                controller = null;
            }
            super.onDestroy();
        }
    }
}
