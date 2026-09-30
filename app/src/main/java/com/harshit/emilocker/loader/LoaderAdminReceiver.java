package com.harshit.emilocker.loader;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

public class LoaderAdminReceiver extends DeviceAdminReceiver {

    public static ComponentName getComponentName(Context context) {
        return new ComponentName(context.getPackageName(), LoaderAdminReceiver.class.getName());
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
    }
}
