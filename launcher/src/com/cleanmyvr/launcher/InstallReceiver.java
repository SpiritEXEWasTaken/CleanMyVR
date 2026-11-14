package com.cleanmyvr.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/** Result of a store install: shows Android's confirm dialog, then records the install. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String pkg = intent.getStringExtra("pkg");
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(confirm);
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                if (pkg != null) new Store(ctx).installed(pkg);
                AppRepo.invalidate();
                Toast.makeText(ctx, "Installed", Toast.LENGTH_SHORT).show();
                MainActivity.refreshIfOpen();
                break;
            default: {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.w("CleanMyVrLauncher", "install failed: " + status + " " + msg);
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED)
                    Toast.makeText(ctx, "Install failed" + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show();
                MainActivity.refreshIfOpen();
            }
        }
    }
}
