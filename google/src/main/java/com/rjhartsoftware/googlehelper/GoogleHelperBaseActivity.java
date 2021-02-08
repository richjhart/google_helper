package com.rjhartsoftware.googlehelper;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;

import com.rjhartsoftware.fragments.TransactionsActivity;

public class GoogleHelperBaseActivity extends TransactionsActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GoogleHelper.getInstance().registerActivity(this);
    }

    private boolean mHasHoldingView = false;
    private boolean mHasAdView = false;

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);

        View hide_ui = findViewById(R.id.google_hide_ui);
        if (hide_ui != null) {
            GoogleHelper.getInstance().setHoldingView(hide_ui);
            mHasHoldingView = true;
        }
        View ad_view = findViewById(R.id.google_ad_view);
        if (ad_view != null) {
            GoogleHelper.getInstance().registerAdView(ad_view);
            mHasAdView = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mHasAdView) {
            GoogleHelper.getInstance().resumeAd();
        }
    }

    @Override
    protected void onPause() {
        if (mHasAdView) {
            GoogleHelper.getInstance().pauseAd();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mHasHoldingView) {
            GoogleHelper.getInstance().releaseHoldingView();
        }
        if (mHasAdView) {
            GoogleHelper.getInstance().unregisterAdView();
        }
        GoogleHelper.getInstance().unregisterActivity();
        super.onDestroy();
    }
}
