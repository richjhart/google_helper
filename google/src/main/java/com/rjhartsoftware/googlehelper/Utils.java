package com.rjhartsoftware.googlehelper;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;

import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.rjhartsoftware.logcatdebug.D;
import com.rjhartsoftware.popup.FragmentMessage;

import java.util.List;

public class Utils {

    public static void openUrl(@Nullable AppCompatActivity context, String url) {
        if (context == null) {
            return;
        }
        try {
            CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
            builder.setToolbarColor(ContextCompat.getColor(context, R.color.colorPrimary));
            CustomTabsIntent customTabsIntent = builder.build();
            customTabsIntent.launchUrl(context, Uri.parse(url));
            return;
        } catch (ActivityNotFoundException e) {
            Uri uri = Uri.parse(url);
            Intent webIntent = new Intent(Intent.ACTION_VIEW, uri);
            PackageManager packageManager = context.getPackageManager();
            List<ResolveInfo> activities = packageManager.queryIntentActivities(webIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (!activities.isEmpty()) {
                try {
                    context.startActivity(webIntent);
                    return;
                } catch (ActivityNotFoundException ignore) {

                }
            }
        }
        new FragmentMessage.Builder()
                .message(R.string.open_link_failed_1)
                .inactivePositiveButton(R.string.general_ok_1)
                .show(context);
    }

    public static void assertNotMainThread(@Nullable String reason) {
        boolean isUiThread = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? Looper.getMainLooper().isCurrentThread()
                : Thread.currentThread() == Looper.getMainLooper().getThread();
        if (isUiThread) {
            if (D.DEBUG) {
                if (!TextUtils.isEmpty(reason)) {
                    throw new BackgroundActivityOnMainThreadException(reason);
                } else {
                    throw new BackgroundActivityOnMainThreadException();
                }
            } else {
                Exception exception;
                if (!TextUtils.isEmpty(reason)) {
                    exception = new BackgroundActivityOnMainThreadException(reason);
                } else {
                    exception = new BackgroundActivityOnMainThreadException();
                }
                FirebaseCrashlytics.getInstance().recordException(exception);
            }
        }
    }

    public static void assertMainThread() {
        boolean isUiThread = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? Looper.getMainLooper().isCurrentThread()
                : Thread.currentThread() == Looper.getMainLooper().getThread();
        if (!isUiThread) {
            if (D.DEBUG) {
                throw new UIActivityOnBackgroundThreadException();
            } else {
                FirebaseCrashlytics.getInstance().recordException(new UIActivityOnBackgroundThreadException());
            }
        }
    }
}
