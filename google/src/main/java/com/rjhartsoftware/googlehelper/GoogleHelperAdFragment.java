package com.rjhartsoftware.googlehelper;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class GoogleHelperAdFragment extends Fragment {

    private boolean mHasAdView = false;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        View ad_view = view.findViewById(R.id.google_ad_view);
        if (ad_view != null) {
            GoogleHelper.getInstance().registerAdView(ad_view);
            mHasAdView = true;
        }

    }

    @Override
    public void onResume() {
        super.onResume();
        if (mHasAdView) {
            GoogleHelper.getInstance().resumeAd();
        }
    }

    @Override
    public void onPause() {
        if (mHasAdView) {
            GoogleHelper.getInstance().pauseAd();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (mHasAdView) {
            GoogleHelper.getInstance().unregisterAdView();
        }
        super.onDestroyView();
    }
}
