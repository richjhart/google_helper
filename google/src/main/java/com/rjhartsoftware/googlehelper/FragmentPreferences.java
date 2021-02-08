package com.rjhartsoftware.googlehelper;

import android.os.Build;
import android.os.Bundle;

import androidx.preference.PreferenceFragmentCompat;

public class FragmentPreferences extends PreferenceFragmentCompat {

    public static final String TAG = "_frag_settings";

    public FragmentPreferences() {
        // Required empty public constructor
    }

    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        setPreferencesFromResource(R.xml.preferences, s);

        if (getContext() != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
                this.findPreference(getResources().getString(
                        R.string.settings_theme_key)).setVisible(false);
            }
        }
    }

}
