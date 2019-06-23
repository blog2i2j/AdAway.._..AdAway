/*
 * Copyright (C) 2011-2012 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * This file is part of AdAway.
 *
 * AdAway is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AdAway is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AdAway.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.adaway.helper;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.widget.Toast;

import com.annimon.stream.Stream;

import org.adaway.R;
import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostListItemDao;
import org.adaway.db.dao.HostsSourceDao;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.adaway.db.entity.ListType;
import org.adaway.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.List;

import static org.adaway.db.entity.ListType.BLACK_LIST;
import static org.adaway.db.entity.ListType.REDIRECTION_LIST;
import static org.adaway.db.entity.ListType.WHITE_LIST;
import static org.adaway.util.Constants.TAG;

/**
 * This class is a helper class to import/export user lists and hosts sources to a backup file on sdcard.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class ImportExportHelper {
    /**
     * The request code to identify the write external storage permission in {@link androidx.fragment.app.Fragment#onRequestPermissionsResult(int, java.lang.String[], int[])}.
     */
    public final static int REQUEST_CODE_WRITE_STORAGE_PERMISSION = 10;
    /**
     * The request code to identify the selection of a file in {@link androidx.fragment.app.Fragment#onActivityResult(int, int, Intent)}.
     */
    public final static int REQUEST_CODE_IMPORT = 42;
    /**
     * The default backup file name.
     */
    private static final String BACKUP_FILE_NAME = "adaway-backup.json";

    /**
     * Import a backup file.
     *
     * @param context   The application context.
     * @param backupUri The URI of a backup file.
     */
    public static void importFromBackup(Context context, Uri backupUri) {
        new ImportTask(context).execute(backupUri);
    }

    /**
     * Export all user lists and hosts sources to a backup file on the external storage.
     *
     * @param context The application context.
     */
    public static void exportToBackup(Context context) {
        // Export user lists
        new ExportTask(context).execute();
    }

    private static JSONObject makeBackup(Context context) throws JSONException {
        AppDatabase database = AppDatabase.getInstance(context);
        HostsSourceDao hostsSourceDao = database.hostsSourceDao();
        HostListItemDao hostListItemDao = database.hostsListItemDao();

        List<HostListItem> allHosts = hostListItemDao.getAll();
        List<HostListItem> blockedHosts = Stream.of(allHosts)
                .filter(value -> value.getType() == BLACK_LIST)
                .toList();
        List<HostListItem> allowedHosts = Stream.of(allHosts)
                .filter(value -> value.getType() == WHITE_LIST)
                .toList();
        List<HostListItem> redirectedHosts = Stream.of(allHosts)
                .filter(value -> value.getType() == REDIRECTION_LIST)
                .toList();

        JSONObject backupObject = new JSONObject();
        backupObject.put("sources", buildSourcesBackup(hostsSourceDao.getAll()));
        backupObject.put("blocked", buildListBackup(blockedHosts));
        backupObject.put("allowed", buildListBackup(allowedHosts));
        backupObject.put("redirected", buildListBackup(redirectedHosts));

        return backupObject;
    }

    private static void importBackup(Context context, JSONObject backupObject) throws JSONException {
        AppDatabase database = AppDatabase.getInstance(context);
        HostsSourceDao hostsSourceDao = database.hostsSourceDao();
        HostListItemDao hostListItemDao = database.hostsListItemDao();

        importSourceBackup(hostsSourceDao, backupObject.getJSONArray("sources"));
        importListBackup(hostListItemDao, BLACK_LIST, backupObject.getJSONArray("blocked"));
        importListBackup(hostListItemDao, WHITE_LIST, backupObject.getJSONArray("allowed"));
        importListBackup(hostListItemDao, REDIRECTION_LIST, backupObject.getJSONArray("redirected"));
    }

    private static JSONArray buildSourcesBackup(List<HostsSource> sources) throws JSONException {
        JSONArray sourceArray = new JSONArray();
        for (HostsSource source : sources) {
            sourceArray.put(sourceToJson(source));
        }
        return sourceArray;
    }

    private static void importSourceBackup(HostsSourceDao hostsSourceDao, JSONArray sources) throws JSONException {
        for (int index = 0; index < sources.length(); index++) {
            JSONObject sourceObject = sources.getJSONObject(index);
            hostsSourceDao.insert(sourceFromJson(sourceObject));
        }
    }

    private static JSONArray buildListBackup(List<HostListItem> hosts) throws JSONException {
        JSONArray listArray = new JSONArray();
        for (HostListItem host : hosts) {
            listArray.put(hostToJson(host));
        }
        return listArray;
    }

    private static void importListBackup(HostListItemDao hostListItemDao, ListType type, JSONArray hosts) throws JSONException {
        for (int index = 0; index < hosts.length(); index++) {
            JSONObject hostObject = hosts.getJSONObject(index);
            HostListItem host = hostFromJson(hostObject);
            host.setType(type);
            hostListItemDao.insert(host);
        }
    }

    private static JSONObject sourceToJson(HostsSource source) throws JSONException {
        JSONObject sourceObject = new JSONObject();
        sourceObject.put("url", source.getUrl());
        sourceObject.put("enabled", source.isEnabled());
        return sourceObject;
    }

    private static HostsSource sourceFromJson(JSONObject sourceObject) throws JSONException {
        HostsSource source = new HostsSource();
        source.setUrl(sourceObject.getString("url"));
        source.setEnabled(sourceObject.getBoolean("enabled"));
        return source;
    }

    private static JSONObject hostToJson(HostListItem host) throws JSONException {
        JSONObject hostObject = new JSONObject();
        hostObject.put("host", host.getHost());
        String redirection = host.getRedirection();
        if (redirection != null && !redirection.isEmpty()) {
            hostObject.put("redirect", redirection);
        }
        hostObject.put("enabled", host.isEnabled());
        return hostObject;
    }

    private static HostListItem hostFromJson(JSONObject hostObject) throws JSONException {
        HostListItem host = new HostListItem();
        host.setHost(hostObject.getString("host"));
        if (hostObject.has("redirect")) {
            host.setRedirection(hostObject.getString("redirect"));
        }
        host.setEnabled(hostObject.getBoolean("enabled"));
        return host;
    }

    /**
     * This class is an {@link AsyncTask} to import from a backup file.
     *
     * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
     */
    private static class ImportTask extends AsyncTask<Uri, Void, Boolean> {
        /**
         * A weak reference to application context.
         */
        private final WeakReference<Context> mWeakContext;
        /**
         * The progress dialog.
         */
        private ProgressDialog mProgressDialog;

        /**
         * Constructor.
         *
         * @param context The application context.
         */
        private ImportTask(Context context) {
            // Store context into weak reference to prevent memory leak
            this.mWeakContext = new WeakReference<>(context);
        }

        @Override
        protected Boolean doInBackground(Uri... results) {
            // Check parameters
            if (results.length < 1) {
                return false;
            }
            // Get URI to export lists
            Uri result = results[0];
            // Get context from weak reference
            Context context = this.mWeakContext.get();
            if (context == null) {
                return false;
            }
            // Get input stream from user selected URI
            try (InputStream inputStream = context.getContentResolver().openInputStream(result);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                StringBuilder contentBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    contentBuilder.append(line);
                }
                JSONObject backupObject = new JSONObject(contentBuilder.toString());
                importBackup(context, backupObject);
            } catch (JSONException exception) {
                Log.e(TAG, "Failed to parse backup file.", exception);
                return false;
            } catch (FileNotFoundException exception) {
                Log.e(TAG, "Failed to find backup file.", exception);
                return false;
            } catch (IOException exception) {
                Log.e(TAG, "Failed to read backup file.", exception);
                return false;
            }
            return true;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            // Get context from weak reference
            Context context = this.mWeakContext.get();
            if (context == null) {
                return;
            }
            // Check and show progress dialog
            this.mProgressDialog = new ProgressDialog(context);
            this.mProgressDialog.setMessage(context.getString(R.string.import_dialog));
            this.mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            this.mProgressDialog.setCancelable(false);
            this.mProgressDialog.show();
        }

        @Override
        protected void onPostExecute(Boolean imported) {
            super.onPostExecute(imported);
            // Check progress dialog
            if (this.mProgressDialog != null) {
                this.mProgressDialog.dismiss();
            }
            // Get context from weak reference
            Context context = this.mWeakContext.get();
            if (context == null) {
                return;
            }
            // Display user toast notification
            Toast toast = Toast.makeText(
                    context,
                    context.getString(imported ? R.string.import_success : R.string.import_dialog),
                    Toast.LENGTH_LONG
            );
            toast.show();
        }
    }

    /**
     * This class is an {@link AsyncTask} to export to a backup file.
     *
     * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
     */
    private static class ExportTask extends AsyncTask<Void, Void, Boolean> {
        /**
         * A weak reference to application context.
         */
        private final WeakReference<Context> mWeakContext;
        /**
         * The progress dialog.
         */
        private ProgressDialog mProgressDialog;

        /**
         * Constructor.
         *
         * @param context The application context.
         */
        private ExportTask(Context context) {
            // Store context into weak reference to prevent memory leak
            this.mWeakContext = new WeakReference<>(context);
        }

        @Override
        protected Boolean doInBackground(Void... unused) {
            // Get context from weak reference
            Context context = this.mWeakContext.get();
            if (context == null) {
                // Fail to export
                return false;
            }
            // Check if sdcard can be written
            File sdcard = Environment.getExternalStorageDirectory();
            if (!sdcard.canWrite()) {
                Log.e(TAG, "External storage can not be written.");
                // Fail to export
                return false;
            }
            // Create export file
            File exportFile = new File(sdcard, BACKUP_FILE_NAME);
            // Open writer on the export file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(exportFile))) {
                JSONObject backup = makeBackup(context);
                writer.write(backup.toString(4));
            } catch (JSONException exception) {
                Log.e(TAG, "Failed to generate backup.", exception);
                return false;
            } catch (IOException exception) {
                Log.e(TAG, "Could not write file.", exception);
                return false;
            }
            // Return successfully exported
            return true;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            // Check context weak reference
            Context context = this.mWeakContext.get();
            if (context == null) {
                return;
            }
            // Create and show progress dialog
            this.mProgressDialog = new ProgressDialog(context);
            this.mProgressDialog.setMessage(context.getString(R.string.export_dialog));
            this.mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            this.mProgressDialog.setCancelable(false);
            this.mProgressDialog.show();
        }

        @Override
        protected void onPostExecute(Boolean exported) {
            super.onPostExecute(exported);
            // Check progress dialog
            if (this.mProgressDialog != null) {
                this.mProgressDialog.dismiss();
            }
            // Get context from weak reference
            Context context = this.mWeakContext.get();
            if (context == null) {
                return;
            }
            // Display user toast notification
            Toast toast = Toast.makeText(
                    context,
                    context.getString(exported ? R.string.export_success : R.string.export_failed),
                    Toast.LENGTH_LONG
            );
            toast.show();
        }
    }
}
