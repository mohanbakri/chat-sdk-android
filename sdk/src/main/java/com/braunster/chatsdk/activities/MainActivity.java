package com.braunster.chatsdk.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import com.astuetz.PagerSlidingTabStrip;
import com.braunster.chatsdk.R;
import com.braunster.chatsdk.Utils.Debug;
import com.braunster.chatsdk.Utils.NotificationUtils;
import com.braunster.chatsdk.adapter.PagerAdapterTabs;
import com.braunster.chatsdk.dao.BMessage;
import com.braunster.chatsdk.dao.BThread;
import com.braunster.chatsdk.dao.BUser;
import com.braunster.chatsdk.dao.core.DaoCore;
import com.braunster.chatsdk.fragments.BaseFragment;
import com.braunster.chatsdk.fragments.ProfileFragment;
import com.braunster.chatsdk.network.BDefines;
import com.braunster.chatsdk.network.BNetworkManager;
import com.braunster.chatsdk.network.events.AppEventListener;
import com.braunster.chatsdk.network.firebase.EventManager;

import java.io.File;
import java.util.concurrent.Callable;


public class MainActivity extends BaseActivity {

    // TODO add option to save up app in external storage. http://developer.android.com/guide/topics/data/install-location.html
    // TODO stack notification like whatsapp and gamil http://developer.android.com/reference/android/app/Notification.InboxStyle.html

    private static final String TAG = MainActivity.class.getSimpleName();
    private static boolean DEBUG = Debug.MainActivity;

    private PagerSlidingTabStrip tabs;
    private ViewPager pager;
    private PagerAdapterTabs adapter;

    private static final String FIRST_TIME_IN_APP = "First_Time_In_App";
    private static final String PAGE_ADAPTER_POS = "page_adapter_pos";

    public static final String Action_Contacts_Added = "com.braunster.androidchatsdk.action.contact_added";
    public static final String Action_Logged_Out = "com.braunster.androidchatsdk.action.logged_out";

    private int pageAdapterPos = -1;

    /** For the double back click exit mode.*/
    private boolean doubleBackToExitPressedOnce = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (DEBUG) Log.v(TAG, "onCreate");

        setContentView(R.layout.chat_sdk_activity_view_pager);

        firstTimeInApp();
        initViews();
        initToast();

        enableCheckOnlineOnResumed(true);

        if (!fromLoginActivity && savedInstanceState != null)
        {
            if (DEBUG) Log.v(TAG, "Saved Instance is not null, "  + savedInstanceState.getInt(PAGE_ADAPTER_POS));
            pager.setCurrentItem(savedInstanceState.getInt(PAGE_ADAPTER_POS));
        }
    }


    @Override
    protected void onPause() {
        super.onPause();
        if (DEBUG) Log.v(TAG, "onPause");

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (DEBUG) Log.v(TAG, "onResume");

        EventManager.getInstance().removeEventByTag(appEventListener.getTag());
        EventManager.getInstance().addEventIfNotExist(appEventListener);

        tabs.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            private int lastPage = 0;
            private int refreshContactsInterval = 4000;
            private long lastContactsRefresh = 0;

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
//                if (DEBUG) Log.v(TAG, "onPageScrolled");
            }

            @Override
            public void onPageSelected(int position) {
                if (DEBUG) Log.v(TAG, "onPageSelected, Pos: " + position + ", Last: " + lastPage);

                // If the user leaves the profile page check tell the fragment to update index and metadata if needed.
                if (lastPage == PagerAdapterTabs.Profile)
                    ((ProfileFragment) getFragment(PagerAdapterTabs.Profile)).updateProfileIfNeeded();

                pageAdapterPos = position;

                lastPage = position;
            }

            @Override
            public void onPageScrollStateChanged(int state) {
//                if (DEBUG) Log.v(TAG, "onPageScrollStateChanged");
            }
        });

        IntentFilter intentFilter = new IntentFilter(Action_Contacts_Added);
        registerReceiver(contactsAddedReceiver, intentFilter);

        IntentFilter intentFilter2 = new IntentFilter(Action_Logged_Out);
        registerReceiver(logoutReceiver, intentFilter2);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (DEBUG) Log.v(TAG, "onNewIntent");
        if (adapter != null)
        {
            BaseFragment pro = getFragment(PagerAdapterTabs.Profile), conv = getFragment(PagerAdapterTabs.Conversations);

            if (conv!=null)
                conv.refreshOnBackground();

            if (pro != null)
                pro.refresh();
        }
    }

    private AppEventListener appEventListener = new AppEventListener("MainActivity") {
        private final int uiUpdateDelay = 3000;
        private UIUpdater uiUpdaterDetailsChanged, uiUpdaterThreadDetailsChangedPublic, uiUpdaterThreadDetailsChangedPrivate, uiUpdaterMessages;

        @Override
        public boolean onThreadDetailsChanged(final String threadId) {
            super.onThreadDetailsChanged(threadId);

            if (DEBUG) Log.v(TAG, "onThreadDetailsChanged");

            BThread thread = DaoCore.fetchEntityWithEntityID(BThread.class, threadId);

            updateForThread(thread);



            return false;
        }

        @Override
        public boolean onUserAddedToThread(String threadId, String userId) {
            if (DEBUG) Log.v(TAG, "onUserAddedToThread");
            return false;
        }

        @Override
        public boolean onUserDetailsChange(BUser user) {
            if (DEBUG) Log.v(TAG, "onUserDetailsChange");

            if (uiUpdaterDetailsChanged != null)
                uiUpdaterDetailsChanged.setKilled(true);

            handler.removeCallbacks(uiUpdaterDetailsChanged);

            uiUpdaterDetailsChanged = new UIUpdater(){
                @Override
                public void run() {
                    if (DEBUG) Log.d(TAG, "Run");
                    if (!isKilled())
                    {
                        if (DEBUG) Log.d(TAG, "Not killed");
                        BaseFragment contacs, conv;
                        contacs = getFragment(PagerAdapterTabs.Contacts);
                        conv = getFragment(PagerAdapterTabs.Conversations);

                        if (contacs!=null)
                            contacs.loadDataOnBackground();

                        if (conv!= null)
                            conv.loadDataOnBackground();
                    }
                }
            };

            handler.postDelayed(uiUpdaterDetailsChanged, uiUpdateDelay);

            return false;
        }

        @Override
        public boolean onMessageReceived(final BMessage message) {
            if (DEBUG) Log.v(TAG, "onMessageReceived");

            // Only notify for private threads.
            updateForThread(message.getBThreadOwner());

            if (message.getBThreadOwner().getType() == BThread.Type.Public) {
                return true;
            }

            // Make sure the message that incoming is not the user message.
            if (message.getBUserSender().getEntityID().equals(
                    BNetworkManager.sharedManager().getNetworkAdapter().currentUser().getEntityID()))
                return false;

            if (uiUpdaterMessages != null)
                uiUpdaterMessages.setKilled(true);

            handler.removeCallbacks(uiUpdaterMessages);

            uiUpdaterMessages = new UIUpdater(){

                @Override
                public void run() {
                    if (!isKilled())
                    {
                        // We check to see that the ChatActivity is not listening to this messages so we wont alert twice.
                        if (!EventManager.getInstance().isEventTagExist(ChatActivity.MessageListenerTAG + message.getOwnerThread())) {
                            NotificationUtils.createMessageNotification(MainActivity.this, message);
                        }
                    }
                }
            };

            handler.postDelayed(uiUpdaterMessages, uiUpdateDelay);

            return false;
        }

        private void updateForThread(BThread thread){
            if (thread.getType() == BThread.Type.Private)
            {
                if (uiUpdaterThreadDetailsChangedPrivate != null)
                {
                    uiUpdaterThreadDetailsChangedPrivate.setKilled(true);
                    handler.removeCallbacks(uiUpdaterThreadDetailsChangedPrivate);
                }

                uiUpdaterThreadDetailsChangedPrivate = new UIUpdater(){

                    @Override
                    public void run() {
                        if (!isKilled())
                        {
                            BaseFragment fragment;
                            fragment = getFragment(PagerAdapterTabs.Conversations);

                            if (fragment != null)
                                fragment.loadDataOnBackground();
                        }
                    }
                };
                handler.postDelayed(uiUpdaterThreadDetailsChangedPrivate, uiUpdateDelay);
            }
            else if (thread.getType() == BThread.Type.Public)
            {
                if (uiUpdaterThreadDetailsChangedPublic != null)
                {
                    uiUpdaterThreadDetailsChangedPublic.setKilled(true);
                    handler.removeCallbacks(uiUpdaterThreadDetailsChangedPublic);
                }

                uiUpdaterThreadDetailsChangedPublic = new UIUpdater(){

                    @Override
                    public void run() {
                        if (!isKilled())
                        {
                            BaseFragment fragment;
                            fragment = getFragment(PagerAdapterTabs.ChatRooms);

                            if (fragment != null)
                                fragment.loadDataOnBackground();
                        }
                    }
                };
                handler.postDelayed(uiUpdaterThreadDetailsChangedPublic, uiUpdateDelay);
            }
        }
    };

    abstract class UIUpdater implements Runnable{

        private boolean killed = false;

        public void setKilled(boolean killed) {
            this.killed = killed;
        }

        public boolean isKilled() {
            return killed;
        }
    }

    Handler handler = new Handler();

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(PAGE_ADAPTER_POS, pageAdapterPos);
    }

    private void initViews(){
        pager = (ViewPager) findViewById(R.id.pager);

        tabs = (PagerSlidingTabStrip) findViewById(R.id.tabs);

        adapter = new PagerAdapterTabs(getSupportFragmentManager());

        pager.setAdapter(adapter);

        tabs.setViewPager(pager);

        pager.setOffscreenPageLimit(3);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_chat_sdk, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.android_settings) {
            DaoCore.printUsersData();
            EventManager.getInstance().printDataReport();
//            EventManager.getInstance().removeAll();
            return true;
        }
        else   if (item.getItemId() == R.id.contact_developer) {
            Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts(
                    "mailto", BDefines.ContactDeveloper_Email, null));
            emailIntent.putExtra(Intent.EXTRA_SUBJECT, BDefines.ContactDeveloper_Subject);
            startActivity(Intent.createChooser(emailIntent, BDefines.ContactDeveloper_DialogTitle));

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(contactsAddedReceiver);
        unregisterReceiver(logoutReceiver);
    }

    private void firstTimeInApp(){
        //TODO handle no SDCARD!
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(FIRST_TIME_IN_APP, true))
        {
            if (DEBUG) Log.d(TAG, "First time in app");
            if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)){
                if (DEBUG) Log.d(TAG, "No SDCARD");
            } else {
                File directory = new File(Environment.getExternalStorageDirectory()+ File.separator+"AndroidChatSDK");
                if (DEBUG) Log.d(TAG, "Creating app directory");
                    directory.mkdirs();
            }

            PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(FIRST_TIME_IN_APP, false).apply();
        }
    }

    /** Refresh the contacts fragment when a contact added action is received.*/
    private BroadcastReceiver contactsAddedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Action_Contacts_Added))
            {
                BaseFragment contacts = getFragment(PagerAdapterTabs.Contacts);

                if (contacts != null)
                    contacts.refreshOnBackground();

                if (intent.getExtras().containsKey(SearchActivity.USER_IDS_LIST))
                {
                    String[] ids = intent.getStringArrayExtra(SearchActivity.USER_IDS_LIST);
                    for (String id : ids)
                        EventManager.getInstance().handleUsersDetailsChange(id);
                }
            }
        }
    };

    /** Clear Fragments data when logged out.*/
    private BroadcastReceiver logoutReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Action_Logged_Out))
            {
                BaseFragment contacts = getFragment(PagerAdapterTabs.Contacts);

                if (contacts != null)
                    contacts.clearData();

                BaseFragment conv = getFragment(PagerAdapterTabs.Conversations);

                if (conv != null)
                    conv.clearData();

                BaseFragment pro = getFragment(PagerAdapterTabs.Profile);

                if (pro != null)
                    pro.clearData();
            }
        }
    };

    /* Exit Stuff*/
    @Override
    public void onBackPressed() {

        switch (BDefines.Defaults.SDKExitMode)
        {
            case BDefines.Exit.EXIT_MODE_DIALOG:
                // Show alert dialog, Positive response is just dismiss the dialog, Negative will close the app.
                showAlertDialog("", getResources().getString(R.string.alert_exit), getResources().getString(R.string.exit),
                        getResources().getString(R.string.stay), null, new CloseApp());
                break;

            case BDefines.Exit.EXIT_MODE_DOUBLE_BACK:
                if (doubleBackToExitPressedOnce) {
                    try {
                        new CloseApp().call();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return;
                }

                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        doubleBackToExitPressedOnce = false;
                    }
                }, BDefines.Exit.DOUBLE_CLICK_INTERVAL);

                this.doubleBackToExitPressedOnce = true;
                showAlertToast("Please click back again to exit");
                break;

            case BDefines.Exit.EXIT_MODE_NONE:
                try {
                    new CloseApp().call();
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
                break;
        }
    }

    /** Close the app when called.*/
    class CloseApp implements Callable{
        @Override
        public Object call() throws Exception {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return null;
        }
    }

    /** After screen orientation chage the getItem from the fragment page adapter is no null but it is not visible to the user
     *  so we have to use this workaround so when we call any method on the wanted fragment the fragment will respond.
     *  http://stackoverflow.com/a/7393477/2568492*/
    private BaseFragment getFragment(int index){
        return ((BaseFragment) getSupportFragmentManager().findFragmentByTag("android:switcher:" + R.id.pager + ":" + index));
    }
}