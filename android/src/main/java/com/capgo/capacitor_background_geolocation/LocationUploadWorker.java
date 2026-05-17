package com.capgo.capacitor_background_geolocation;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.getcapacitor.Logger;
import org.json.JSONException;
import org.json.JSONObject;

// KIVO fork addition. WorkManager worker that runs the HTTP POST for a single
// location ping. Returns Result.retry() on transient failures (network / 5xx),
// triggering WorkManager's exponential backoff.
public class LocationUploadWorker extends Worker {

    public LocationUploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String payload = getInputData().getString(LocationUploadStore.EXTRA_LOCATION_PAYLOAD);
        if (payload == null || payload.isEmpty()) {
            return Result.success();
        }
        try {
            LocationUploadStore.sendUpload(getApplicationContext(), new JSONObject(payload));
            return Result.success();
        } catch (JSONException exception) {
            Logger.error("Invalid location upload payload", exception);
            return Result.failure();
        } catch (Exception exception) {
            Logger.error("Failed to upload location", exception);
            return Result.retry();
        }
    }
}
