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

package org.kontalk;

import java.io.IOException;
import java.io.OutputStream;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

import com.vanniktech.emoji.EmojiManager;
import com.vanniktech.emoji.ios.IosEmojiProvider;

import org.bouncycastle.openpgp.PGPException;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import androidx.annotation.RequiresApi;
import androidx.multidex.MultiDexApplication;

import org.kontalk.authenticator.Authenticator;
import org.kontalk.authenticator.MyAccount;
import org.kontalk.client.EndpointServer;
import org.kontalk.client.ServerList;
import org.kontalk.crypto.PGP;
import org.kontalk.crypto.PersonalKey;
import org.kontalk.data.Contact;
import org.kontalk.provider.MessagesProviderClient;
import org.kontalk.reporting.ReportingManager;
import org.kontalk.service.DownloadService;
import org.kontalk.service.NetworkStateReceiver;
import org.kontalk.service.ServerListUpdater;
import org.kontalk.service.SystemBootStartup;
import org.kontalk.service.UploadService;
import org.kontalk.service.msgcenter.IPushService;
import org.kontalk.service.msgcenter.MessageCenterService;
import org.kontalk.service.msgcenter.PushServiceManager;
import org.kontalk.service.msgcenter.SecureConnectionManager;
import org.kontalk.sync.SyncAdapter;
import org.kontalk.ui.ComposeMessage;
import org.kontalk.ui.MessagingNotification;
import org.kontalk.ui.SearchActivity;
import org.kontalk.util.CustomSimpleXmppStringprep;
import org.kontalk.util.DataUtils;
import org.kontalk.util.Preferences;
import org.kontalk.util.Showcase;
import org.kontalk.util.SystemUtils;


/**
 * The Application.
 * @author Daniele Ricci
 */
public class Kontalk extends MultiDexApplication {
    public static final String TAG = Kontalk.class.getSimpleName();

    /**
     * The singleton instance.
     * Used to use {@link #getApplicationContext()} to retrieve this instance,
     * but apparently there are places where it doesn't return a Kontalk object.
     */
    private static Kontalk sInstance;

    private MyAccount mDefaultAccount;

    /**
     * Keep-alive reference counter.
     * This is used throughout the activities to keep track of application
     * usage. Please note that this is not to be confused with
     * {@link MessageCenterService.IdleConnectionHandler} reference counter,
     * since this counter here is used only by {@link NetworkStateReceiver} and
     * a few others to check if the Message Center should be started or not.<br>
     * Call {@link #hold} to increment the counter, {@link #release} to
     * decrement it.
     */
    @SuppressWarnings("JavadocReference")
    private int mRefCounter;

    /** Messages controller singleton instance. */
    private MessagesController mMessagesController;

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener =
        new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                // debug log
                if ("pref_debug_log".equals(key)) {
                    Log.init(Kontalk.this);
                }
                // foreground service
                else if ("pref_foreground_service".equals(key)) {
                    MessageCenterService.start(Kontalk.this);
                }
                // reporting opt-in
                else if ("pref_reporting".equals(key)) {
                    if (Preferences.isReportingEnabled(Kontalk.this)) {
                        ReportingManager.register(Kontalk.this);
                    }
                    else {
                        ReportingManager.unregister(Kontalk.this);
                    }
                }
                // UI theme
                else if ("pref_ui_theme".equals(key)) {
                    Preferences.applyTheme(Kontalk.this);
                }
                // actions requiring an account
                else if (getDefaultAccount() != null) {
                    // manual server address
                    if ("pref_network_uri".equals(key)) {
                        // just restart the message center for now
                        Log.w(TAG, "network address changed");
                        MessageCenterService.restart(Kontalk.this);
                    }
                    // hide presence flag / encrypt user data flag
                    else if ("pref_hide_presence".equals(key) || "pref_encrypt_userdata".equals(key)) {
                        MessageCenterService.updateStatus(Preferences.getStatusMessage());
                    }
                    // changing remove prefix
                    else if ("pref_remove_prefix".equals(key)) {
                        SyncAdapter.requestSync(Kontalk.this, true);
                    }
                }
            }
        };

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        // init preferences
        // This must be done before registering the reporting manager
        // because we need access to the reporting opt-in preference.
        // However this call will not be reported if it crashes
        Preferences.init(this);

        // init logging system
        // done after preferences because we need to access debug log preference
        Log.init(this);

        // register reporting manager
        if (Preferences.isReportingEnabled(this))
            ReportingManager.register(this);

        // hacks
        CustomSimpleXmppStringprep.setup();
        disableHintsForUpgrade();

        // register security provider
        SecureConnectionManager.init(this);
        try {
            PGP.registerProvider();
        }
        catch (PGP.PRNGFixException e) {
            ReportingManager.logException(e);
            Log.w(TAG, "Unable to install PRNG fix - ignoring", e);
        }

        // init contacts
        Contact.init(this, new Handler());

        // init notification system
        MessagingNotification.init(this);

        // init the messages controller
        initMessagesController();

        // register network state receiver manually
        if (!SystemUtils.isReceivingNetworkStateChanges()) {
            registerNetworkStateReceiver();
        }

        // init UI settings
        Preferences.initUI(this);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(mPrefListener);

        // TODO listen for changes to phone numbers

        AccountManager am = AccountManager.get(this);
        MyAccount account = getDefaultAccount();
        if (account != null) {
            // register account change listener
            final OnAccountsUpdateListener listener = new OnAccountsUpdateListener() {
                @Override
                public void onAccountsUpdated(Account[] accounts) {
                    Account my = null;
                    for (Account acc : accounts) {
                        if (acc.type.equals(Authenticator.ACCOUNT_TYPE)) {
                            my = acc;
                            break;
                        }
                    }

                    // account removed!!! Shutdown everything.
                    if (my == null) {
                        Log.w(TAG, "my account has been removed, shutting down");
                        // stop message center
                        MessageCenterService.stop(Kontalk.this);
                        // disable components
                        setServicesEnabled(Kontalk.this, false);
                        // unregister from push notifications
                        IPushService pushMgr = PushServiceManager.getInstance(Kontalk.this);
                        if (pushMgr != null && pushMgr.isServiceAvailable())
                            pushMgr.unregister(PushServiceManager.getDefaultListener());
                        // delete all messages
                        MessagesProviderClient.deleteDatabase(Kontalk.this);
                        // invalidate cached personal key
                        invalidatePersonalKey();
                    }
                }
            };

            // register listener to handle account removal
            am.addOnAccountsUpdatedListener(listener, null, true);

            // TODO remove after a few release iterations
            if (Authenticator.getDefaultServiceTermsURL(this) == null) {
                // default service terms url
                am.setUserData(account.getSystemAccount(),
                    Authenticator.DATA_SERVICE_TERMS_URL,
                    getString(R.string.help_default_KPN_service_terms_url));
            }

            // TODO remove after a few release iterations
            if (Authenticator.getDefaultServerList(this) == null) {
                // default server list
                ServerList list = ServerListUpdater.getCurrentList(this);
                am.setUserData(account.getSystemAccount(),
                    Authenticator.DATA_SERVER_LIST,
                    DataUtils.serializeProperties(list.toProperties()));
            }
        }
        else {
            // ensure everything is cleared up
            MessagesProviderClient.deleteDatabase(Kontalk.this);
        }

        // enable/disable components
        setServicesEnabled(this, account != null);

        // disable backend services in offline mode (helps after installs)
        if (account != null && Preferences.getOfflineMode())
            setBackendEnabled(this, false);
    }

    /**
     * @deprecated To be removed after the first release it gets into.
     */
    @Deprecated
    private void disableHintsForUpgrade() {
        if (getDefaultAccount() != null) {
            Showcase.disableAllHints();
        }
    }

    @Nullable
    public MyAccount getDefaultAccount() {
        if (mDefaultAccount == null) {
            mDefaultAccount = Authenticator.getDefaultAccount(this);
        }
        return mDefaultAccount;
    }

    public PersonalKey getPersonalKey() throws PGPException, IOException, CertificateException {
        MyAccount account = getDefaultAccount();
        return account != null ? account.getPersonalKey() : null;
    }

    /**
     * Returns a random server from the cached list or the user-defined server.
     * @deprecated Server should only be retrieved from account user data
     */
    @Deprecated
    public EndpointServer getEndpointServer() {
        String customUri = Preferences.getServerURI();
        if (!TextUtils.isEmpty(customUri)) {
            try {
                return new EndpointServer(customUri);
            }
            catch (Exception e) {
                // custom is not valid - take one from list
            }
        }

        // return server stored in the default account
        MyAccount account = getDefaultAccount();
        return account != null ? account.getServer() : null;
    }

    public void exportPersonalKey(OutputStream out, String exportPassphrase)
            throws CertificateException, PGPException, IOException,
                KeyStoreException, NoSuchAlgorithmException {

        Authenticator.exportDefaultPersonalKey(this, out, exportPassphrase, true);
    }

    /** Invalidates the cached personal key. */
    public void invalidatePersonalKey() {
        mDefaultAccount = null;
    }

    private void initMessagesController() {
        mMessagesController = new MessagesController(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void registerNetworkStateReceiver() {
        registerReceiver(new NetworkStateReceiver(),
            new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    /** Returns the messages controller singleton instance. */
    public MessagesController getMessagesController() {
        return mMessagesController;
    }

    /** Returns true if we are using a two-panes UI. */
    public static boolean hasTwoPanesUI(Context context) {
        return context.getResources().getBoolean(R.bool.has_two_panes);
    }

    /** Returns the singleton {@link Kontalk} instance. */
    public static Kontalk get() {
        return sInstance;
    }

    /** Enable/disable application components when account is added or removed. */
    public static void setServicesEnabled(Context context, boolean enabled) {
        PackageManager pm = context.getPackageManager();
        enableService(context, pm, ComposeMessage.class, enabled);
        enableService(context, pm, SearchActivity.class, enabled);
        setBackendEnabled(context, enabled);
    }

    /** Enable/disable backend application components when account is added or removed. */
    public static void setBackendEnabled(Context context, boolean enabled) {
        PackageManager pm = context.getPackageManager();
        enableService(context, pm, MessageCenterService.class, enabled);
        // check for backward compatibility
        if (enabled) {
            // DownloadService can and should be used at any time if needed
            enableService(context, pm, DownloadService.class, true);
        }
        enableService(context, pm, UploadService.class, enabled);
        enableService(context, pm, SystemBootStartup.class, enabled);
        enableService(context, pm, NetworkStateReceiver.class, enabled);
    }

    private static void enableService(Context context, PackageManager pm, Class<?> klass, boolean enabled) {
        pm.setComponentEnabledSetting(new ComponentName(context, klass),
            enabled ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    /** Increments the reference counter. */
    public void hold() {
        mRefCounter++;
    }

    /** Decrements the reference counter. */
    public void release() {
        if (mRefCounter > 0)
            mRefCounter--;
    }

    /** Returns true if the reference counter is greater than zero. */
    public boolean hasReference() {
        return mRefCounter > 0;
    }

    /**
     * Returns the reference counter. Used only by the message center to restore
     * its reference counter when restarting or handling exceptions.
     */
    public int getReferenceCounter() {
        return mRefCounter;
    }

}
