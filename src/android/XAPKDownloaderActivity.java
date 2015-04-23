package org.apache.cordova.xapkreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.Messenger;
import android.util.Log;

import com.google.android.vending.expansion.downloader.DownloadProgressInfo;
import com.google.android.vending.expansion.downloader.DownloaderClientMarshaller;
import com.google.android.vending.expansion.downloader.DownloaderServiceMarshaller;
import com.google.android.vending.expansion.downloader.Helpers;
import com.google.android.vending.expansion.downloader.IDownloaderClient;
import com.google.android.vending.expansion.downloader.IDownloaderService;
import com.google.android.vending.expansion.downloader.IStub;

public class XAPKDownloaderActivity extends Activity implements IDownloaderClient {

    private IStub mDownloaderClientStub;

    private IDownloaderService mRemoteService;

    private ProgressDialog mProgressDialog;

    private static final String LOG_TAG = "XAPKDownloader";

    /**
     * Determine if the file is present and match the required size. Free
     * applications should definitely consider doing this, as this allows the
     * application to be launched for the first time without having a network
     * connection present. Paid applications that use LVL should probably do at
     * least one LVL check that requires the network to be present, so this is
     * not as necessary.
     *
     * @return true if they are present.
     */
    boolean expansionFilesDelivered(int mainVersionCode, int patchVersionCode, long mainFileSize, long patchFileSize) {
        String fileName = Helpers.getExpansionAPKFileName(this, true, mainVersionCode);
        if (!Helpers.doesFileExist(this, fileName, mainFileSize, false)) {
            Log.e(LOG_TAG, "ExpansionAPKFile doesn't exist or has a wrong size (" + fileName + ").");
            if(patchVersionCode != 0) {
                fileName = Helpers.getExpansionAPKFileName(this, false, patchVersionCode);
                if (!Helpers.doesFileExist(this, fileName, patchFileSize, false)) {
                    Log.e(LOG_TAG, "ExpansionAPKFile doesn't exist or has a wrong size (" + fileName + ").");
                    return false;
                }
            }
            else {
               return false;
            }
        }
        return true;
    }


    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        int mainVersionCode = this.getIntent().getIntExtra("mainVersionCode", 1);
        int patchVersionCode = this.getIntent().getIntExtra("patchVersionCode", 0);
        long mainFileSize = this.getIntent().getLongExtra("mainFileSize", 0L);
        long patchFileSize = this.getIntent().getLongExtra("patchFileSize", 0L);
        boolean downloadOption = this.getIntent().getBooleanExtra("downloadOption",true); 

        // Check if expansion files are available before going any further
        if (!expansionFilesDelivered(mainVersionCode, patchVersionCode, mainFileSize, patchFileSize)) {

            if(downloadOption = true) {
                try {
                    Intent launchIntent = this.getIntent();

                    // Build an Intent to start this activity from the Notification
                    Intent notifierIntent = new Intent(XAPKDownloaderActivity.this, XAPKDownloaderActivity.this.getClass());
                    notifierIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    notifierIntent.setAction(launchIntent.getAction());

                    if (launchIntent.getCategories() != null) {
                        for (String category : launchIntent.getCategories()) {
                            notifierIntent.addCategory(category);
                        }
                    }

                    PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notifierIntent, PendingIntent.FLAG_UPDATE_CURRENT);

                    // Start the download service (if required)
                    Log.v(LOG_TAG, "Start the download service");
                    int startResult = DownloaderClientMarshaller.startDownloadServiceIfRequired(this, pendingIntent, XAPKDownloaderService.class);

                    // If download has started, initialize activity to show progress
                    if (startResult != DownloaderClientMarshaller.NO_DOWNLOAD_REQUIRED) {
                        Log.v(LOG_TAG, "initialize activity to show progress");
                        // Instantiate a member instance of IStub
                        mDownloaderClientStub = DownloaderClientMarshaller.CreateStub(this, XAPKDownloaderService.class);
                        // Shows download progress
                        mProgressDialog = new ProgressDialog(XAPKDownloaderActivity.this);
                        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                        mProgressDialog.setMessage(getResources().getString(getResources().getIdentifier("downloading_assets", "string", getPackageName())));
                        mProgressDialog.setCancelable(false);
                        mProgressDialog.show();
                        return;
                    }
                    // If the download wasn't necessary, fall through to start the app
                    else {
                        Log.v(LOG_TAG, "No download required");
                    }
                }
                catch (NameNotFoundException e) {
                    Log.e(LOG_TAG, "Cannot find own package! MAYDAY!");
                    e.printStackTrace();
                }
                catch (Exception e) {
                    Log.e(LOG_TAG, e.getMessage());
                    e.printStackTrace();
                }
            }

        }
        else {
            Log.v(LOG_TAG, "File is already present");
        }

        // finish activity
        finish();
    }

    /**
     * Connect the stub to our service on start.
     */
    @Override
    protected void onStart() {
        if (null != mDownloaderClientStub) {
            mDownloaderClientStub.connect(this);
        }
        super.onStart();
    }

    /**
     * Connect the stub from our service on resume
     */
    @Override
    protected void onResume() {
        if (null != mDownloaderClientStub) {
            mDownloaderClientStub.connect(this);
        }
        super.onResume();
    }

    /**
     * Disconnect the stub from our service on stop
     */
    @Override
    protected void onStop() {
        if (null != mDownloaderClientStub) {
            mDownloaderClientStub.disconnect(this);
        }
        super.onStop();
    }

    @Override
    public void onServiceConnected(Messenger m) {
        mRemoteService = DownloaderServiceMarshaller.CreateProxy(m);
        mRemoteService.onClientUpdated(mDownloaderClientStub.getMessenger());
    }

    @Override
    public void onDownloadProgress(DownloadProgressInfo progress) {
        long percents = progress.mOverallProgress * 100 / progress.mOverallTotal;
        Log.v(LOG_TAG, "DownloadProgress:"+Long.toString(percents) + "%");
        mProgressDialog.setProgress((int) percents);
    }

    @Override
    public void onDownloadStateChanged(int newState) {
        Log.v(LOG_TAG, "DownloadStateChanged : " + getString(Helpers.getDownloaderStringResourceIDFromState(newState)));

        switch (newState) {
            case STATE_DOWNLOADING:
                Log.v(LOG_TAG, "Downloading...");
                break;
            case STATE_COMPLETED: // The download was finished
                // validateXAPKZipFiles();
                mProgressDialog.setMessage(getResources().getString(getResources().getIdentifier("preparing_assets", "string", getPackageName())));
                // dismiss progress dialog
                mProgressDialog.dismiss();
                // finish activity
                finish();
                break;
            case STATE_FAILED_UNLICENSED:
            case STATE_FAILED_FETCHING_URL:
            case STATE_FAILED_SDCARD_FULL:
            case STATE_FAILED_CANCELED:
            case STATE_FAILED:
                Builder alert = new AlertDialog.Builder(this);
                alert.setTitle(getResources().getString(getResources().getIdentifier("error", "string", getPackageName())));
                alert.setMessage(getResources().getString(getResources().getIdentifier("download_failed", "string", getPackageName())));
                alert.setNeutralButton(getResources().getString(getResources().getIdentifier("close", "string", getPackageName())), null);
                alert.show();
                break;
        }
    }
}