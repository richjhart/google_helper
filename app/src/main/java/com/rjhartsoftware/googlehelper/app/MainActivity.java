package com.rjhartsoftware.googlehelper.app;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import android.os.Bundle;
import android.view.View;

import com.google.android.gms.ads.MobileAds;
import com.rjhartsoftware.fragments.FragmentTransactions;
import com.rjhartsoftware.googlehelper.GoogleHelper;
import com.rjhartsoftware.logcatdebug.D;

public class MainActivity extends AppCompatActivity {

    private static final String BILLING_PUBLIC_KEY =
            "KfgQX+uJRatBvR83DNowWpuUNKdCizEAJfAWXuOyPKROkz0EJ9YSXuOyQaR3liQKBeUXVpazVZ9RgzoRFMF2Ld" +
                    "fSftRPohwjM/t2du3WfYhTojoTDoMcetavM6RrghN/CuBtVZS3Ztxq7xIBUtUhWdWnXdZh7TgHM+AR" +
                    "RPGrS5xzvC4rM4URb9SlYLdVkTcfEekAWNuZT5RtuEcCXJ5qLpusaI5C7icOFcgsTMTSK9xOkSR3E8" +
                    "EvJPaHM5JTowYeFd92S8SBaqhvjhAgC8VvbcmMMIY0iDUlU9oTWYm5XohziQVtL9gbT8CSQIRlmBw/" +
                    "VdkzWODRaqx3vlsyPPYYMti1UdAwijYUB98Uf5WiK4tqtBwnK8tuSeWrUrNSnSN2FoISUemQSbBOmD" +
                    "13NPgOLe2IZd1ziBEQE8kUZ9qPK4JHjhF+L4QcVu+LPZZhsxcyMfYYSsnUV5JskBwWAfQVU8GGMId0" +
                    "sxEvDv0hVNqWMdFZ6BMsFfsgZPSUTZQsti0QPMYQWeOyRac=";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        D.init(BuildConfig.VERSION_NAME, BuildConfig.DEBUG);
        MobileAds.initialize(this, "ca-app-pub-4750446129557325~1422975651");
        super.onCreate(savedInstanceState);
        GoogleHelper
                .init(this, BILLING_PUBLIC_KEY)
                .addPurchaseInfo("purchase_1", "purchase_2", "purchase_4", "purchase_5")
                .addPurchaseInfo("purchase_2", "purchase_5")
                .addPurchaseInfo("purchase_3", "purchase_5")
                .addPurchaseInfo("purchase_4", "purchase_5")
                .addPurchaseInfo("purchase_5")
                .setConsentPurchase("purchase_2")
                .setRemovesAds("purchase_1")
                .start();

        setContentView(R.layout.activity_main);
        FragmentTransactions.activityCreated(this);
        GoogleHelper.getInstance().registerActivity(this);
        GoogleHelper.getInstance().setHoldingView(findViewById(R.id.main_hide_ui));
        GoogleHelper.getInstance().registerAdView(findViewById(R.id.adView));

        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment == null) {
            FragmentTransactions
                    .beginTransaction(this)
                    .add(R.id.main_fragment, new MainFragment(), MainFragment.TAG)
                    .commit();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        FragmentTransactions.activityStarted(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        FragmentTransactions.activityResumed(this);
        GoogleHelper.getInstance().resumeAd();
    }


    @Override
    protected void onPause() {
        GoogleHelper.getInstance().pauseAd();
        FragmentTransactions.activityPaused(this);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        FragmentTransactions.activitySaved(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        FragmentTransactions.activityStopped(this);
    }

    @Override
    protected void onDestroy() {
        GoogleHelper.getInstance().unregisterAdView();
        GoogleHelper.getInstance().releaseHoldingView();
        GoogleHelper.getInstance().unregisterActivity();
        GoogleHelper.destroy(this);
        FragmentTransactions.activityDestroyed(this);
        super.onDestroy();
    }
}
