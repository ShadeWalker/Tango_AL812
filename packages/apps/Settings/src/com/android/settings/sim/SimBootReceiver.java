/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.sim;

import com.android.settings.R;
import com.android.settings.Settings.SimSettingsActivity;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Resources;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.text.TextUtils;

import com.mediatek.settings.cdma.CdmaUtils;
import com.mediatek.settings.ext.ISettingsMiscExt;
import com.mediatek.settings.ext.ISimManagementExt;
import com.android.settings.Utils;
/// M: Add for CT 6M.
import com.mediatek.settings.FeatureOption;
import com.mediatek.settings.UtilsExt;
import com.mediatek.settings.sim.Log;

import android.content.ContentResolver;
import android.provider.Settings.SettingNotFoundException;

import java.util.Iterator;
import java.util.List;
import android.os.SystemProperties;

public class SimBootReceiver extends BroadcastReceiver {
    private static final String TAG = "SimBootReceiver";
    private static final int SLOT_EMPTY = -1;
    private static final int NOTIFICATION_ID = 1;
    private static final String SHARED_PREFERENCES_NAME = "sim_state";
    private static final String SLOT_PREFIX = "sim_slot_";
    private static final int INVALID_SLOT = -2; // Used when upgrading from K to LMR1

    private SharedPreferences mSharedPreferences = null;
    private TelephonyManager mTelephonyManager;
    private Context mContext;
    private SubscriptionManager mSubscriptionManager;
    /// @}
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive()... action: " + intent.getAction());
        int detectedType = intent.getIntExtra(
                SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        if (detectedType == SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
            return;
        }
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mContext = context;
        mSubscriptionManager = SubscriptionManager.from(mContext);
        mSharedPreferences = mContext.getSharedPreferences(SHARED_PREFERENCES_NAME,
                Context.MODE_PRIVATE);
//      mSubscriptionManager.addOnSubscriptionsChangedListener(mSubscriptionListener);
        detectChangeAndNotify();

		/*HQ_xionghaifeng 20151009 modify for set default id start*/
        List<SubscriptionInfo> sil = mSubscriptionManager.getActiveSubscriptionInfoList();
        if (sil != null && sil.size() > 0) {
            Log.d(TAG, "onReceive()... sil.size(): " + sil.size());
			checkDefaultSmsSubId();
			checkDefaultVoiceSubId();
          checkDefaultDataSubId();
    	}
        else {
            Log.d(TAG, "onReceive()... sil.size(): " + 0);
        }
		/*HQ_xionghaifeng 20151009 modify for set default id end*/
    }

	/*HQ_xionghaifeng 20151009 modify for set default id start*/
    private int getSubIdBySlot(int slotId) {
        if (slotId < 0 || slotId > 1) {
            return -1;
        }
        int[] subids = mSubscriptionManager.getSubId(slotId);
        int subid = -1;
        if (subids != null && subids.length >= 1) {
            subid = subids[0];
        }
        return subid;
	}
	
	private void checkDefaultSmsSubId()
	{
		int smsSubId = mSubscriptionManager.getDefaultSmsSubId();
		if (!mSubscriptionManager.isValidSubscriptionId(smsSubId))
		{
			if (mTelephonyManager.hasIccCard(0))
			{
				mSubscriptionManager.setDefaultSmsSubId(getSubIdBySlot(0));
			}
			else
			{
				mSubscriptionManager.setDefaultSmsSubId(getSubIdBySlot(1));
			}
		}
		Log.d("xionghaifeng", "checkDefaultSmsSubId: PhoneId: " + mSubscriptionManager.getDefaultSmsPhoneId());
	}
	
	private void checkDefaultVoiceSubId()
	{
		int voiceSubId = mSubscriptionManager.getDefaultVoiceSubId();
		if (!mSubscriptionManager.isValidSubscriptionId(voiceSubId))
		{
			if (mTelephonyManager.hasIccCard(0))
			{
				mSubscriptionManager.setDefaultVoiceSubId(getSubIdBySlot(0));
			}
			else
			{
				mSubscriptionManager.setDefaultVoiceSubId(getSubIdBySlot(1));
			}
		}
		Log.d("xionghaifeng", "checkDefaultVoiceSubId: PhoneId: " + mSubscriptionManager.getDefaultVoicePhoneId());
	}
	/*HQ_xionghaifeng 20151009 modify for set default id end*/


    /*yanqing add for HQ01481926 start*/
    private void checkDefaultDataSubId()
    {
        boolean bNeedSet = false;
        int dataSubId = mSubscriptionManager.getDefaultDataSubId();
        if (!mSubscriptionManager.isValidSubscriptionId(dataSubId)) {
            bNeedSet = true;
        }
        else {
            boolean match = false;
            int[] subIds = mSubscriptionManager.getActiveSubscriptionIdList();
            if (subIds.length > 1) {
                for (int id : subIds) {
                    if (id == dataSubId) {
                        match = true;
                    }
                }
                if (!match) {
                    bNeedSet = true;
                }
            }
        }

        Log.d("xionghaifeng", "checkDefaultDataSubId: bNeedSet: " + bNeedSet);
        if (bNeedSet)
        {

            SubscriptionInfo si = mSubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(0);
            if (si != null) {
                mSubscriptionManager.setDefaultDataSubId(si.getSubscriptionId());
                Log.d("xionghaifeng", "checkDefaultDataSubId: slot=0  subId:" + si.getSubscriptionId());
            }
            else {
                si = mSubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(1);
                if (si != null) {
                    mSubscriptionManager.setDefaultDataSubId(si.getSubscriptionId());
                    Log.d("xionghaifeng", "checkDefaultDataSubId: slot=1  subId:" + si.getSubscriptionId());
                }
                else {
                    Log.d("xionghaifeng", "checkDefaultDataSubId: no card");
                }
            }
        }
        Log.d("xionghaifeng", "checkDefaultDataSubId: PhoneId: " + mSubscriptionManager.getDefaultDataPhoneId());
    }
    /*yanqing add for HQ01481926 end*/

    private void detectChangeAndNotify() {
        final int numSlots = mTelephonyManager.getSimCount();
        final boolean isInProvisioning = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) == 0;
        boolean notificationSent = false;
        int numSIMsDetected = 0;
        int lastSIMSlotDetected = -1;
        Log.d(TAG,"detectChangeAndNotify numSlots = " + numSlots + 
                " isInProvisioning = " + isInProvisioning);

        /* begin: change by donghongjing for sim settings Emui 
         * remove isInProvisioning, use isInProvisioning later */
        // Do not create notifications on single SIM devices or when provisiong.
        if (numSlots < 2) {
            return;
        }
        /* end: change by donghongjing for sim settings Emui */

        // We wait until SubscriptionManager returns a valid list of Subscription informations
        // by checking if the list is empty.
        // This is not completely correct, but works for most cases.
        // See Bug: 18377252
        List<SubscriptionInfo> sil = mSubscriptionManager.getActiveSubscriptionInfoList();
        if (sil == null || sil.size() < 1) {
            Log.d(TAG,"do nothing since no cards inserted");
            return;
        }

        /// M: for [C2K 2 SIM Warning]
        boolean newSimInserted = false;

        for (int i = 0; i < numSlots; i++) {
            final SubscriptionInfo sir = Utils.findRecordBySlotId(mContext, i);
            Log.d(TAG,"sir = " + sir);
            final String key = SLOT_PREFIX+i;
            final int lastSubId = getLastSubId(key);
            if (sir != null) {
                numSIMsDetected++;
                final int currentSubId = sir.getSubscriptionId();
                if (lastSubId == INVALID_SLOT) {
                    setLastSubId(key, currentSubId);
					notificationSent = true;
                } else if (lastSubId != currentSubId) {
                    createNotification(mContext);
                    setLastSubId(key, currentSubId);
                    notificationSent = true;
                }
                lastSIMSlotDetected = i;
                Log.d(TAG,"key = " + key + " lastSubId = " + lastSubId + 
                        " currentSubId = " + currentSubId + 
                        " lastSIMSlotDetected = " + lastSIMSlotDetected);
            } else if (lastSubId != SLOT_EMPTY) {
                createNotification(mContext);
                setLastSubId(key, SLOT_EMPTY);
                notificationSent = true;
            }
        }
        Log.d(TAG,"notificationSent = " + notificationSent + " numSIMsDetected = " + numSIMsDetected);

        if (notificationSent || isInProvisioning) {
            /* begin: change by donghongjing for sim settings Emui */
            ISimManagementExt smExt = UtilsExt.getSimManagmentExtPlugin(mContext);
            SubscriptionInfo sir = Utils.findRecordBySubId(mContext,
                    mSubscriptionManager.getDefaultDataSubId());
            sir = smExt.setDefaultSubId(mContext, sir, 2);

            /* remove  && !isDefaultDataSubInserted() */
	    /* HQ_hejingkui_2016-3-16 modified for HQ01789694 */
            //old codes
	    //if (numSIMsDetected == 2 && isEnableShowSimDialog()) {
	    if (numSIMsDetected == 2) {
                int selectedDataSlot = (sir == null) ? -1 : sir.getSimSlotIndex();
                if (selectedDataSlot == -1) {
                    int selectingDataSlotId = 0;
                    final SubscriptionInfo dataSir = Utils.findRecordBySlotId(mContext, selectingDataSlotId);
                    setDefaultDataSubId(mContext, dataSir.getSubscriptionId());
                }

                final SubscriptionInfo smsSir = getDefaultSmsSub();
                setDefaultSmsSubId(mContext, smsSir.getSubscriptionId());

                final SubscriptionInfo callSir = getDefaultCallSub();
                PhoneAccountHandle phoneAccountHandle =
                        subscriptionIdToPhoneAccountHandle(callSir.getSubscriptionId());
                setUserSelectedOutgoingPhoneAccount(phoneAccountHandle);

                if (!isInProvisioning) {
                    Intent simSetting = new Intent(Intent.ACTION_MAIN);
                    simSetting.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    simSetting.setAction("com.android.settings.sim.SIM_SUB_INFO_SETTINGS");
                    mContext.startActivity(simSetting);;
                } else {
                    Log.d(TAG,"start CheckAndShowSimSettingsService for isInProvisioning = " + isInProvisioning);
                    Intent intent = new Intent(mContext, CheckAndShowSimSettingsService.class);
                    mContext.startService(intent);
                }
            } else if (numSIMsDetected == 1 && isEnableShowSimDialog()) {
                int selectedSlot = (sir == null) ? -1 : sir.getSimSlotIndex();
                int selectingSlotId = hasIccCard(0) ? 0 : 1;
                final SubscriptionInfo dataSir = Utils.findRecordBySlotId(mContext, selectingSlotId);
                /// guoxiaolong for apr @{
                int subId = 0;
                if(null != dataSir) {
                	subId = dataSir.getSubscriptionId();
                }
                /// @}
                if (selectedSlot == -1) {
                    setDefaultDataSubId(mContext, subId);
                }

                setDefaultSmsSubId(mContext, subId);

                PhoneAccountHandle phoneAccountHandle =
                        subscriptionIdToPhoneAccountHandle(subId);
                setUserSelectedOutgoingPhoneAccount(phoneAccountHandle);
                
            }
            //yanqing add for HQ01458245 start
            setDefaultDataOpen();
            //yanqing add for HQ01458245 end

            if (isInProvisioning) {
                return;
            }
            /* end: change by donghongjing for sim settings Emui */
            //For C2K OM requirements @{
            CdmaUtils.startCdmaWaringDialog(mContext, numSIMsDetected);
            // @}
        }
    }

     /* begin: add by donghongjing for sim settings Emui */
    private boolean hasIccCard(int slotId) {
        boolean bReturn =  false;
        TelephonyManager tpMgr = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (tpMgr != null) {
            bReturn = tpMgr.hasIccCard(slotId);
            return bReturn;
        }
        return false;
    }

    private static void setDefaultDataSubId(final Context context, final int subId) {
        ISettingsMiscExt miscExt = UtilsExt.getMiscPlugin(context);
        ISimManagementExt simExt = UtilsExt.getSimManagmentExtPlugin(context);
        if (TelecomManager.from(context).isInCall()) {
            return;
        }
        final SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
        miscExt.setDefaultDataEnable(context, subId);
        simExt.setDataState(subId);
        subscriptionManager.setDefaultDataSubId(subId);
        simExt.setDataStateEnable(subId);
    }

    private static void setDefaultSmsSubId(final Context context, final int subId) {
        final SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
        subscriptionManager.setDefaultSmsSubId(subId);
    }

    private void setUserSelectedOutgoingPhoneAccount(PhoneAccountHandle phoneAccount) {
        final TelecomManager telecomManager = TelecomManager.from(mContext);
        telecomManager.setUserSelectedOutgoingPhoneAccount(phoneAccount);
    }

    private PhoneAccountHandle subscriptionIdToPhoneAccountHandle(final int subId) {
        final TelecomManager telecomManager = TelecomManager.from(mContext);
        final Iterator<PhoneAccountHandle> phoneAccounts =
                telecomManager.getCallCapablePhoneAccounts().listIterator();

        while (phoneAccounts.hasNext()) {
            final PhoneAccountHandle phoneAccountHandle = phoneAccounts.next();
            final PhoneAccount phoneAccount = telecomManager.getPhoneAccount(phoneAccountHandle);
            final String phoneAccountId = phoneAccountHandle.getId();

            if (phoneAccount.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                    && TextUtils.isDigitsOnly(phoneAccountId)
                    && Integer.parseInt(phoneAccountId) == subId){
                return phoneAccountHandle;
            }
        }

        return null;
    }

    private SubscriptionInfo getDefaultSmsSub() {
        int defaultSmsSub = SubscriptionManager.getDefaultSmsSubId();
        if (SubscriptionManager.isValidSubscriptionId(defaultSmsSub)) {
            final int numSlots = mTelephonyManager.getSimCount();
            for (int i = 0; i < numSlots; ++i) {
                final SubscriptionInfo sir = Utils.findRecordBySlotId(mContext, i);
                if (sir != null) {
                    if (sir.getSubscriptionId() == defaultSmsSub) {
                        Log.d(TAG, "getDefaultSmsSub defaultSmsSub: "
                                + defaultSmsSub + ", return slot i:" + i);
                        return sir;
                    }
                }
            }
        }
        Log.d(TAG, "getDefaultSmsSub defaultSmsSub: "
                + defaultSmsSub + ", not inserted, return slot:0");
        return Utils.findRecordBySlotId(mContext, 0);
    }

    private SubscriptionInfo getDefaultCallSub() {
        final TelecomManager telecomManager = TelecomManager.from(mContext);
        PhoneAccountHandle phoneAccountHandle = telecomManager.getUserSelectedOutgoingPhoneAccount();
        int defaultCallSub = -1;
        if (phoneAccountHandle != null) {
            final PhoneAccount phoneAccount = telecomManager.getPhoneAccount(phoneAccountHandle);
            final String phoneAccountId = phoneAccountHandle.getId();
            if (phoneAccount.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                    && TextUtils.isDigitsOnly(phoneAccountId)){
                defaultCallSub = Integer.parseInt(phoneAccountId);
            }
        }

        if (SubscriptionManager.isValidSubscriptionId(defaultCallSub)) {
            final int numSlots = mTelephonyManager.getSimCount();
            for (int i = 0; i < numSlots; ++i) {
                final SubscriptionInfo sir = Utils.findRecordBySlotId(mContext, i);
                if (sir != null) {
                    if (sir.getSubscriptionId() == defaultCallSub) {
                        Log.d(TAG, "getDefaultCallSub defaultCallSub: "
                                + defaultCallSub + ", return slot i:" + i);
                        return sir;
                    }
                }
            }
        }
        Log.d(TAG, "getDefaultCallSub defaultCallSub: "
                + defaultCallSub + ", not inserted, return slot:0");
        return Utils.findRecordBySlotId(mContext, 0);
    }
    /* end: add by donghongjing for sim settings Emui */

    private int getLastSubId(String strSlotId) {
        return mSharedPreferences.getInt(strSlotId, INVALID_SLOT);
    }

    private void setLastSubId(String strSlotId, int value) {
        Editor editor = mSharedPreferences.edit();
        editor.putInt(strSlotId, value);
        editor.commit();
    }

    private void createNotification(Context context){
        final Resources resources = context.getResources();

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context)
                .setSmallIcon(R.drawable.ic_sim_card_alert_white_48dp)
                .setColor(resources.getColor(R.color.sim_noitification))
                .setContentTitle(resources.getString(R.string.sim_notification_title))
                .setContentText(resources.getString(R.string.sim_notification_summary));
        /// M: only for OP09 UIM/SIM changes.
        changeNotificationString(context, builder);
        Intent resultIntent = new Intent(context, SimSettingsActivity.class);
        resultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                context,
                0,
                resultIntent,
                PendingIntent.FLAG_CANCEL_CURRENT
                );
        builder.setContentIntent(resultPendingIntent);
        NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    public static void cancelNotification(Context context) {
        NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
    }

    private final OnSubscriptionsChangedListener mSubscriptionListener =
            new OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            detectChangeAndNotify();
        }
    };

    private boolean isDefaultDataSubInserted() {
        boolean isInserted = false;
        int defaultDataSub = SubscriptionManager.getDefaultDataSubId();
        if (SubscriptionManager.isValidSubscriptionId(defaultDataSub)) {
            final int numSlots = mTelephonyManager.getSimCount();
            for (int i = 0; i < numSlots; ++i) {
                final SubscriptionInfo sir = Utils.findRecordBySlotId(mContext, i);
                if (sir != null) {
                    if (sir.getSubscriptionId() == defaultDataSub) {
                        isInserted = true;
                        break;
                    }
                }
            }
        }
        Log.d(TAG, "defaultDataSub: " + defaultDataSub + ", isInsert: " + isInserted);
        return isInserted;
    }

    /**
     * only for OP09 UIM/SIM changes.
     *
     * @param context the context.
     * @param builder the notification builder.
     */
    private void changeNotificationString(
                    Context context,
                    NotificationCompat.Builder builder) {
        Resources resources = context.getResources();
        String title = resources.getString(R.string.sim_notification_title);
        String text = resources.getString(R.string.sim_notification_summary);

        ISettingsMiscExt miscExt = UtilsExt.getMiscPlugin(context);
        title = miscExt.customizeSimDisplayString(
                            title,
                            SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        text = miscExt.customizeSimDisplayString(
                            text,
                            SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        builder.setContentTitle(title);
        builder.setContentText(text);
    }

    private boolean isEnableShowSimDialog() {
		///HQ_xionghaifeng add for HQ01469127 platform show SIM pick dialog start @{
		String productName = SystemProperties.get("ro.product.name", "");    
		if (productName.equalsIgnoreCase("TAG-TL00"))
		{
			return true;
		}
		///@}
		
        /// M: modify for CT 6M. Disable data pick dialog and preferred sim dialog. @ {
        boolean isEnable = false;
        if (!FeatureOption.MTK_CT6M_SUPPORT) {
            ISettingsMiscExt plugin = UtilsExt.getMiscPlugin(mContext);
            isEnable = plugin.isFeatureEnable();
        }
        /// @ }
        Log.d(TAG,"isEnableShowSimDialog isEnable = " + isEnable);
        return isEnable;
    }

    //yanqing add for HQ01458245 start
    private void setDefaultDataOpen() {
        ContentResolver resolver;
        resolver = mContext.getContentResolver();
        boolean dataIsOpen = false;
        try {
            dataIsOpen = Settings.Global.getInt(resolver,
                Settings.Global.MOBILE_DATA) != 0;
        } catch (SettingNotFoundException snfe) {
            // Not found the 'MOBILE_DATA+phoneSubId' setting, we should initialize it.
            dataIsOpen = false;
        }
        Log.d(TAG,"dataIsOpen = " + dataIsOpen);
        if(dataIsOpen){
            TelephonyManager tm = (TelephonyManager)
                    mContext.getSystemService(Context.TELEPHONY_SERVICE);
            final SubscriptionManager subscriptionManager = SubscriptionManager.from(mContext);
            int defaultSubId = subscriptionManager.getDefaultDataSubId();
            tm.setDataEnabled(defaultSubId, true);
            tm.setDataEnabled(true);

            //yanqing add for HQ01458260 start
            //systemui listen for Settings.Global.MOBILE_DATA value change to update 'Mobile data' switch.
            //So we make some changes to notify systemui
            //systemui do not listen for broadcast ACTION_ANY_DATA_CONNECTION_STATE_CHANGED
            Settings.Global.putInt(resolver, Settings.Global.MOBILE_DATA, 0);
            Settings.Global.putInt(resolver, Settings.Global.MOBILE_DATA, 1);
            //yanqing add for HQ01458260 end
        }
    }
    //yanqing add for HQ01458245 end
}