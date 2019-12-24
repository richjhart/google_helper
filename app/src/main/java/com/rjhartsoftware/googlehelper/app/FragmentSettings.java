package com.rjhartsoftware.googlehelper.app;


import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.rjhartsoftware.googlehelper.GoogleHelper;

public class FragmentSettings extends PreferenceFragmentCompat {

    public static final String TAG = "_frag_settings";

    public FragmentSettings() {
        // Required empty public constructor
    }

    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        setPreferencesFromResource(R.xml.preferences, s);

        if (getContext() != null) {
            Preference p1 = findPreference("purchase_1");
            Preference p2 = findPreference("purchase_2");
            Preference p3 = findPreference("purchase_3");
            Preference p4 = findPreference("purchase_4");
            Preference p5 = findPreference("purchase_5");
            Preference gdpr = findPreference("settings_gdpr");
            GoogleHelper.getInstance().registerProSettings(new Preference[]{p1, p2, p3, p4, p5}, gdpr);
        }
    }

    @Override
    public void onDestroyView() {
        GoogleHelper.getInstance().unregisterProSettings();

        super.onDestroyView();
    }

}
