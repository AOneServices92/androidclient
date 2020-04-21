/*
 * Kontalk Android client
 * Copyright (C) 2020 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Properties;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import android.content.Context;

import org.kontalk.Kontalk;
import org.kontalk.Log;
import org.kontalk.R;
import org.kontalk.client.EndpointServer;
import org.kontalk.client.ServerList;
import org.kontalk.reporting.ReportingManager;
import org.kontalk.service.msgcenter.MessageCenterService;
import org.kontalk.service.msgcenter.event.ConnectedEvent;
import org.kontalk.service.msgcenter.event.ServerListEvent;
import org.kontalk.service.msgcenter.event.ServerListRequest;
import org.kontalk.util.Preferences;
import org.kontalk.util.SystemUtils;


/**
 * Worker for downloading and caching locally a server list.
 * This class doesn't need to be configured: it hides all the logic of picking
 * a random server, connecting to it, downloading the server list and saving it
 * in the application cache. Finally, it restarts the message center.
 *
 * @author Daniele Ricci
 */
// TODO downloaded list should be saved in account user data
public class ServerListUpdater {
    private static final String TAG = ServerListUpdater.class.getSimpleName();

    private static ServerList sCurrentList;

    private final Context mContext;
    private UpdaterListener mListener;

    private EventBus mServiceBus = MessageCenterService.bus();

    public ServerListUpdater(Context context) {
        mContext = context;
    }

    public void setListener(UpdaterListener listener) {
        mListener = listener;
    }

    public void start() {
        /*
         * We have a server list - either builtin or cached. Now pick a random
         * server from the list and contact it for the latest server list.
         */
        EndpointServer random = Kontalk.get().getEndpointServer();

        /* no server found -- notify to user */
        if (random == null) {
            Log.i(TAG, "no list to pick a random server from - aborting");

            // notify to UI
            if (mListener != null)
                mListener.noData();

            return;
        }

        // check for network
        if (!SystemUtils.isNetworkConnectionAvailable(mContext)) {
            if (mListener != null)
                mListener.networkNotAvailable();
            return;
        }

        // check for offline mode
        if (Preferences.getOfflineMode()) {
            if (mListener != null)
                mListener.offlineModeEnabled();
            return;
        }

        // register for and request connection status
        mServiceBus.register(this);

        MessageCenterService.start(mContext);
    }

    public void cancel() {
        unregisterReceiver();
    }

    private void unregisterReceiver() {
        mServiceBus.unregister(this);
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.BACKGROUND)
    public void onConnected(ConnectedEvent event) {
        // request serverlist
        mServiceBus.post(new ServerListRequest(null));
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onServerList(ServerListEvent event) {
        // we don't need this any more
        unregisterReceiver();

        if (event.servers != null && event.servers.length > 0) {
            Date now = new Date();
            ServerList list = new ServerList(now);

            for (int i = 0; i < event.servers.length; i++) {
                String item = event.servers[i];
                list.add(new EndpointServer(item));
            }

            Properties prop = list.toProperties();

            OutputStream out = null;
            try {
                out = new FileOutputStream(getCachedListFile(mContext));
                prop.store(out, null);
                out.close();

                // update cached list
                sCurrentList = list;

                if (mListener != null)
                    mListener.updated(list);
            }
            catch (IOException e) {
                if (mListener != null)
                    mListener.error(e);
            }
            finally {
                try {
                    if (out != null)
                        out.close();
                }
                catch (IOException e) {
                    // ignored
                }
            }
        }

        else {
            if (mListener != null)
                mListener.error(null);
        }
    }

    public static void deleteCachedList(Context context) {
        File file = getCachedListFile(context);
        file.delete();
    }

    /**
     * The path to the locally cached downloaded server list.
     */
    private static File getCachedListFile(Context context) {
        return new File(context.getCacheDir(), "serverlist.properties");
    }

    private static ServerList parseList(InputStream in) throws IOException {
        Properties prop = new Properties();
        prop.load(in);
        return ServerList.fromProperties(prop);
    }

    private static ServerList parseBuiltinList(Context context) throws IOException {
        InputStream in = null;
        try {
            in = context.getResources()
                .openRawResource(R.raw.serverlist);
            return parseList(in);
        }
        finally {
            try {
                in.close();
            }
            catch (Exception e) {
                // ignored
            }
        }
    }

    private static ServerList parseCachedList(Context context) throws IOException {
        InputStream in = null;
        try {
            in = new FileInputStream(getCachedListFile(context));
            return parseList(in);
        }
        finally {
            try {
                in.close();
            }
            catch (Exception e) {
                // ignored
            }
        }
    }

    /**
     * Returns (and loads if necessary) the current server list.
     */
    public static ServerList getCurrentList(Context context) {
        if (sCurrentList != null)
            return sCurrentList;

        ServerList builtin = null;
        try {
            builtin = parseBuiltinList(context);
            sCurrentList = parseCachedList(context);
            // use cached list if recent than
            if (builtin.getDate().after(sCurrentList.getDate()))
                sCurrentList = builtin;
        }
        catch (IOException e) {
            if (builtin == null) {
                ReportingManager.logException(e);
                Log.w(TAG, "unable to load builtin server list", e);
            }
            sCurrentList = builtin;
        }

        return sCurrentList;
    }

    public interface UpdaterListener {
        /**
         * Called if either the cached list or the built-in list cannot be loaded.
         */
        void noData();

        /**
         * Called when network is not available.
         */
        void networkNotAvailable();

        /**
         * Called when offline mode is active.
         */
        void offlineModeEnabled();

        /**
         * Called if an error occurs during update.
         */

        void error(Throwable e);

        /**
         * Called when list update has finished.
         */
        void updated(ServerList list);
    }
}
