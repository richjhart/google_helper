package com.rjhartsoftware.googlehelper.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.rjhartsoftware.fragments.FragmentTransactions;
import com.rjhartsoftware.googlehelper.GoogleHelper;

public class MainFragment extends Fragment implements View.OnClickListener, GoogleHelper.PurchaseStatusChangeListener {
    public static final String TAG = "_main";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_main, container, false);

        v.findViewById(R.id.main_settings).setOnClickListener(this);
        v.findViewById(R.id.main_consume).setOnClickListener(this);
        v.findViewById(R.id.main_clear_consent).setOnClickListener(this);

        GoogleHelper.getInstance().registerPurchaseChangeListener(this);
        updateStatus(v);

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateStatus(null);
    }

    @Override
    public void onDestroyView() {
        GoogleHelper.getInstance().unregisterPurchaseChangeListener(this);
        super.onDestroyView();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.main_settings:
                FragmentTransactions
                        .beginTransaction((AppCompatActivity) getActivity())
                        .replace(R.id.main_fragment, new FragmentSettings(), FragmentSettings.TAG)
                        .addToBackStack(FragmentSettings.TAG)
                        .commit();
                break;
            case R.id.main_consume:
                GoogleHelper.getInstance().consumePurchase("purchase_1");
                GoogleHelper.getInstance().consumePurchase("purchase_2");
                GoogleHelper.getInstance().consumePurchase("purchase_3");
                GoogleHelper.getInstance().consumePurchase("purchase_4");
                GoogleHelper.getInstance().consumePurchase("purchase_5");
                break;
            case R.id.main_clear_consent:
                GoogleHelper.getInstance().clearConsent();
                break;
        }

    }

    @Override
    public void PurchaseStatusChanged() {
        updateStatus(null);
    }

    private void updateStatus(View view) {
        if (view == null) {
            view = getView();
        }
        if (view == null) {
            return;
        }
        ((CheckBox)view.findViewById(R.id.main_purchase_status_1)).setChecked(
                GoogleHelper.getInstance().getPurchaseStatus("purchase_1", true) == GoogleHelper.PURCHASE_ENABLED
        );
        ((CheckBox)view.findViewById(R.id.main_purchase_status_2)).setChecked(
                GoogleHelper.getInstance().getPurchaseStatus("purchase_2", true) == GoogleHelper.PURCHASE_ENABLED
        );
        ((CheckBox)view.findViewById(R.id.main_purchase_status_3)).setChecked(
                GoogleHelper.getInstance().getPurchaseStatus("purchase_3", true) == GoogleHelper.PURCHASE_ENABLED
        );
        @GoogleHelper.PurchaseStatus int p4 = GoogleHelper.getInstance().getPurchaseStatus("purchase_4", true);
        ((CheckBox)view.findViewById(R.id.main_purchase_status_4)).setChecked(
                 GoogleHelper.PURCHASE_ENABLED == p4
        );
        ((CheckBox)view.findViewById(R.id.main_purchase_status_5)).setChecked(
                GoogleHelper.getInstance().getPurchaseStatus("purchase_5", true) != GoogleHelper.PURCHASE_DISABLED
        );
    }
}
