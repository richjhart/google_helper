package com.rjhartsoftware.googlehelper;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.crashlytics.android.Crashlytics;
import com.google.ads.consent.ConsentForm;
import com.google.ads.consent.ConsentFormListener;
import com.google.ads.consent.ConsentInfoUpdateListener;
import com.google.ads.consent.ConsentInformation;
import com.google.ads.consent.ConsentStatus;
import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.rjhartsoftware.logcatdebug.D;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class GoogleHelper {
    // TODO pending transactions
    // TODO handle remote config?
    // region Initialisation and Constants
    private static final String SETTINGS_KEY_PURCHASE = "_ps.";
    private static final String SETTINGS_KEY_ADS = "_a";

    private final BroadcastReceiver mNetworkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            log(GENERAL, "Network changed - check connectivity"); //NON-NLS
            maybeResetAdsStatus();
            start();
        }
    };

    private static final GoogleHelper sInstance = new GoogleHelper();
    private Application mContext;
    private WeakReference<Activity> mActivity = new WeakReference<>(null);

    private GoogleHelper() {
    }

    public static GoogleHelper getInstance() {
        return sInstance;
    }

    public static GoogleHelper init(@NonNull Context context, String billingKey) {
        sInstance.initO(context);
        sInstance.setBillingPublicKey(billingKey);
        return sInstance;
    }

    public static void destroy(@Nullable Context context) {
        try {
            if (context != null) {
                context.unregisterReceiver(sInstance.mNetworkReceiver);
            }
        } catch (Exception ignore) {

        }
    }

    public void registerActivity(Activity activity) {
        mActivity = new WeakReference<>(activity);
    }

    public void unregisterActivity() {
        mActivity = new WeakReference<>(null);
        mConsentFormShowing = false;
    }

    public GoogleHelper addPurchaseInfo(String key, String... otherKeys) {
        mPurchaseInfo.put(key, new PurchaseInfo(key, otherKeys));
        return this;
    }

    public GoogleHelper setConsentPurchase(String key) {
        //noinspection ConstantConditions
        mPurchaseInfo.get(key).consentPurchase = true;
        return this;
    }

    @SuppressWarnings("ConstantConditions")
    public GoogleHelper setRemovesAds(String key, String publisher) {
        MobileAds.initialize(mContext, publisher);
        mPurchaseInfo.get(key).removesAds = true;
        for (String otherKey : mPurchaseInfo.get(key).otherKeys) {
            mPurchaseInfo.get(otherKey).removesAds = true;
        }
        return this;
    }

    private void initO(@NonNull Context context) {
        log(GENERAL, "Attaching to context");
        mContext = (Application) context.getApplicationContext();
        IntentFilter connFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(mNetworkReceiver, connFilter);
        for (String device : TEST_DEVICES) {
            ConsentInformation.getInstance(context).addTestDevice(device);
        }
        // ConsentInformation.getInstance(context).setDebugGeography(DebugGeography.DEBUG_GEOGRAPHY_EEA);

        mBillingClient = BillingClient.newBuilder(context)
                .enablePendingPurchases()
                .setListener(new PurchasesUpdatedListenerCustom())
                .build();

        updateMainStatus();
        checkAdConsent();
    }

    public void start() {
        log(GENERAL, "'Starting'");
        initialiseBilling();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        if (mAdsStatus == STATUS_ADS_INIT) {
            D.log(ADS, "Startup - initialising ads status to saved value");
            setAdsStatus(prefs.getInt(SETTINGS_KEY_ADS, STATUS_ADS_NOTHING));
        }
        for (PurchaseInfo info : mPurchaseInfo.values()) {
            if (info.status == INT_STATUS_PURCHASE_INIT) {
                //noinspection StringConcatenation
                D.log(BILLING, "Startup - initialising purchase status of %s to saved value", info.key);
                setPurchaseStatus(info.key, prefs.getInt(SETTINGS_KEY_PURCHASE + info.key.hashCode(), INT_STATUS_PURCHASE_NOTHING));
            }
        }

        updateMainStatus();
    }
    // endregion

    //region Analytics and Firebase
    private static final D.DebugTag GENERAL = new D.DebugTag("google_general");
    private static final D.DebugTag ANALYTICS = new D.DebugTag("google_analytics");
    private static final D.DebugTag BILLING = new D.DebugTag("google_billing");
    private static final D.DebugTag EU_CONSENT = new D.DebugTag("google_consent");
    private static final D.DebugTag ADS = new D.DebugTag("google_ads");
    private static final D.DebugTag FIRESTORE = new D.DebugTag("google_firestore");
    private static final D.DebugTag VERBOSE = new D.DebugTag("google_verbose", false);

    private static DocumentReference sVerboseDoc = null;

    private static final int CONSENT_FORM_RETURNED_UNKNOWN = 2001;
    private static final int CONSENT_ERROR_LOADING_PRIVACY_POLICY = 2002;
    private static final int CONSENT_PERSONALISED = 2003;
    private static final int CONSENT_ANONYMOUS = 2004;
    private static final int CONSENT_NON_EU = 2005;
    private static final int CONSENT_TO_BE_REQUESTED = 2006;
    private static final int CONSENT_FAILED_TO_GET_STATUS = 2007;
    private static final int CONSENT_RESET = 2008;
    private static final int CONSENT_SHOWING_CONSENT = 2009;
    private static final int CONSENT_REQUESTED_PAID = 2010;
    private static final int CONSENT_SETTING_PERSONALISED = 2011;
    private static final int CONSENT_SETTING_ANONYMOUS = 2012;
    private static final int CONSENT_FORM_ERROR = 2013;
    private static final int CONSENT_FORM_EXCEPTION = 2014;
    private static final int CONSENT_FORM_NO_ACTIVITY = 2015;
    private static final int CONSENT_ERROR_ASKED_WHILE_WAITING_FOR_PURCHASE = 2016;
    private static final int CONSENT_ERROR_MULTIPLE_PURCHASING_STATUS = 2017;
    private static final int CONSENT_ERROR_ASKED_WITH_UNKNOWN_STATUS = 2018;
    private static final int CONSENT_ERROR_ASKED_WITH_PURCHASE_ON = 2019;

    private static final int STATS_AD_FAILED_TO_LOAD = 3001;
    private static final int STATS_AD_LOADED = 3002;

    private static final int BILLING_SETUP_ERROR_BASE = 5100;
    private static final int BILLING_PURCHASE_START_ERROR_BASE = 5200;
    private static final int BILLING_QUERY_ERROR_BASE = 5300;
    private static final int BILLING_PURCHASE_UPDATE_ERROR_BASE = 5400;
    private static final int BILLING_SETUP_DISCONNECTED = 5501;
    private static final int BILLING_VERIFY_ERROR_NO_SIGNED_DATA = 5503;
    private static final int BILLING_VERIFY_ERROR_NO_SIGNATURE = 5504;
    private static final int BILLING_VERIFY_ERROR_NO_SUCH_ALGORITHM = 5505;
    private static final int BILLING_VERIFY_ERROR_INVALID_KEY_SPEC = 5506;
    private static final int BILLING_VERIFY_ERROR_BASE64_DECODING = 5507;
    private static final int BILLING_VERIFY_ERROR_VERIFY_FAILED = 5508;
    private static final int BILLING_VERIFY_ERROR_NO_SUCH_ALGORITHM_2 = 5509;
    private static final int BILLING_VERIFY_ERROR_INVALID_KEY = 5510;
    private static final int BILLING_VERIFY_ERROR_SIGNATURE = 5511;
    private static final int BILLING_QUERY_PRODUCT_NOT_FOUND = 5512;
    private static final int BILLING_PURCHASE_UPDATE_USER_CANCELLED = 5513;
    private static final int BILLING_QUERY_PRODUCT_ERROR_BASE = 5600;
    private static final int BILLING_PURCHASE_ACK_OK = 5514;
    private static final int BILLING_PURCHASE_ACK_ERROR = 5514;

    public final static int ANALYTICS_STATUS_EVENT = 1;
    public final static int ANALYTICS_STATUS_WARNING = 2;
    public final static int ANALYTICS_STATUS_ERROR = 3;

    @IntDef(
            value = {
                    ANALYTICS_STATUS_EVENT,
                    ANALYTICS_STATUS_WARNING,
                    ANALYTICS_STATUS_ERROR
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface DebugInfoReason {
    }

    public static void reportDebugInfo(int code, @DebugInfoReason int reason, Integer extraCode, Object extraData) {
        reportDebugInfo('g', code, reason, (long)extraCode, extraData);
    }

    public static void reportDebugInfo(int code, @DebugInfoReason int reason, Long extraCode, Object extraData) {
        reportDebugInfo('g', code, reason, extraCode, extraData);
    }

    private static void reportDebugInfo(char type, int code, @DebugInfoReason int reason, Long extraCode, Object extraData) {
        D.log(ANALYTICS, "Reporting debug info: " + code); //NON-NLS
        Bundle details = new Bundle();
        char c_reason = '-';
        switch (reason) {
            case ANALYTICS_STATUS_ERROR:
                c_reason = 'e';
                break;
            case ANALYTICS_STATUS_EVENT:
                c_reason = '-';
                break;
            case ANALYTICS_STATUS_WARNING:
                c_reason = 'w';
                break;
        }
        details.putString("code", String.format(Locale.US, "%c.%c.%d", type, c_reason, code)); //NON-NLS
        if (extraCode != null) {
            details.putString("extraCode", String.format(Locale.US, "%c.%c.%d.%d", type, c_reason, code, extraCode)); //NON-NLS
        }
        if (extraData != null) {
            details.putString("extraData", extraData.toString()); //NON-NLS
        }
        FirebaseAnalytics.getInstance(sInstance.mContext).logEvent("internal_info", details); //NON-NLS
    }

    public static void store(String collection, Map<String, Object> details) {
        try {
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            details.put("device_model", Build.MODEL); //NON-NLS
            details.put("device_brand", Build.BRAND); //NON-NLS
            details.put("device_manufacturer", Build.MANUFACTURER); //NON-NLS
            details.put("device_product", Build.PRODUCT); //NON-NLS
            details.put("device_sdk", String.valueOf(Build.VERSION.SDK_INT)); //NON-NLS
            details.put("app_build", BuildConfig.VERSION_CODE); //NON-NLS
            details.put("app_version", BuildConfig.VERSION_NAME); //NON-NLS
            db.collection(collection)
                    .add(details)
                    .addOnSuccessListener(new OnSuccessListener<DocumentReference>() {
                        @Override
                        public void onSuccess(DocumentReference documentReference) {
                            D.log(FIRESTORE, "Success writing to firestore");
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            D.warn(FIRESTORE, "Error writing to firestore: " + e);
                        }
                    });
        } catch (Exception e) {
            D.error(FIRESTORE, "Error writing to firestore: " + e);
        }
    }

    private static void log(D.DebugTag tag, String entry) {
        D.log(tag.indirect(), entry);
        if (D.isLogging(VERBOSE)) {
            try {
                FirebaseFirestore db = FirebaseFirestore.getInstance();
                HashMap<String, Object> details = new HashMap<>();
                if (sVerboseDoc == null) {
                    details.put("device_model", Build.MODEL); //NON-NLS
                    details.put("device_brand", Build.BRAND); //NON-NLS
                    details.put("device_manufacturer", Build.MANUFACTURER); //NON-NLS
                    details.put("device_product", Build.PRODUCT); //NON-NLS
                    details.put("device_sdk", String.valueOf(Build.VERSION.SDK_INT)); //NON-NLS
                    details.put("app_build", BuildConfig.VERSION_CODE); //NON-NLS
                    details.put("app_version", BuildConfig.VERSION_NAME); //NON-NLS
                    sVerboseDoc = db.collection("debug_verbose_log").document(String.valueOf(System.currentTimeMillis()));
                    sVerboseDoc.set(details)
                            .addOnSuccessListener(new OnSuccessListener<Void>() {
                                @Override
                                public void onSuccess(Void aVoid) {
                                    D.log(VERBOSE, "Created - success");
                                }
                            })
                            .addOnFailureListener(new OnFailureListener() {
                                @Override
                                public void onFailure(@NonNull Exception e) {
                                    D.warn(VERBOSE, "Created - failed", e);
                                }
                            });
                }
                details.clear();
//                HashMap<String, Object> entryMap = new HashMap<>();
//                entryMap.put("time", System.currentTimeMillis());
//                entryMap.put("value", entry);
                details.put("log", FieldValue.arrayUnion(entry));
                sVerboseDoc.update(details)
                        .addOnSuccessListener(new OnSuccessListener<Void>() {
                            @Override
                            public void onSuccess(Void aVoid) {
                                D.log(VERBOSE, "Complete - success");
                            }
                        })
                        .addOnFailureListener(new OnFailureListener() {
                            @Override
                            public void onFailure(@NonNull Exception e) {
                                D.warn(VERBOSE, "Complete - failed", e);
                            }
                        });
            } catch (Exception e) {
                D.error(VERBOSE, "Error writing to firestore: " + e);
            }

        }
    }

    //endregion

    //region Main Status and App Visibility
    @IntDef(
            value = {
                    STATUS_WAITING,
                    STATUS_REQUEST_PAID,
                    STATUS_HIDE_ADS,
                    STATUS_SHOW_ALL_ADS,
                    STATUS_SHOW_ANONYMOUS_ADS
            })
    @Retention(RetentionPolicy.SOURCE)
    private @interface Status {
    }

    private static String getStatus(@Status int status) {
        switch (status) {
            case STATUS_HIDE_ADS:
                return "Hide Ads";
            case STATUS_REQUEST_PAID:
                return "Requesting Paid";
            case STATUS_SHOW_ALL_ADS:
                return "Show All";
            case STATUS_SHOW_ANONYMOUS_ADS:
                return "Show Anonymous";
            case STATUS_WAITING:
                return "Waiting for status";
        }
        return "N/A";
    }

    private static final int STATUS_WAITING = 0;
    private static final int STATUS_REQUEST_PAID = 1;
    private static final int STATUS_HIDE_ADS = 2;
    private static final int STATUS_SHOW_ALL_ADS = 3;
    private static final int STATUS_SHOW_ANONYMOUS_ADS = 4;
    @Status
    private int mOverallStatus = STATUS_WAITING;
    private View mHoldingView = null;

    public static final int PURCHASE_ENABLED = 0;
    public static final int PURCHASE_DISABLED = 1;

    @IntDef(
            value = {
                    PURCHASE_ENABLED,
                    PURCHASE_DISABLED,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PurchaseStatus {
    }

    public void setHoldingView(View waitView) {
        log(EU_CONSENT, "Setting holding view");
        mHoldingView = waitView;
        updateOverallVisibility();
        if (mAdsStatus == STATUS_ADS_REQUESTING_CONSENT) {
            log(EU_CONSENT, "Requesting consent (from registering holding view");
            requestConsent();
        }
    }

    public void releaseHoldingView() {
        log(EU_CONSENT, "Releasing holding view");
        mHoldingView = null;
    }

    private void setStatus(@Status int status) {
        log(GENERAL, String.format("Updating overall status from %s to %s", getStatus(mOverallStatus), getStatus(status)));
        if (status != mOverallStatus) {
            D.log(ADS, "Updating overall status from %s to %s", getStatus(mOverallStatus), getStatus(status));
            mOverallStatus = status;
        }
    }

    @PurchaseStatus
    public int getPurchaseStatus(String key, boolean benefitOfTheDoubt) {
        PurchaseInfo info = mPurchaseInfo.get(key);
        boolean allOff = true;
        assert info != null;
        switch (info.status) {
            case INT_STATUS_PURCHASE_ON:
                return PURCHASE_ENABLED;
            case INT_STATUS_PURCHASE_OFF:
                // do nothing
                break;
            case INT_STATUS_PURCHASE_INIT:
            case INT_STATUS_PURCHASE_NOTHING:
            case INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT:
            case INT_STATUS_PURCHASE_UNKNOWN:
            default:
                allOff = false;
                break;
        }
        for (String otherKey : info.otherKeys) {
            PurchaseInfo otherInfo = mPurchaseInfo.get(otherKey);
            assert otherInfo != null;
            switch (otherInfo.status) {
                case INT_STATUS_PURCHASE_ON:
                    return PURCHASE_ENABLED;
                case INT_STATUS_PURCHASE_OFF:
                    // do nothing
                    // if all off is already true, keep it that way
                    break;
                case INT_STATUS_PURCHASE_INIT:
                case INT_STATUS_PURCHASE_NOTHING:
                case INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT:
                case INT_STATUS_PURCHASE_UNKNOWN:
                    allOff = false;
                    break;
            }
        }
        if (allOff) {
            return PURCHASE_DISABLED;
        } else {
            return benefitOfTheDoubt ? PURCHASE_ENABLED : PURCHASE_DISABLED;
        }
    }

    @InternalPurchaseStatus
    private int getAdsPurchaseStatus() {
        @InternalPurchaseStatus int status = INT_STATUS_PURCHASE_NOTHING;
        boolean first = true;
        for (PurchaseInfo info : mPurchaseInfo.values()) {
            if (first) {
                status = info.status;
                first = false;
            } else {
                if (info.removesAds) {
                    switch (status) {
                        case INT_STATUS_PURCHASE_OFF:
                            switch (info.status) {
                                case INT_STATUS_PURCHASE_NOTHING:// waiting for one, we can keep waiting
                                case INT_STATUS_PURCHASE_INIT:// waiting for one, we can keep waiting
                                case INT_STATUS_PURCHASE_OFF: // both (all) are off. Stay off
                                case INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT: // high priority - set to purchasing
                                case INT_STATUS_PURCHASE_UNKNOWN: // unknown - give the benefit of the doubt (hide ads)
                                case INT_STATUS_PURCHASE_ON: // on - high priority
                                    status = info.status;
                                    break;
                            }
                            break;
                        case INT_STATUS_PURCHASE_NOTHING:
                        case INT_STATUS_PURCHASE_INIT:
                            switch (info.status) {
                                case INT_STATUS_PURCHASE_NOTHING: // keep waiting
                                case INT_STATUS_PURCHASE_INIT: // keep waiting
                                case INT_STATUS_PURCHASE_ON: // high priority
                                    status = info.status;
                                    break;
                                case INT_STATUS_PURCHASE_OFF:
                                    // waiting for one - keep waiting
                                    // do nothing
                                    break;
                                case INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT:
                                    reportDebugInfo('c', CONSENT_ERROR_ASKED_WHILE_WAITING_FOR_PURCHASE, ANALYTICS_STATUS_ERROR, null, null);
                                    // trust the purchasing one
                                    status = info.status;
                                    break;
                                case INT_STATUS_PURCHASE_UNKNOWN:
                                    // keep waiting
                                    // do nothing
                                    break;
                            }
                            break;
                        case INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT:
                            switch (info.status) {
                                case INT_STATUS_PURCHASE_NOTHING:
                                case INT_STATUS_PURCHASE_INIT:
                                    reportDebugInfo('c', CONSENT_ERROR_ASKED_WHILE_WAITING_FOR_PURCHASE, ANALYTICS_STATUS_ERROR, null, null);
                                    // keep purchasing
                                    // do nothing
                                    break;
                                case INT_STATUS_PURCHASE_OFF:
                                    // keep purchasing
                                    // do nothing
                                    break;
                                case INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT:
                                    // should never happen
                                    reportDebugInfo('c', CONSENT_ERROR_MULTIPLE_PURCHASING_STATUS, ANALYTICS_STATUS_ERROR, null, null);
                                    break;
                                case INT_STATUS_PURCHASE_UNKNOWN:
                                    // should never happen
                                    reportDebugInfo('c', CONSENT_ERROR_ASKED_WITH_UNKNOWN_STATUS, ANALYTICS_STATUS_ERROR, null, null);
                                    // however, purchase has started now...
                                    // do nothing
                                    break;
                                case INT_STATUS_PURCHASE_ON:
                                    // should never happen
                                    reportDebugInfo('c', CONSENT_ERROR_ASKED_WITH_PURCHASE_ON, ANALYTICS_STATUS_ERROR, null, null);
                                    // however, purchase has started now...
                                    // do nothing
                                    break;
                            }
                            break;
                        case INT_STATUS_PURCHASE_UNKNOWN:
                            switch (info.status) {
                                case INT_STATUS_PURCHASE_NOTHING:
                                case INT_STATUS_PURCHASE_INIT:
                                case INT_STATUS_PURCHASE_OFF:
                                case INT_STATUS_PURCHASE_UNKNOWN:
                                    // already shouldn't be showing ads
                                    // do nothing
                                    break;
                                case INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT:
                                    // should never happen
                                    reportDebugInfo('c', CONSENT_ERROR_ASKED_WITH_UNKNOWN_STATUS, ANALYTICS_STATUS_ERROR, null, null);
                                    // however, purchase has started now...
                                    status = info.status;
                                    break;
                                case INT_STATUS_PURCHASE_ON:
                                    // on is higher priority, might as well set it
                                    status = info.status;
                                    break;
                            }
                            break;
                        case INT_STATUS_PURCHASE_ON:
                            switch (info.status) {
                                case INT_STATUS_PURCHASE_NOTHING:
                                case INT_STATUS_PURCHASE_INIT:
                                case INT_STATUS_PURCHASE_OFF:
                                case INT_STATUS_PURCHASE_UNKNOWN:
                                case INT_STATUS_PURCHASE_ON:
                                    // on is already highest priority
                                    // do nothing
                                    break;
                                case INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT:
                                    // should never happen
                                    reportDebugInfo('c', CONSENT_ERROR_ASKED_WITH_PURCHASE_ON, ANALYTICS_STATUS_ERROR, null, null);
                                    // however, purchase has started now...
                                    status = info.status;
                            }
                            break;
                    }
                }
            }
        }
        return status;
    }

    // OFF = determined by consent status but may hide the app and/or show ads. Should only do this if sure (all are off)
    // PURCHASING_FROM_CONSENT = you are waiting for the user. if any are set, this has priority
    // NOTHING, INIT = this will hide the app, so fairly low priority
    // ON = will show the app and hide ads. Should be highest priority
    // UNKNOWN = will show the app and hide ads. Should be high priority, but not highest

    private void updateMainStatus() {
        @InternalPurchaseStatus int currentPurchaseStatus = getAdsPurchaseStatus();
        log(ADS, String.format("Updating overall status... Pro is %s. Ads is %s", getPurchaseStatusName(currentPurchaseStatus), getAdsStatusName(mAdsStatus)));
        switch (currentPurchaseStatus) {
            case INT_STATUS_PURCHASE_OFF:
                switch (mAdsStatus) {
                    case STATUS_ADS_NOTHING:
                    case STATUS_ADS_INIT:
                    case STATUS_ADS_REQUESTING_CONSENT:
                        // still waiting for consent check - hide the app for now
                        log(ADS, "Set status to waiting because we haven't checked consent yet (and pro is off)");
                        setStatus(STATUS_WAITING);
                        break;
                    case STATUS_ADS_ALLOWED_PERSONALISED:
                    case STATUS_ADS_ALLOWED_NON_EU:
                        log(ADS, "Showing ads because we're outside the EU or the user has given consent (and pro is off)");
                        setStatus(STATUS_SHOW_ALL_ADS);
                        break;
                    case STATUS_ADS_ALLOWED_ANONYMOUS:
                        log(ADS, "Showing anonymous ads because the user has allowed it (and pro is off)");
                        setStatus(STATUS_SHOW_ANONYMOUS_ADS);
                        break;
                    case STATUS_ADS_PREFER_PAID:
                        if (startPurchaseFromConsent()) {
                            log(ADS, "Waiting for the user to complete a purchase (and pro is off)");
                            setStatus(STATUS_REQUEST_PAID);
                        } else {
                            log(ADS, "Waiting because the purchase was unable to start yet - will start once available (and pro is off)");
                            setStatus(STATUS_WAITING);
                        }
                        break;
                    case STATUS_ADS_CONSENT_UNKNOWN:
                        log(ADS, "Consent status is not known - check it now");
                        checkAdConsent();
                        break;
                    case STATUS_ADS_CONSENT_FAILED:
                    case STATUS_ADS_UNKNOWN:
                    case STATUS_ADS_CONSENT_TIMED_OUT:
                    default:
                        // weren't able to get the consent status
                        // allow the app to run without ads
                        log(ADS, "Unable to get consent status (error) (and pro is off)");
                        setStatus(STATUS_HIDE_ADS);
                        break;
                }
                break;
            case INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT:
                // don't change the status
                log(ADS, "Waiting for user to finish purchase - don't change the status");
                break;
            case INT_STATUS_PURCHASE_NOTHING:
            case INT_STATUS_PURCHASE_INIT:
                // still waiting for the pro status - hide the app for now
                log(ADS, "Hide the app as we don't yet know the pro status");
                setStatus(STATUS_WAITING);
                break;
            case INT_STATUS_PURCHASE_ON:
                // never show ads in the pro version, and consent doesn't matter
                log(ADS, "Running Pro - consent doesn't matter. Hide ads");
                // deliberate fall-through
            case INT_STATUS_PURCHASE_UNKNOWN:
            default:
                log(ADS, "Pro or error getting pro - consent doesn't matter. Hide ads");
                setStatus(STATUS_HIDE_ADS);
                break;
        }
        updateAdVisibility();
        updateOverallVisibility();
        updateSettingVisibility();
    }

    private void updateOverallVisibility() {
        log(EU_CONSENT, String.format("Updating visibility of whole app... Status: %s", getStatus(mOverallStatus)));
        if (mHoldingView != null) {
            log(EU_CONSENT, "Holding view is set...");
            switch (mOverallStatus) {
                case GoogleHelper.STATUS_WAITING:
                case GoogleHelper.STATUS_REQUEST_PAID:
                    // hide the UI
                    log(EU_CONSENT, "Hiding the UI");
                    mHoldingView.setVisibility(View.VISIBLE);
                    break;
                case GoogleHelper.STATUS_HIDE_ADS:
                case GoogleHelper.STATUS_SHOW_ALL_ADS:
                case GoogleHelper.STATUS_SHOW_ANONYMOUS_ADS:
                default:
                    // show the UI
                    log(EU_CONSENT, "Showing the UI");
                    mHoldingView.setVisibility(View.GONE);
                    break;
            }
        }
    }
    //endregion

    //region Ads (And Consent)
    private static final String[] TEST_DEVICES = new String[]{
            "5F39972DBABC6E538CFB40EB92DDC26C", // TF101
            "D7CBF60667925E6FBB3EF8261D190F7A", // iRulu
            "D91D37F46105B674BE3AC908E8B45F9C", // OnePlus 5
            "E1AADDC4BD1DC95985EBE1D13213C449", // Lenovo Tablet
            "444BEDEEF8F91E966568DC77F501B0DC", // Nexus 5X
            "69EA90F05BCC277953430441647E510D", // Moto G4
            "B3C04AB901B835A4AB4918589505C09C", // OnePlus 6 (Old)
            "50A95DB0BE39AC18B27492AAA4076A3A", // OnePlus 6
            "5BD2A4C64D83D92CF61D07E1133907C8", // Nexus 5X
    };

    private AdView mAdView = null;
    private AdRequest mAdRequest = null;
    private boolean mShowingNonPersonalisedAds = false;
    private boolean mGooglePlayServicesAvailable = false;
    private boolean mRequestingAd = false;

    public void registerAdView(@Nullable View adView) {
        if (adView instanceof AdView) {
            log(ADS, "Successfully registering Ad Fragment: " + adView); //NON-NLS
            if (mAdView != adView && mAdView != null) {
                log(ADS, "Hiding previous adView"); //NON-NLS
                hideAds();
            }
            mAdView = (AdView) adView;
            updateAdVisibility();
        }
    }

    public void unregisterAdView() {
        log(ADS, "Successfully Un-registering Ad Fragment"); //NON-NLS
        hideAds();
        if (mAdView != null) {
            log(ADS, "Destroying ad view"); //NON-NLS
            mAdView.destroy();
        }
        mAdView = null;
    }

    public void pauseAd() {
        log(ADS, "pausing ad: " + (mAdView != null)); //NON-NLS
        if (mAdView != null) {
            mAdView.pause();
        }
    }

    public void resumeAd() {
        log(ADS, "Resuming ad"); //NON-NLS
        updateAdVisibility();
    }

    private void updateAdVisibility() {
        log(ADS, "Showing or hiding ad: " + getStatus(mOverallStatus)); //NON-NLS
        switch (mOverallStatus) {
            case STATUS_SHOW_ALL_ADS:
            case STATUS_SHOW_ANONYMOUS_ADS:
                if (mAdView != null) {
                    if (!mGooglePlayServicesAvailable) {
                        GoogleApiAvailability API = GoogleApiAvailability.getInstance();
                        int GP = API.isGooglePlayServicesAvailable(mContext);
                        mGooglePlayServicesAvailable = GP == ConnectionResult.SUCCESS;
                        log(ADS, "Updated play service availability: " + mGooglePlayServicesAvailable); //NON-NLS
                    }
                    if (mGooglePlayServicesAvailable) {
                        log(ADS, "Attempting to show ad"); //NON-NLS
                        mAdView.setAdListener(mAdListener);
                        if (mAdRequest != null) {
                            if (mOverallStatus == STATUS_SHOW_ALL_ADS &&
                                    mShowingNonPersonalisedAds) {
                                mAdRequest = null;
                            }
                            if (mOverallStatus == STATUS_SHOW_ANONYMOUS_ADS &&
                                    !mShowingNonPersonalisedAds) {
                                mAdRequest = null;
                            }
                        }
                        if (mAdRequest == null) {
                            mShowingNonPersonalisedAds = mOverallStatus == STATUS_SHOW_ANONYMOUS_ADS;

                            AdRequest.Builder builder = new AdRequest.Builder()
                                    .addTestDevice(AdRequest.DEVICE_ID_EMULATOR);
                            for (String device : TEST_DEVICES) {
                                builder.addTestDevice(device);
                            }
                            if (mShowingNonPersonalisedAds) {
                                Bundle extras = new Bundle();
                                extras.putString("npa", "1"); //NON-NLS
                                builder.addNetworkExtrasBundle(AdMobAdapter.class, extras);
                            }
                            mAdRequest = builder.build();
                        }
                        mAdView.loadAd(mAdRequest);
                        mAdView.resume();
                        mRequestingAd = true;
                    }
                }
                break;
            case STATUS_WAITING:
            case STATUS_REQUEST_PAID:
            case STATUS_HIDE_ADS:
            default:
                mRequestingAd = false;
                hideAds();
                break;
        }
    }

    private final AdListener mAdListener = new AdListener() {
        @Override
        public void onAdFailedToLoad(int errorCode) {
            super.onAdFailedToLoad(errorCode);
            log(ADS, "Ad failed to load: " + errorCode); //NON-NLS
            reportDebugInfo('a', STATS_AD_FAILED_TO_LOAD, ANALYTICS_STATUS_WARNING, null, null);
            if (mAdView != null) {
                mAdView.setVisibility(View.GONE);
            }
        }

        @Override
        public void onAdLoaded() {
            super.onAdLoaded();
            log(ADS, "Ad has loaded"); //NON-NLS
            if (mAdView != null) {
                if (mRequestingAd) {
                    mAdView.setVisibility(View.VISIBLE);
                } else {
                    hideAds();
                }
            }
            reportDebugInfo('a', STATS_AD_LOADED, ANALYTICS_STATUS_EVENT, null, null);
        }
    };

    private void hideAds() {
        log(ADS, "Hiding ads"); //NON-NLS
        if (mAdView != null) {
            mAdView.pause();
            mAdView.setVisibility(View.GONE);
        }
    }

    //region Consent
    private static final String PRIVACY_URL = "https://sites.google.com/view/storageanalyzer/privacy";

    @IntDef(
            value = {
                    STATUS_ADS_INIT,
                    STATUS_ADS_NOTHING,
                    STATUS_ADS_UNKNOWN,
                    STATUS_ADS_PREFER_PAID,
                    STATUS_ADS_ALLOWED_PERSONALISED,
                    STATUS_ADS_ALLOWED_NON_EU,
                    STATUS_ADS_ALLOWED_ANONYMOUS,
                    STATUS_ADS_REQUESTING_CONSENT,
                    STATUS_ADS_CONSENT_UNKNOWN,
                    STATUS_ADS_CONSENT_FAILED,
                    STATUS_ADS_CONSENT_TIMED_OUT
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface StatusAds {
    }

    private static String getAdsStatusName(@StatusAds int status) {
        switch (status) {
            case STATUS_ADS_ALLOWED_ANONYMOUS:
                return "Anonymous (Allowed)";
            case STATUS_ADS_ALLOWED_NON_EU:
                return "Non Eu (Allowed)";
            case STATUS_ADS_ALLOWED_PERSONALISED:
                return "All (Allowed)";
            case STATUS_ADS_INIT:
                return "Initialising";
            case STATUS_ADS_NOTHING:
                return "Not Set";
            case STATUS_ADS_PREFER_PAID:
                return "Buying";
            case STATUS_ADS_REQUESTING_CONSENT:
                return "Requesting Consent";
            case STATUS_ADS_UNKNOWN:
                return "Unknown";
            case STATUS_ADS_CONSENT_UNKNOWN:
                return "Consent Not Available";
            case STATUS_ADS_CONSENT_FAILED:
                return "Consent determination has failed";
            case STATUS_ADS_CONSENT_TIMED_OUT:
                return "Consent status has timed out";
        }
        return "N/A";
    }

    private static final long CONSENT_LOAD_TIMEOUT = 15 * 1000;
    private static final long CONSENT_FORCED_DELAY = 0;

    private static final int STATUS_ADS_INIT = -1;
    private static final int STATUS_ADS_NOTHING = 0;
    private static final int STATUS_ADS_UNKNOWN = 1;
    private static final int STATUS_ADS_ALLOWED_PERSONALISED = 2;
    private static final int STATUS_ADS_ALLOWED_ANONYMOUS = 3;
    private static final int STATUS_ADS_ALLOWED_NON_EU = 4;
    private static final int STATUS_ADS_PREFER_PAID = 5;
    private static final int STATUS_ADS_REQUESTING_CONSENT = 6;
    private static final int STATUS_ADS_CONSENT_UNKNOWN = 7;
    private static final int STATUS_ADS_CONSENT_FAILED = 8;
    private static final int STATUS_ADS_CONSENT_TIMED_OUT = 9;

    private final Handler mHandler = new Handler();
    private final Runnable mConsentTimedOut = new Runnable() {
        @Override
        public void run() {
            setAdsStatus(STATUS_ADS_CONSENT_TIMED_OUT);
        }
    };

    @StatusAds
    private int mAdsStatus = STATUS_ADS_INIT;
    private ConsentForm mConsentForm = null;

    private void setAdsStatus(@StatusAds int status) {
        log(ADS, String.format(Locale.US, "Updating ads status from %s to %s", getAdsStatusName(mAdsStatus), getAdsStatusName(status))); //NON-NLS
        if (mAdsStatus != status) {
            log(ADS, "Ads status has changed...");
            mAdsStatus = status;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(SETTINGS_KEY_ADS, status);
            editor.apply();
            updateMainStatus();
        }
    }

    private void maybeResetAdsStatus() {
        switch (mAdsStatus) {
            case GoogleHelper.STATUS_ADS_CONSENT_FAILED:
            case GoogleHelper.STATUS_ADS_UNKNOWN:
                setAdsStatus(STATUS_ADS_CONSENT_UNKNOWN);
                break;
            case GoogleHelper.STATUS_ADS_CONSENT_TIMED_OUT:
                mConsentFormShowing = false;
                setAdsStatus(STATUS_ADS_CONSENT_UNKNOWN);
                break;
            case GoogleHelper.STATUS_ADS_ALLOWED_ANONYMOUS:
            case GoogleHelper.STATUS_ADS_ALLOWED_NON_EU:
            case GoogleHelper.STATUS_ADS_ALLOWED_PERSONALISED:
            case GoogleHelper.STATUS_ADS_CONSENT_UNKNOWN:
            case GoogleHelper.STATUS_ADS_INIT:
            case GoogleHelper.STATUS_ADS_NOTHING:
            case GoogleHelper.STATUS_ADS_PREFER_PAID:
            case GoogleHelper.STATUS_ADS_REQUESTING_CONSENT:
                // do nothing
                break;
        }
    }

    private void checkAdConsent() {
        log(EU_CONSENT, "Checking for existing consent status"); //NON-NLS
        ConsentInformation consentInformation = ConsentInformation.getInstance(mContext);
        String[] publisherIds = {mContext.getString(R.string.ads_publisher_id)};
        consentInformation.requestConsentInfoUpdate(publisherIds, new ConsentInfoUpdateListener() {
            @Override
            public void onConsentInfoUpdated(ConsentStatus consentStatus) {
                mHandler.removeCallbacks(mConsentTimedOut);
                if (ConsentInformation.getInstance(mContext).isRequestLocationInEeaOrUnknown()) {
                    switch (consentStatus) {
                        case PERSONALIZED:
                            log(ADS, "User is allowing personalised ads");
                            D.log(EU_CONSENT, "User is allowing personalised ads"); //NON-NLS
                            setAdsStatus(STATUS_ADS_ALLOWED_PERSONALISED);
                            reportDebugInfo('c', CONSENT_PERSONALISED, ANALYTICS_STATUS_EVENT, null, null);
                            break;
                        case NON_PERSONALIZED:
                            log(ADS, "User is allowing anonymous ads");
                            D.log(EU_CONSENT, "User is allowing anonymous ads"); //NON-NLS
                            setAdsStatus(STATUS_ADS_ALLOWED_ANONYMOUS);
                            reportDebugInfo('c', CONSENT_ANONYMOUS, ANALYTICS_STATUS_EVENT, null, null);
                            break;
                        case UNKNOWN:
                            log(ADS, "No consent set - requesting from user");
                            D.log(EU_CONSENT, "User has not yet consented - requesting"); //NON-NLS
                            setAdsStatus(STATUS_ADS_NOTHING);
                            reportDebugInfo('c', CONSENT_TO_BE_REQUESTED, ANALYTICS_STATUS_EVENT, null, null);
                            requestConsent();
                            break;
                        default:
                            log(ADS, "Unknown response from consent request");
                            setAdsStatus(STATUS_ADS_CONSENT_FAILED);
                    }
                } else {
                    log(ADS, "Outside EU - allow all ads");
                    setAdsStatus(STATUS_ADS_ALLOWED_NON_EU);
                    reportDebugInfo('c', CONSENT_NON_EU, ANALYTICS_STATUS_EVENT, null, null);
                }
            }

            @Override
            public void onFailedToUpdateConsentInfo(String errorDescription) {
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // don't show ads if consent has failed
                        mHandler.removeCallbacks(mConsentTimedOut);
                        log(EU_CONSENT, "Failed to determine consent status: " + errorDescription); //NON-NLS
                        switch (mAdsStatus) {
                            case STATUS_ADS_ALLOWED_ANONYMOUS:
                            case STATUS_ADS_ALLOWED_NON_EU:
                            case STATUS_ADS_ALLOWED_PERSONALISED:
                                // don't downgrade the ads status if it was previously known
                                log(ADS, "We previously knew the consent, don't downgrade to unknown on error");
                                break;
                            case STATUS_ADS_CONSENT_UNKNOWN:
                            case STATUS_ADS_INIT:
                            case STATUS_ADS_NOTHING:
                            case STATUS_ADS_PREFER_PAID:
                            case STATUS_ADS_REQUESTING_CONSENT:
                            case STATUS_ADS_UNKNOWN:
                            case STATUS_ADS_CONSENT_FAILED:
                            case STATUS_ADS_CONSENT_TIMED_OUT:
                                log(ADS, "Failed to determine consent");
                                setAdsStatus(STATUS_ADS_CONSENT_FAILED);
                                break;
                        }
                        reportDebugInfo('c', CONSENT_FAILED_TO_GET_STATUS, ANALYTICS_STATUS_WARNING, null, errorDescription);
                    }
                }, CONSENT_FORCED_DELAY);
            }
        });
        mHandler.postDelayed(mConsentTimedOut, CONSENT_LOAD_TIMEOUT);
    }

    public void clearConsent() {
        log(ADS, "Consent has been cleared");
        ConsentInformation consentInformation = ConsentInformation.getInstance(mContext);
        consentInformation.setConsentStatus(ConsentStatus.UNKNOWN);
        setAdsStatus(STATUS_ADS_NOTHING);
        reportDebugInfo('c', CONSENT_RESET, ANALYTICS_STATUS_EVENT, null, null);
        checkAdConsent();
    }

    private boolean mConsentFormShowing = false;

    private void requestConsent() {
        // note: the form is prepared regardless of the pro status
        // it's only at the point of showing it that we might stop
        log(EU_CONSENT, "Requesting consent from user");
        URL privacyUrl;
        try {
            privacyUrl = new URL(PRIVACY_URL);
        } catch (MalformedURLException e) {
            D.error(GENERAL, "Internal error - should never happen", e); //NON-NLS
            reportDebugInfo('c', CONSENT_ERROR_LOADING_PRIVACY_POLICY, ANALYTICS_STATUS_WARNING, null, null);
            D.log(ADS, "Error getting consent - should never happen");
            setAdsStatus(STATUS_ADS_UNKNOWN);
            return;
        }
        if (mConsentFormShowing) {
            log(EU_CONSENT, "Consent form already (apparently) showing - do nothing");
            return;
        }
        mConsentFormShowing = true;
        try {
            final Activity activity = mActivity.get();
            if (activity != null && !activity.isFinishing()) {
                mConsentForm = new ConsentForm.Builder(activity, privacyUrl)
                        .withListener(new ConsentFormListener() {

                            @Override
                            public void onConsentFormLoaded() {
                                super.onConsentFormLoaded();
                                mHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        mHandler.removeCallbacks(mConsentTimedOut);
                                        @InternalPurchaseStatus int adsPurchaseStatus = getAdsPurchaseStatus();
                                        log(EU_CONSENT, String.format("Consent form loaded. Pro status: %s", getPurchaseStatusName(adsPurchaseStatus)));
                                        // this affects the UI - showing it depends on the pro status
                                        switch (adsPurchaseStatus) {
                                            case INT_STATUS_PURCHASE_OFF:
                                                log(ADS, "Pro is off, so requesting consent");
                                                D.log(EU_CONSENT, "Attempting to display consent form"); //NON-NLS
                                                setAdsStatus(STATUS_ADS_REQUESTING_CONSENT);
                                                reportDebugInfo('c', CONSENT_SHOWING_CONSENT, ANALYTICS_STATUS_EVENT, null, null);
                                                if (!activity.isFinishing()) {
                                                    mConsentForm.show();
                                                    return;
                                                } else {
                                                    log(EU_CONSENT, "App has already quit... do nothing"); //NON-NLS
                                                }
                                                break;
                                            case INT_STATUS_PURCHASE_ON:
                                                log(ADS, "Pro is on, so don't request consent");
                                                D.log(EU_CONSENT, "pro on - we're not showing ads"); //NON-NLS
                                                setAdsStatus(STATUS_ADS_UNKNOWN);
                                                break;
                                            case INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT:
                                                log(ADS, "Purchasing - wait for that to complete");
                                                D.log(EU_CONSENT, "purchasing now - do nothing (keep waiting)"); //NON-NLS
                                                break;
                                            case INT_STATUS_PURCHASE_INIT:
                                            case INT_STATUS_PURCHASE_NOTHING:
                                            case INT_STATUS_PURCHASE_UNKNOWN:
                                            default:
                                                // if the pro is determined to be off (i.e later), hide the app
                                                log(ADS, "pro status not yet known. keep waiting and not setting any ad status yet");
                                                D.log(EU_CONSENT, "pro status not yet known. keep waiting"); //NON-NLS
                                                setAdsStatus(STATUS_ADS_NOTHING);
                                                break;
                                        }
                                        // if we get to here, we haven't actually shown it
                                        mConsentFormShowing = false;
                                    }
                                }, CONSENT_FORCED_DELAY);
                            }

                            @Override
                            public void onConsentFormClosed(
                                    ConsentStatus consentStatus, Boolean userPrefersAdFree) {
                                log(EU_CONSENT, String.format("User has responded: %s, %s", consentStatus, userPrefersAdFree)); //NON-NLS
                                mConsentFormShowing = false;
                                if (userPrefersAdFree) {
                                    log(ADS, "User has requested the paid version");
                                    D.log(EU_CONSENT, "User has requested the paid version"); //NON-NLS
                                    setAdsStatus(STATUS_ADS_PREFER_PAID);
                                    reportDebugInfo('c', CONSENT_REQUESTED_PAID, ANALYTICS_STATUS_EVENT, null, null);
                                    updateMainStatus();
                                } else {
                                    switch (consentStatus) {
                                        case PERSONALIZED:
                                            log(ADS, "User has allowed all ads");
                                            D.log(EU_CONSENT, "User has allowed personalised ads"); //NON-NLS
                                            reportDebugInfo('c', CONSENT_SETTING_PERSONALISED, ANALYTICS_STATUS_EVENT, null, null);
                                            setAdsStatus(STATUS_ADS_ALLOWED_PERSONALISED);
                                            break;
                                        case NON_PERSONALIZED:
                                            log(ADS, "User has allowed anonymous ads");
                                            D.log(EU_CONSENT, "User has allowed anonymous ads"); //NON-NLS
                                            reportDebugInfo('c', CONSENT_SETTING_ANONYMOUS, ANALYTICS_STATUS_EVENT, null, null);
                                            setAdsStatus(STATUS_ADS_ALLOWED_ANONYMOUS);
                                            break;
                                        case UNKNOWN:
                                            log(ADS, "User consent is still unknown. This shouldn't be possible");
                                            D.warn(EU_CONSENT, "Still unknown. This shouldn't happen"); //NON-NLS
                                            setAdsStatus(STATUS_ADS_UNKNOWN);
                                            reportDebugInfo('c', CONSENT_FORM_RETURNED_UNKNOWN, ANALYTICS_STATUS_ERROR, null, null);
                                            break;
                                    }
                                }
                            }

                            @Override
                            public void onConsentFormError(String errorDescription) {
                                mHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        mHandler.removeCallbacks(mConsentTimedOut);
                                        mConsentFormShowing = false;
                                        log(ADS, "Error getting consent from user. Don't show ads");
                                        D.warn(EU_CONSENT, "There was an error getting consent from the user: " + errorDescription); //NON-NLS
                                        setAdsStatus(STATUS_ADS_CONSENT_FAILED);
                                        reportDebugInfo('c', CONSENT_FORM_ERROR, ANALYTICS_STATUS_WARNING, null, null);
                                    }
                                }, CONSENT_FORCED_DELAY);
                            }

                        })
                        .withPersonalizedAdsOption()
                        .withNonPersonalizedAdsOption()
                        .withAdFreeOption()
                        .build();
                mConsentForm.load();
                mHandler.postDelayed(mConsentTimedOut, CONSENT_LOAD_TIMEOUT);
            } else {
                mConsentFormShowing = false;
                D.warn(EU_CONSENT, "Activity not available trying to load consent form");
                setAdsStatus(STATUS_ADS_CONSENT_FAILED);
                reportDebugInfo('c', CONSENT_FORM_NO_ACTIVITY, ANALYTICS_STATUS_WARNING, null, null);
            }
        } catch (Exception e) {
            mConsentFormShowing = false;
            D.warn(EU_CONSENT, "Unknown error trying to load consent form", e);
            setAdsStatus(STATUS_ADS_CONSENT_FAILED);
            reportDebugInfo('c', CONSENT_FORM_EXCEPTION, ANALYTICS_STATUS_ERROR, null, e.getMessage());
            Crashlytics.logException(e);
        }


    }
    //endregion

    //endregion

    //region Purchases
    private HashMap<String, PurchaseInfo> mPurchaseInfo = new HashMap<>();

    private static class PurchaseInfo {
        private final String key;
        private Preference preference;
        private boolean consentPurchase = false;
        private boolean removesAds = false;
        @InternalPurchaseStatus
        private int status = INT_STATUS_PURCHASE_INIT;
        private String priceString;
        private SkuDetails skuDetails;
        private String token;
        private final String[] otherKeys;

        private PurchaseInfo(String key, String... otherKeys) {
            this.key = key;
            this.otherKeys = otherKeys;
        }
    }

    public interface PurchaseStatusChangeListener {
        void PurchaseStatusChanged();
    }

    private final List<PurchaseStatusChangeListener> mPurchaseChangeListeners = new ArrayList<>();

    public void registerPurchaseChangeListener(PurchaseStatusChangeListener listener) {
        if (!mPurchaseChangeListeners.contains(listener)) {
            mPurchaseChangeListeners.add(listener);
        }
    }

    public void unregisterPurchaseChangeListener(PurchaseStatusChangeListener listener) {
        mPurchaseChangeListeners.remove(listener);
    }

    private void purchasesStatusChanged() {
        for (PurchaseStatusChangeListener listener : mPurchaseChangeListeners) {
            listener.PurchaseStatusChanged();
        }
    }

    @IntDef(
            value = {
                    INT_STATUS_PURCHASE_INIT,
                    INT_STATUS_PURCHASE_NOTHING,
                    INT_STATUS_PURCHASE_UNKNOWN,
                    INT_STATUS_PURCHASE_OFF,
                    INT_STATUS_PURCHASE_ON,
                    INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface StatusPro {
    }

    private static String getPurchaseStatusName(@StatusPro int status) {
        switch (status) {
            case INT_STATUS_PURCHASE_INIT:
                return "Initialising";
            case INT_STATUS_PURCHASE_NOTHING:
                return "Not Set";
            case INT_STATUS_PURCHASE_OFF:
                return "Off";
            case INT_STATUS_PURCHASE_ON:
                return "On";
            case INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT:
                return "Purchasing (from ad consent screen)";
            case INT_STATUS_PURCHASE_UNKNOWN:
                return "Unknown";
        }
        return "N/A";
    }

    static final int INT_STATUS_PURCHASE_NOTHING = 0;
    static final int INT_STATUS_PURCHASE_INIT = 1;
    static final int INT_STATUS_PURCHASE_OFF = 2;
    static final int INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT = 3;
    static final int INT_STATUS_PURCHASE_UNKNOWN = 4;
    static final int INT_STATUS_PURCHASE_ON = 5;

    @IntDef(
            value = {
                    INT_STATUS_PURCHASE_INIT,
                    INT_STATUS_PURCHASE_NOTHING,
                    INT_STATUS_PURCHASE_UNKNOWN,
                    INT_STATUS_PURCHASE_OFF,
                    INT_STATUS_PURCHASE_ON,
                    INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface InternalPurchaseStatus {
    }

    private void setPurchaseStatus(String key, @InternalPurchaseStatus int status) {
        PurchaseInfo info = mPurchaseInfo.get(key);
        assert info != null;
        D.log(BILLING, String.format(Locale.US, "Updating purchase status for %s from %s to %s", key, getPurchaseStatusName(info.status), getPurchaseStatusName(status))); //NON-NLS
        @InternalPurchaseStatus int initialAdsStatus = getAdsPurchaseStatus();
        if (info.status != status) {
            info.status = status;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
            SharedPreferences.Editor editor = prefs.edit();
            //noinspection StringConcatenation
            editor.putInt(SETTINGS_KEY_PURCHASE + key.hashCode(), status);
            editor.apply();
            if (initialAdsStatus != getAdsPurchaseStatus()) {
                maybeResetAdsStatus();
            }
            updateMainStatus();
            purchasesStatusChanged();
        }
    }

    private BillingClient mBillingClient;
    private boolean mIsServiceConnected = false;
    private String mBillingPublicKey;

    private void setBillingPublicKey(String key) {
        mBillingPublicKey = key;
    }

    private void initialiseBilling() {
        log(BILLING, "Initialise billing");
        startServiceConnection(new Runnable() {
            @Override
            public void run() {
                // IAB is fully set up. Now, let's get an inventory of stuff we own.
                log(BILLING, "Setup successful. Querying inventory."); //NON-NLS
                queryPurchases();
                queryProducts();
            }
        });
    }

    private void executeServiceRequest(Runnable runnable) {
        log(BILLING, "Requesting service request (Google action)");
        if (mIsServiceConnected) {
            log(BILLING, "Ready to run - running immediately");
            runnable.run();
        } else {
            log(BILLING, "Not ready to run - restarting service connection");
            startServiceConnection(runnable);
        }
    }

    private void startServiceConnection(final Runnable executeOnSuccess) {
        mBillingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                log(BILLING, "Setup finished. Response code: " + getBillingResponseString(billingResult.getResponseCode())); //NON-NLS
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    log(BILLING, "Billing setup worked. Check for pro status");
                    mIsServiceConnected = true;
                    if (executeOnSuccess != null) {
                        executeOnSuccess.run();
                    }
                } else {
                    log(BILLING, "Billing failed. Set to unknown if not already set");
                    for (PurchaseInfo info : mPurchaseInfo.values()) {
                        if (info.status == INT_STATUS_PURCHASE_INIT || info.status == INT_STATUS_PURCHASE_NOTHING) {
                            setPurchaseStatus(info.key, INT_STATUS_PURCHASE_UNKNOWN);
                        }
                    }
                    reportDebugInfo('b', BILLING_SETUP_ERROR_BASE, ANALYTICS_STATUS_WARNING, (long) billingResult.getResponseCode(), null);
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                log(BILLING, "Billing service has disconnected");
                mIsServiceConnected = false;
                for (PurchaseInfo info : mPurchaseInfo.values()) {
                    if (info.status == INT_STATUS_PURCHASE_INIT || info.status == INT_STATUS_PURCHASE_NOTHING) {
                        setPurchaseStatus(info.key, INT_STATUS_PURCHASE_UNKNOWN);
                    }
                }
                reportDebugInfo('b', BILLING_SETUP_DISCONNECTED, ANALYTICS_STATUS_WARNING, null, null);
            }
        });
    }

    private static String getBillingResponseString(@BillingClient.BillingResponseCode int billingResponseCode) {
        switch (billingResponseCode) {
            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                return "Billing Unavailable";
            case BillingClient.BillingResponseCode.DEVELOPER_ERROR:
                return "Developer Error";
            case BillingClient.BillingResponseCode.ERROR:
                return "Error";
            case BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED:
                return "Feature Not Supported";
            case BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED:
                return "Item Already Owned";
            case BillingClient.BillingResponseCode.ITEM_NOT_OWNED:
                return "Item Not Owned";
            case BillingClient.BillingResponseCode.ITEM_UNAVAILABLE:
                return "Item Unavailable";
            case BillingClient.BillingResponseCode.OK:
                return "OK";
            case BillingClient.BillingResponseCode.SERVICE_DISCONNECTED:
                return "Service Disconnected";
            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
                return "Service Unavailable";
            case BillingClient.BillingResponseCode.USER_CANCELED:
                return "User Cancelled";
        }
        return "Unknown";
    }

    private void queryPurchases() {
        log(BILLING, "Querying purchases");
        Runnable queryToExecute = new Runnable() {
            @Override
            public void run() {
                log(BILLING, "Running purchases query");
                long time = System.currentTimeMillis();
                Purchase.PurchasesResult purchasesResult = mBillingClient.queryPurchases(BillingClient.SkuType.INAPP);
                D.log(BILLING, "Querying purchases elapsed time: " + (System.currentTimeMillis() - time) + "ms"); //NON-NLS
                if (purchasesResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    for (PurchaseInfo info : mPurchaseInfo.values()) {
                        boolean purchase_found = false;
                        for (Purchase purchase : purchasesResult.getPurchasesList()) {
                            if (info.key.equals(purchase.getSku()) && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                verifyValidSignature(info.key, purchase);
                                purchase_found = true;
                                break;
                            }
                        }
                        if (!purchase_found) {
                            setPurchaseStatus(info.key, INT_STATUS_PURCHASE_OFF);
                        }
                    }
                } else {
                    log(BILLING, "queryPurchases() got an error response code: " + purchasesResult.getResponseCode()); //NON-NLS
                    for (PurchaseInfo info : mPurchaseInfo.values()) {
                        setPurchaseStatus(info.key, INT_STATUS_PURCHASE_UNKNOWN);
                    }
                    reportDebugInfo('b', BILLING_QUERY_ERROR_BASE, ANALYTICS_STATUS_WARNING, (long) purchasesResult.getResponseCode(), null);
                }
            }
        };

        executeServiceRequest(queryToExecute);
    }

    private void queryProducts() {
        log(BILLING, "Get product details");
        Runnable queryToExecute = new Runnable() {
            @Override
            public void run() {
                log(BILLING, "Running product query");
                List<String> skus = new ArrayList<>();
                for (PurchaseInfo info : mPurchaseInfo.values()) {
                    skus.add(info.key);
                }
                SkuDetailsParams params = SkuDetailsParams.newBuilder()
                        .setSkusList(skus)
                        .setType(BillingClient.SkuType.INAPP)
                        .build();
                mBillingClient.querySkuDetailsAsync(params, new SkuDetailsResponseListener() {
                    @Override
                    public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> skuDetailsList) {
                        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK &&
                                skuDetailsList != null &&
                                !skuDetailsList.isEmpty()) {
                            log(BILLING, String.format(Locale.US, "skuDetails: %d %s",  //NON-NLS
                                    skuDetailsList.get(0).getPriceAmountMicros(), skuDetailsList.get(0).getPriceCurrencyCode()));
                            for (SkuDetails details : skuDetailsList) {
                                D.log(BILLING, String.format(Locale.US, "skuDetails for %s: %d %s",  //NON-NLS
                                        details.getSku(), details.getPriceAmountMicros(), details.getPriceCurrencyCode()));
                                for (PurchaseInfo info : mPurchaseInfo.values()) {
                                    if (info.key.equals(details.getSku())) {
                                        info.priceString = details.getPrice();
                                        info.skuDetails = details;
                                        break;
                                    }
                                }
                            }
                            updateSettingVisibility();
                        } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                            D.log(BILLING, "No purchase types found"); //NON-NLS
                            reportDebugInfo('b', BILLING_QUERY_PRODUCT_NOT_FOUND, ANALYTICS_STATUS_ERROR, null, null);
                        } else {
                            log(BILLING, "Error getting sku details: " + billingResult.getResponseCode()); //NON-NLS
                            reportDebugInfo('b', BILLING_QUERY_PRODUCT_ERROR_BASE, ANALYTICS_STATUS_WARNING, (long) billingResult.getResponseCode(), null);
                        }
                    }
                });
            }
        };
        executeServiceRequest(queryToExecute);
    }

    private boolean startPurchaseFromConsent() {
        for (PurchaseInfo info : mPurchaseInfo.values()) {
            if (info.consentPurchase) {
                return startPurchase(info.key, true);
            }
        }
        return false;
    }

    public boolean startPurchase(String key) {
        return startPurchase(key, false);
    }

    private boolean startPurchase(final String key, final boolean fromConsent) {
        log(BILLING, "Starting purchase flow"); //NON-NLS
        final PurchaseInfo info = mPurchaseInfo.get(key);
        if (info != null && info.skuDetails != null) {
            Runnable purchaseFlowRequest = new Runnable() {
                @Override
                public void run() {
                    Activity activity = mActivity.get();
                    if (activity == null) {
                        log(BILLING, "App is not running - don't try to start the purchase");
                        return;
                    }
                    log(BILLING, "Launching in-app purchase flow."); //NON-NLS

                    BillingFlowParams purchaseParams = BillingFlowParams.newBuilder()
                            .setSkuDetails(info.skuDetails)
                            .build();
                    BillingResult result = mBillingClient.launchBillingFlow(activity, purchaseParams);
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK || result.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                        log(BILLING, "Purchase flow started successfully");
                        if (fromConsent) {
                            log(BILLING, "Purchasing from consent");
                            setPurchaseStatus(key, INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT);
                        }
                    } else {
                        log(BILLING, "Error starting purchase flow");
                        reportDebugInfo('b', BILLING_PURCHASE_START_ERROR_BASE, ANALYTICS_STATUS_WARNING, (long) result.getResponseCode(), null);
                    }
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                        log(BILLING, "Item already owned - verify the purchase");
                        queryPurchases();
                    }
                }
            };
            executeServiceRequest(purchaseFlowRequest);
            return true;
        }
        return false;
    }

    private final class PurchasesUpdatedListenerCustom implements PurchasesUpdatedListener {

        @Override
        public void onPurchasesUpdated(BillingResult result, @Nullable List<Purchase> purchases) {
            log(BILLING, "Purchases updated...");
            switch (result.getResponseCode()) {
                case BillingClient.BillingResponseCode.OK:
                    log(BILLING, "Purchase updated - success. Verify the signature");
                    if (purchases != null) {
                        for (PurchaseInfo info : mPurchaseInfo.values()) {
                            for (Purchase purchase : purchases) {
                                if (info.key.equals(purchase.getSku()) && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                    verifyValidSignature(info.key, purchase);
                                }
                            }
                        }
                    }
                    break;
                case BillingClient.BillingResponseCode.USER_CANCELED:
                    log(BILLING, "onPurchasesUpdated() - user cancelled the purchase flow - skipping"); //NON-NLS

                    reportDebugInfo('b', BILLING_PURCHASE_UPDATE_USER_CANCELLED, ANALYTICS_STATUS_EVENT, null, null);

                    for (PurchaseInfo info : mPurchaseInfo.values()) {
                        if (info.consentPurchase && info.status == INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT) {
                            // This should only happen during the purchase flow of the remove ads, so it should be safe
                            setAdsStatus(STATUS_ADS_NOTHING);
                            setPurchaseStatus(info.key, INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT);
                            checkAdConsent();
                        }
                    }
                    break;
                case BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED:
                    log(BILLING, "Item is already owned - query and verify");
                    queryPurchases();
                    break;
                default:
                    log(BILLING, "onPurchasesUpdated() got unknown resultCode: " + result.getDebugMessage()); //NON-NLS

                    reportDebugInfo('b', BILLING_PURCHASE_UPDATE_ERROR_BASE, ANALYTICS_STATUS_WARNING, (long) result.getResponseCode(), null);
                    for (PurchaseInfo info : mPurchaseInfo.values()) {
                        if (info.consentPurchase && info.status == INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT) {
                            log(ADS, "Unknown result from purchase. Something has gone wrong. Hide ads");
                            setAdsStatus(STATUS_ADS_NOTHING);
                            setPurchaseStatus(info.key, INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT);
                            checkAdConsent();
                        }
                    }
                    break;
            }
        }
    }

    // this is only used for debugging
    public void consumePurchase(String sku) {
        for (final PurchaseInfo info : mPurchaseInfo.values()) {
            if (info.key.equals(sku)) {
                if (info.token != null) {
                    // Generating Consume Response listener
                    final ConsumeResponseListener onConsumeListener = new ConsumeResponseListener() {
                        @Override
                        public void onConsumeResponse(BillingResult response, String purchaseToken) {
                            D.log(BILLING, "finished attempting to consume purchase"); //NON-NLS
                            if (response.getResponseCode() == BillingClient.BillingResponseCode.OK ||
                                    response.getResponseCode() == BillingClient.BillingResponseCode.ITEM_NOT_OWNED) {
                                setPurchaseStatus(info.key, INT_STATUS_PURCHASE_OFF);
                            } else {
                                D.log(BILLING, "failed to consume purchase"); //NON-NLS
                            }
                        }
                    };

                    // Creating a runnable from the request to use it inside our connection retry policy below
                    Runnable consumeRequest = new Runnable() {
                        @Override
                        public void run() {
                            // Consume the purchase async
                            ConsumeParams params = ConsumeParams.newBuilder()
                                    .setPurchaseToken(info.token)
                                    .build();
                            mBillingClient.consumeAsync(params, onConsumeListener);
                        }
                    };

                    executeServiceRequest(consumeRequest);
                }
            }
        }
    }

    private void verifyValidSignature(String key, Purchase purchase) {
        String signedData = purchase.getOriginalJson();
        String signature = purchase.getSignature();
        PurchaseInfo info = mPurchaseInfo.get(key);
        assert info != null;
        if (TextUtils.isEmpty(signedData)) {
            log(BILLING, "Signed Data is empty"); //NON-NLS
            reportDebugInfo('b', BILLING_VERIFY_ERROR_NO_SIGNED_DATA, ANALYTICS_STATUS_ERROR, null, null);
            setPurchaseStatus(key, INT_STATUS_PURCHASE_OFF);
            return;
        }
        if (TextUtils.isEmpty(signature)) {
            log(BILLING, "Signature is empty"); //NON-NLS
            reportDebugInfo('b', BILLING_VERIFY_ERROR_NO_SIGNATURE, ANALYTICS_STATUS_ERROR, null, null);
            setPurchaseStatus(key, INT_STATUS_PURCHASE_OFF);
            return;
        }
        PublicKey publicKey = generatePublicKey(decode(mBillingPublicKey));
        if (publicKey != null) {
            if (verify(publicKey, signedData, signature)) {
                log(BILLING, "Signature is valid. Pro is purchased");
                info.token = purchase.getPurchaseToken();
                setPurchaseStatus(key, INT_STATUS_PURCHASE_ON);
                if (!purchase.isAcknowledged()) {
                    acknowledgePurchase(purchase);
                }
            } else {
                log(BILLING, "Signature is invalid. Assume pro is off");
                // if the signature fails, something weird has probably happened
                info.token = null;
                setPurchaseStatus(key, INT_STATUS_PURCHASE_OFF);
            }
        } else {
            log(BILLING, "Unable to generate key - this should never happen"); //NON-NLS
            setPurchaseStatus(key, INT_STATUS_PURCHASE_OFF);
        }
    }

    private void acknowledgePurchase(Purchase purchase) {
        executeServiceRequest(new Runnable() {
            @Override
            public void run() {
                AcknowledgePurchaseParams params =
                        AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(purchase.getPurchaseToken())
                                .build();
                mBillingClient.acknowledgePurchase(params, new AcknowledgePurchaseResponseListener() {
                    @Override
                    public void onAcknowledgePurchaseResponse(BillingResult billingResult) {
                        switch (billingResult.getResponseCode()) {
                            case BillingClient.BillingResponseCode.OK:
                                reportDebugInfo('b', BILLING_PURCHASE_ACK_OK, ANALYTICS_STATUS_EVENT, null, null);
                            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                            case BillingClient.BillingResponseCode.ERROR:
                            case BillingClient.BillingResponseCode.DEVELOPER_ERROR:
                            case BillingClient.BillingResponseCode.SERVICE_DISCONNECTED:
                            case BillingClient.BillingResponseCode.SERVICE_TIMEOUT:
                            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
                                reportDebugInfo('b', BILLING_PURCHASE_ACK_ERROR, ANALYTICS_STATUS_WARNING, (long) billingResult.getResponseCode(), billingResult.getDebugMessage());
                            default:
                                reportDebugInfo('b', BILLING_PURCHASE_ACK_ERROR, ANALYTICS_STATUS_ERROR, (long) billingResult.getResponseCode(), billingResult.getDebugMessage());
                        }
                    }
                });
            }
        });
    }

    private PublicKey generatePublicKey(String encodedPublicKey) {
        try {
            byte[] decodedKey = Base64.decode(encodedPublicKey, Base64.DEFAULT);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA"); //NON-NLS
            return keyFactory.generatePublic(new X509EncodedKeySpec(decodedKey));
        } catch (NoSuchAlgorithmException e) {
            reportDebugInfo('b', BILLING_VERIFY_ERROR_NO_SUCH_ALGORITHM, ANALYTICS_STATUS_ERROR, null, e.getMessage());
        } catch (InvalidKeySpecException e) {
            reportDebugInfo('b', BILLING_VERIFY_ERROR_INVALID_KEY_SPEC, ANALYTICS_STATUS_ERROR, null, e.getMessage());
        }
        return null;
    }

    private boolean verify(PublicKey publicKey, String signedData, String signature) {
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.decode(signature, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            reportDebugInfo('b', BILLING_VERIFY_ERROR_BASE64_DECODING, ANALYTICS_STATUS_ERROR, null, e.getMessage());
            D.log(BILLING, "Base64 decoding failed."); //NON-NLS
            return false;
        }
        try {
            Signature signatureAlgorithm = Signature.getInstance("SHA1withRSA"); //NON-NLS
            signatureAlgorithm.initVerify(publicKey);
            signatureAlgorithm.update(signedData.getBytes());
            if (!signatureAlgorithm.verify(signatureBytes)) {
                reportDebugInfo(BILLING_VERIFY_ERROR_VERIFY_FAILED, ANALYTICS_STATUS_ERROR, (Long)null, null);
                D.log(BILLING, "Signature verification failed."); //NON-NLS
                return false;
            }
            return true;
        } catch (NoSuchAlgorithmException e) {
            reportDebugInfo('b', BILLING_VERIFY_ERROR_NO_SUCH_ALGORITHM_2, ANALYTICS_STATUS_ERROR, null, e.getMessage());
        } catch (InvalidKeyException e) {
            reportDebugInfo('b', BILLING_VERIFY_ERROR_INVALID_KEY, ANALYTICS_STATUS_ERROR, null, e.getMessage());
        } catch (SignatureException e) {
            reportDebugInfo('b', BILLING_VERIFY_ERROR_SIGNATURE, ANALYTICS_STATUS_ERROR, null, e.getMessage());
        }
        return false;
    }

    @SuppressWarnings("SameParameterValue")
    private static String decode(String s) {
        return new String(xorWithKey(base64Decode(s)));
    }

    @SuppressWarnings("MagicNumber")
    private static byte[] xorWithKey(byte[] a) {
        byte[] key = new byte[12];
        Random r = new Random(34534089753456L);
        r.nextBytes(key);
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ key[i % key.length]);
        }
        return out;
    }

    private static byte[] base64Decode(String s) {
        return Base64.decode(s, Base64.NO_WRAP);
    }

        // These aren't usually needed
//        public static String encode(String s) {
//            return base64Encode(xorWithKey(s.getBytes()));
//        }
//
//        private static String base64Encode(byte[] bytes) {
//            byte[] output = Base64.encode(bytes, Base64.NO_WRAP);
//            return new String(output);
//        }

    // endregion

    // region Billing Preferences

    private Preference mPrefGdpr = null;

    public void registerProSettings(Preference[] purchasePrefs, Preference gdpr) {
        log(ADS, "Registering Settings"); //NON-NLS
        for (Preference pref : purchasePrefs) {
            for (PurchaseInfo info : mPurchaseInfo.values()) {
                if (pref.getKey().equals(info.key)) {
                    info.preference = pref;
                    break;
                }
            }
        }
        mPrefGdpr = gdpr;
        updateSettingVisibility();
    }

    public void unregisterProSettings() {
        log(ADS, "Un-Registering Settings"); //NON-NLS
        for (PurchaseInfo info : mPurchaseInfo.values()) {
            if (info.preference != null) {
                info.preference.setOnPreferenceClickListener(null);
                info.preference = null;
            }
        }
        if (mPrefGdpr != null) {
            mPrefGdpr.setOnPreferenceClickListener(null);
            mPrefGdpr = null;
        }
        updateSettingVisibility();
    }

    private void updateSettingVisibility() {
        for (PurchaseInfo info : mPurchaseInfo.values()) {
            if (info.preference != null) {
                @PurchaseStatus int status = getPurchaseStatus(info.key, false);
                info.preference.setOnPreferenceClickListener(mPurchasePrefClick);
                switch (status) {
                    case PURCHASE_DISABLED:
                        info.preference.setVisible(true);
                        info.preference.setEnabled(true);
                        if (TextUtils.isEmpty(info.priceString)) {
                            info.preference.setSummary(R.string.settings_summary_pro_off_no_price);
                        } else {
                            info.preference.setSummary(String.format(mContext.getString(R.string.settings_summary_pro_off), info.priceString));
                        }
                        break;
                    case PURCHASE_ENABLED:
                        info.preference.setEnabled(false);
                        info.preference.setVisible(true);
                        info.preference.setSummary(mContext.getString(R.string.settings_summary_pro_on));
                        break;
                }
            }
        }
        if (mPrefGdpr != null) {
            mPrefGdpr.setOnPreferenceClickListener(mGdprPrefClick);
            switch (getAdsPurchaseStatus()) {
                case INT_STATUS_PURCHASE_OFF:
                    switch (mAdsStatus) {
                        case STATUS_ADS_ALLOWED_ANONYMOUS:
                            mPrefGdpr.setVisible(true);
                            mPrefGdpr.setSummary(R.string.settings_summary_gdpr_non_personalised);
                            break;
                        case STATUS_ADS_ALLOWED_PERSONALISED:
                            mPrefGdpr.setVisible(true);
                            mPrefGdpr.setSummary(R.string.settings_summary_gdpr_personalised);
                            break;
                        case STATUS_ADS_REQUESTING_CONSENT:
                            mPrefGdpr.setSummary(R.string.settings_summary_gdpr_unknown);
                            mPrefGdpr.setVisible(true);
                            break;
                        case STATUS_ADS_ALLOWED_NON_EU:
                        case STATUS_ADS_INIT:
                        case STATUS_ADS_NOTHING:
                        case STATUS_ADS_PREFER_PAID:
                        case STATUS_ADS_UNKNOWN:
                        case STATUS_ADS_CONSENT_UNKNOWN:
                        case STATUS_ADS_CONSENT_FAILED:
                        case STATUS_ADS_CONSENT_TIMED_OUT:
                            mPrefGdpr.setVisible(false);
                            break;
                    }
                    break;
                case INT_STATUS_PURCHASE_ON:
                case INT_STATUS_PURCHASE_INIT:
                case INT_STATUS_PURCHASE_NOTHING:
                case INT_STATUS_PURCHASE_PURCHASING_FROM_CONSENT:
                case INT_STATUS_PURCHASE_UNKNOWN:
                default:
                    mPrefGdpr.setVisible(false);
                    break;
            }
        }
    }

    private final Preference.OnPreferenceClickListener mPurchasePrefClick = new Preference.OnPreferenceClickListener() {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            for (PurchaseInfo info : mPurchaseInfo.values()) {
                if (info.key.equals(preference.getKey())) {
                    startPurchase(info.key);
                    break;
                }
            }
            return true;
        }
    };

    private final Preference.OnPreferenceClickListener mGdprPrefClick = new Preference.OnPreferenceClickListener() {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            clearConsent();
            return true;
        }
    };
    //endregion

}
