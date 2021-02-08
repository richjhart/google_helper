package com.rjhartsoftware.googlehelper;

import android.os.Build;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;

import java.util.ArrayList;
import java.util.List;

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

            PreferenceCategory pro_section = this.findPreference(getResources().getString(R.string.settings_key_google));
            if (pro_section != null) {
                List<Preference> purchase_prefs = new ArrayList<>();
                Preference gdpr_pref = null;
                for (int i = 0; i < pro_section.getPreferenceCount(); i++) {
                    Preference pref = pro_section.getPreference(i);
                    if (pref.getKey().equals(getContext().getString(R.string.settings_key_gdpr))) {
                        gdpr_pref = pref;
                    } else {
                        purchase_prefs.add(pref);
                    }
                }
                if (purchase_prefs.size() > 0) {
                    GoogleHelper.getInstance().registerProSettings(purchase_prefs.toArray(new Preference[] {}), gdpr_pref);
                }
            }
        }
    }

    @Override
    public void onDestroyView() {
        GoogleHelper.getInstance().unregisterProSettings();
        super.onDestroyView();
    }
}
