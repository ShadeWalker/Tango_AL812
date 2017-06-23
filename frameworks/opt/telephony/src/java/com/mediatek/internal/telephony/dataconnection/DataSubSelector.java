package com.mediatek.internal.telephony.dataconnection;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;

import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;

import android.telephony.RadioAccessFamily;
import android.telephony.Rlog;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.UiccController;

import com.mediatek.internal.telephony.cdma.CdmaFeatureOptionUtils;
import com.mediatek.internal.telephony.ITelephonyEx;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteModeController;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteUtils;
import com.mediatek.internal.telephony.RadioCapabilitySwitchUtil;
import com.mediatek.internal.telephony.uicc.SvlteUiccUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DataSubSelector {
    private static final boolean DBG = true;

    private int mPhoneNum;
    private boolean mIsNeedWaitImsi = false;

    private static final String PROPERTY_DEFAULT_DATA_ICCID = "persist.radio.data.iccid";
    private static final String PROPERTY_DEFAULT_SIMSWITCH_ICCID = "persist.radio.simswitch.iccid";
    private static final String NO_SIM_VALUE = "N/A";

    private static final boolean BSP_PACKAGE =
            SystemProperties.getBoolean("ro.mtk_bsp_package", false);

    private static String mOperatorSpec;
    private static final String OPERATOR_OM = "OM";
    private static final String OPERATOR_OP01 = "OP01";
    private static final String OPERATOR_OP02 = "OP02";
    private static final String OPERATOR_OP09 = "OP09";
    private static final String OPERATOR_OP18 = "OP18";

    private static final String PROPERTY_3G_SIM = "persist.radio.simswitch";
    private static final String PROPERTY_CAPABILITY_SWITCH_POLICY = "ro.mtk_sim_switch_policy";
    public static final String ACTION_MOBILE_DATA_ENABLE
            = "android.intent.action.ACTION_MOBILE_DATA_ENABLE";
    public static final String EXTRA_MOBILE_DATA_ENABLE_REASON = "reason";

    public static final String REASON_MOBILE_DATA_ENABLE_USER = "user";
    public static final String REASON_MOBILE_DATA_ENABLE_SYSTEM = "system";

    private static final String PROPERTY_MOBILE_DATA_ENABLE = "persist.radio.mobile.data";
    private String[] PROPERTY_ICCID = {
        "ril.iccid.sim1",
        "ril.iccid.sim2",
        "ril.iccid.sim3",
        "ril.iccid.sim4",
    };
    private Intent mIntent = null;
    private final static String OLD_ICCID = "old_iccid";
    private final static String NEED_TO_WAIT_UNLOCKED = "persist.radio.unlock";
    private final static String NEED_TO_WAIT_UNLOCKED_ROAMING = "persist.radio.unlock.roaming";
    private final static String SIM_STATUS = "persist.radio.sim.status";
    private final static String NEW_SIM_SLOT = "persist.radio.new.sim.slot";
    private final static String FIRST_TIME_ROAMING = "first_time_roaming";
    private final static String NEED_TO_EXECUTE_ROAMING = "need_to_execute_roaming";
    private final static int HOME_POLICY = 0;
    private final static int ROAMING_POLICY = 1;

    private Context mContext = null;
    private boolean mAirplaneModeOn = false;
    private boolean mIsNeedWaitAirplaneModeOff = false;
    private boolean mIsNeedWaitAirplaneModeOffRoaming = false;
    private boolean mIsNeedWaitImsiRoaming = false;
    private boolean mIsUserConfirmDefaultData = false;
    private int mPrevDefaultDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private int mLastValidDefaultDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private boolean detectedChange = false;
    private boolean switchOffAirplandMode = false;
    // Add for multi policies for single operator
    private final static int POLICY_NO_AUTO = 0;
    private final static int POLICY_DEFAULT = 1;
    private final static int POLICY_POLICY1 = 2;
    private final static int capability_switch_policy =
            SystemProperties.getInt(PROPERTY_CAPABILITY_SWITCH_POLICY, POLICY_DEFAULT);

    protected BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            log("onReceive: action=" + action);
            if (action.equals(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED)) {
                mIsNeedWaitImsi = false;
                onSubInfoReady(intent);
            } else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                String simStatus = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                int slotId = intent.getIntExtra(PhoneConstants.SLOT_KEY, PhoneConstants.SIM_ID_1);
                log("slotId: " + slotId + " simStatus: " + simStatus + " mIsNeedWaitImsi: "
                        + mIsNeedWaitImsi + " mIsNeedWaitUnlock: " +
                        isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED));
                if (simStatus.equals(IccCardConstants.INTENT_VALUE_ICC_IMSI)) {
                    if (mIsNeedWaitImsi == true || mIsNeedWaitImsiRoaming == true) {
                        if (mIsNeedWaitImsi == true) {
                            mIsNeedWaitImsi = false;
                            if (OPERATOR_OP01.equals(mOperatorSpec)) {
                                log("get imsi and need to check op01 again");
                                mIntent = intent;
                                if (checkOp01CapSwitch6m() == false) {
                                    mIsNeedWaitImsi = true;
                                }
                            } else if (OPERATOR_OP02.equals(mOperatorSpec)) {
                                log("get imsi and need to check op02 again");
                                if (checkOp02CapSwitch(HOME_POLICY) == false) {
                                    mIsNeedWaitImsi = true;
                                }
                            } else if (OPERATOR_OP18.equals(mOperatorSpec)) {
                                log("get imsi and need to check op18 again");
                                if (checkOp18CapSwitch() == false) {
                                    mIsNeedWaitImsi = true;
                                }
                            }
                        }
                        if (mIsNeedWaitImsiRoaming == true) {
                            mIsNeedWaitImsiRoaming = false;
                            log("get imsi and need to check op02Roaming again");
                            if (checkOp02CapSwitch(ROAMING_POLICY) == false) {
                                mIsNeedWaitImsiRoaming = true;
                            }
                        }
                    } else if (isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED) == true || mIsNeedWaitImsiRoaming == true) {
                        log("get imsi because unlock");

                        ITelephonyEx iTelEx = ITelephonyEx.Stub.asInterface(
                            ServiceManager.getService(Context.TELEPHONY_SERVICE_EX));
                        try {
                            if (iTelEx.isCapabilitySwitching()) {
                                // wait complete intent
                            } else {
                                    if (isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED)) {
                                        setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
                                    if (OPERATOR_OP01.equals(mOperatorSpec)) {
                                        subSelectorForOp01(mIntent);
                                    } else if (OPERATOR_OP02.equals(mOperatorSpec)) {
                                            if (SystemProperties.getBoolean(
                                                    "ro.mtk_disable_cap_switch", false) == true) {
                                                subSelectorForOp02(mIntent);
                                            } else {
                                                subSelectorForOp02();
                                            }
                                    } else if (OPERATOR_OM.equals(mOperatorSpec)) {
                                        subSelectorForOm(mIntent);
                                    } else if (OPERATOR_OP09.equals(mOperatorSpec)) {
                                        subSelectorForOp09(mIntent);
                                    } else if (OPERATOR_OP18.equals(mOperatorSpec)) {
                                        subSelectorForOp18(mIntent);
                                    }
                                }
                                    if (isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED_ROAMING)) {
                                        setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED_ROAMING, "false");
                                    checkOp02CapSwitch(ROAMING_POLICY);
                                }
                            }
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } else if (action.equals(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_DONE)
                    || action.equals(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_FAILED)) {
                log("detectedChange:" + detectedChange);
                if (action.equals(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_DONE)) {
                    if (OPERATOR_OP02.equals(mOperatorSpec)) {
                        if (SystemProperties.getBoolean("ro.mtk_disable_cap_switch",false) == false) {
                            detectedChange = false;
                            log("set detectedChange = " + detectedChange);
                        }
                    }
                }
                if (isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED) == true || mIsNeedWaitImsiRoaming == true) {
                    if (isNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED) == true) {
                        setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
                        if (OPERATOR_OP01.equals(mOperatorSpec)) {
                            subSelectorForOp01(mIntent);
                        } else if (OPERATOR_OP02.equals(mOperatorSpec)) {
                            if (SystemProperties.getBoolean("ro.mtk_disable_cap_switch",
                                    false) == true) {
                                subSelectorForOp02(mIntent);
                            } else {
                                subSelectorForOp02();
                            }
                        } else if (OPERATOR_OM.equals(mOperatorSpec)) {
                            subSelectorForOm(mIntent);
                        } else if (OPERATOR_OP09.equals(mOperatorSpec)) {
                            subSelectorForOp09(mIntent);
                        } else if (OPERATOR_OP18.equals(mOperatorSpec)) {
                            subSelectorForOp18(mIntent);
                        }
                    }
                    if (mIsNeedWaitImsiRoaming == true) {
                        log("mIsNeedWaitImsiRoaming is true");
                        mIsNeedWaitImsiRoaming = false;
                        checkOp02CapSwitch(ROAMING_POLICY);
                    }
                }
            } else if (action.equals(TelephonyIntents.ACTION_SET_RADIO_TECHNOLOGY_DONE)) {
                log("ACTION_SET_RADIO_TECHNOLOGY_DONE");
                if (mOperatorSpec.equals(OPERATOR_OM)) {
                    subSelectorForSvlte(intent);
                }
            } else if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                boolean mAirplaneModeOnOld = mAirplaneModeOn;
                mAirplaneModeOn = intent.getBooleanExtra("state", false) ? true : false;
                if(mAirplaneModeOnOld == true && mAirplaneModeOn == false) switchOffAirplandMode = true;
                log("ACTION_AIRPLANE_MODE_CHANGED, enabled = " + mAirplaneModeOn);
                if (!mAirplaneModeOn) {
                    if (mIsNeedWaitAirplaneModeOff) {
                        mIsNeedWaitAirplaneModeOff = false;
                        if (OPERATOR_OP01.equals(mOperatorSpec)) {
                            subSelectorForOp01(mIntent);
                        } else if (OPERATOR_OP02.equals(mOperatorSpec)) {
                            if (SystemProperties.getBoolean("ro.mtk_disable_cap_switch",
                                    false) == true) {
                                subSelectorForOp02(mIntent);
                            } else {
                                subSelectorForOp02();
                            }
                        }
                    }
                    if (mIsNeedWaitAirplaneModeOffRoaming == true) {
                        log("mIsNeedWaitAirplaneModeOffRoaming is true");
                        mIsNeedWaitAirplaneModeOffRoaming = false;
                        checkOp02CapSwitch(ROAMING_POLICY);
                    }
                }
            } else if (action.equals(TelephonyIntents.ACTION_LOCATED_PLMN_CHANGED)) {
                log("ACTION_LOCATED_PLMN_CHANGED");
                if (SystemProperties.getBoolean("ro.mtk_disable_cap_switch", false) == false) {
                    if (OPERATOR_OP02.equals(mOperatorSpec)) {
                        String plmn = intent.getStringExtra(TelephonyIntents.EXTRA_PLMN);
                        if ((plmn != null) && !("".equals(plmn))) {
                            log("plmn = " + plmn);
                            SharedPreferences preference = context.getSharedPreferences(
                                    FIRST_TIME_ROAMING, Context.MODE_PRIVATE);
                            SharedPreferences.Editor editor = preference.edit();
                            boolean firstTimeRoaming = preference.getBoolean(
                                    NEED_TO_EXECUTE_ROAMING, true);
                            if (!(plmn.startsWith(RadioCapabilitySwitchUtil.CN_MCC))) {
                                if (firstTimeRoaming == true) {
                                    if (mIsNeedWaitImsi == false) {
                                        checkOp02CapSwitch(ROAMING_POLICY);
                                    } else {
                                        // If onSubInfoReady doesn't get IMSI, we assume
                                        // checkOp02CapSwitch get the same result
                                        mIsNeedWaitImsiRoaming = true;
                                    }
                                }
                            } else {
                                // Plmn is in home location.
                                if (firstTimeRoaming == false) {
                                    // Reset first_time_roaming flag
                                    log("reset roaming flag");
                                    editor.clear();
                                    editor.commit();
                                }
                            }
                        }
                    }
                }
            } else if (TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED.equals(action)) {
                int nDefaultDataSubId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                            SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                log("mIsUserConfirmDefaultData/nDefaultDataSubId:" + mIsUserConfirmDefaultData
                        + "/" + nDefaultDataSubId);
                if (mIsUserConfirmDefaultData
                        && SubscriptionManager.isValidSubscriptionId(nDefaultDataSubId)) {
                    handleDataEnableForOp02(2);
                    mIsUserConfirmDefaultData = false;
                }

                setLastValidDefaultDataSub(nDefaultDataSubId);
            }
        }
    };

    public DataSubSelector(Context context, int phoneNum) {
        log("DataSubSelector is created");
        mPhoneNum = phoneNum;
        mOperatorSpec = SystemProperties.get("ro.operator.optr", OPERATOR_OM);
        log("Operator Spec:" + mOperatorSpec);
        mContext = context;

        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_DONE);
        filter.addAction(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_FAILED);
        filter.addAction(TelephonyIntents.ACTION_LOCATED_PLMN_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SET_RADIO_TECHNOLOGY_DONE);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        context.registerReceiver(mBroadcastReceiver, filter);

        mAirplaneModeOn = (Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1) ? true : false;

        // Op02 hotplug
        int defaultDataSubId = SubscriptionManager.getDefaultDataSubId();
        log("defaultDataSub:" + defaultDataSubId);
        setLastValidDefaultDataSub(defaultDataSubId);
    }

    /**
     * Receive ACTION_SUBINFO_RECORD_UPDATED.
     * @param intent the intent conveys info.
     */
    public void onSubInfoReady(Intent intent) {
        mIsNeedWaitImsi = false;
        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                        SubscriptionManager.EXTRA_VALUE_NOCHANGE);
        log("[onSubInfoReady]detectedType:" + detectedType + " detectedChange:" + detectedChange);
        if (BSP_PACKAGE) {
            log("Don't support BSP Package.");
            return;
        }
        if (mOperatorSpec.equals(OPERATOR_OP01)) {
            subSelectorForOp01(intent);
        } else if (mOperatorSpec.equals(OPERATOR_OP02)) {
            if (SystemProperties.getBoolean("ro.mtk_disable_cap_switch", false) == true) {
                subSelectorForOp02(intent);
            } else {
                if (detectedType != SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
                    detectedChange = true;
                    subSelectorForOp02();
                } else if(switchOffAirplandMode == true && detectedChange == true){
                    log("switchOffAirplandMode");
                    switchOffAirplandMode = false;
                    // at this time do sim switch, may based on old imsi.
                    // because subinfo changed, can auto sim switch after get imsi.
                    mIsNeedWaitImsi = true;
                }
            }
        } else if (OPERATOR_OP18.equals(mOperatorSpec)) {
            // for skip auto switch after user set manually.
            if (detectedType != SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
                subSelectorForOp18(intent);
            } else {
                log("skip auto switch when detectedType is NOCHANGE for OP18 when user may set");
            }
        } else if (mOperatorSpec.equals(OPERATOR_OP09)) {
            subSelectorForOp09(intent);
        } else if (CdmaFeatureOptionUtils.isCT6MSupport()) {
            subSelectorForOp09C(intent);
        } else if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            // wait for ACTION_SET_RADIO_TECHNOLOGY_DONE
            //Turn off data if new SIM detected.
            turnOffNewSimData(intent);
        } else {
            subSelectorForOm(intent);
        }

        // TODO: m remove this line
        updateDataEnableProperty();

    }

    private void subSelectorForSolution15(Intent intent) {
        log("DataSubSelector for C2K om solution 1.5: capability maybe diff with default data");

        // only handle 3/4G switching
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        String[] currIccId = new String[mPhoneNum];

        //Since SvLTE Project may call subSelectorForOm before sub ready
        //we should do this on sub ready
        if (!CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            turnOffNewSimData(intent);
        }

        //Get previous sim switch data
        String capabilityIccid = SystemProperties.get(PROPERTY_DEFAULT_SIMSWITCH_ICCID);
        log("capability Iccid = " + capabilityIccid);
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);
        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i]) || "N/A".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }

            if (defaultIccid.equals(currIccId[i]) &&
                    SvlteUiccUtils.getInstance().isRuimCsim(i)) {
                phoneId = i;
                break;
            }

            if (capabilityIccid.equals(currIccId[i])) {
                phoneId = i;
                break;
            }
        }

        log("capability  phoneid = " + phoneId);
        if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
            // always set capability to this phone
            setCapability(phoneId);
        }
    }

    private boolean checkCdmaCardInsert() {
        int phoneCount = TelephonyManager.getDefault().getPhoneCount();
        for (int i = 0; i< phoneCount; i++){
            if (SvlteUiccUtils.getInstance().isRuimCsim(i)){
                log("CDMA sim is inserted in " + i);
                return true;
            }
        }
        return false;
    }

    private void subSelectorForOm(Intent intent) {
        log("DataSubSelector for OM: only for capability switch; for default data, use google");

        // only handle 3/4G switching
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        String[] currIccId = new String[mPhoneNum];

        //Since SvLTE Project may call subSelectorForOm before sub ready
        //we should do this on sub ready
        if (!CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            turnOffNewSimData(intent);
        }

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);
        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            if (defaultIccid.equals(currIccId[i])) {
                phoneId = i;
                break;
            }

            if (NO_SIM_VALUE.equals(currIccId[i])) {
                log("clear mcc.mnc:" + i);
                String propStr;
                if (i == 0) {
                    propStr = "gsm.sim.ril.mcc.mnc";
                } else {
                    propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
                }
                SystemProperties.set(propStr, "");
            }
        }
        // check pin lock
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("DataSubSelector for OM: do not switch because of sim locking");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            mIntent = intent;
            setSimStatus(intent);
            setNewSimSlot(intent);
            return;
        }

        log("Default data phoneid = " + phoneId);
        if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
            // always set capability to this phone
            setCapability(phoneId);
        }
    }

    /*private void subSelectorForOm(Intent intent) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for OM");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID + (i + 1));
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);

        if (insertedSimCount == 0) {
            // No SIM inserted
            // 1. Default Data: unset
            // 2. Data Enable: OFF
            // 3. 34G: No change
            log("C0: No SIM inserted, set data unset");
            setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }

            if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                // Case 1: Single SIM + New SIM:
                // 1. Default Data: this sub
                // 2. Data Enable: OFF
                // 3. 34G: this sub
                log("C1: Single SIM + New SIM: Set Default data to phone:" + phoneId);
                if (setCapability(phoneId)) {
                    setDefaultData(phoneId);
                }
                setDataEnable(false);
            } else {
                if (defaultIccid == null || "".equals(defaultIccid)) {
                    //It happened from two SIMs without default SIM -> remove one SIM.
                    // Case 3: Single SIM + Non Data SIM:
                    // 1. Default Data: this sub
                    // 2. Data Enable: OFF
                    // 3. 34G: this sub
                    log("C3: Single SIM + Non Data SIM: Set Default data to phone:" + phoneId);
                    if (setCapability(phoneId)) {
                        setDefaultData(phoneId);
                    }
                    setDataEnable(false);
                } else {
                    if (defaultIccid.equals(currIccId[phoneId])) {
                        // Case 2: Single SIM + Defult Data SIM:
                        // 1. Default Data: this sub
                        // 2. Data Enable: No Change
                        // 3. 34G: this sub
                        log("C2: Single SIM + Data SIM: Set Default data to phone:" + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                    } else {
                        // Case 3: Single SIM + Non Data SIM:
                        // 1. Default Data: this sub
                        // 2. Data Enable: OFF
                        // 3. 34G: this sub
                        log("C3: Single SIM + Non Data SIM: Set Default data to phone:" + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                        setDataEnable(false);
                    }
                }
            }
        } else if (insertedSimCount >= 2) {
            if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                int newSimStatus = intent.getIntExtra(
                        SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, 0);

                boolean isAllNewSim = true;
                for (int i = 0; i < mPhoneNum; i++) {
                    if ((newSimStatus & (1 << i)) == 0) {
                        isAllNewSim = false;
                    }
                }

                if (isAllNewSim) {
                    // Case 4: Multi SIM + All New SIM:
                    // 1. Default Data: Unset
                    // 2. Data Enable: OFF
                    // 3. 34G: Sub1
                    log("C4: Multi SIM + All New SIM: Set 34G to sub1");
                    if (setCapability(PhoneConstants.SIM_ID_1)) {
                        setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
                    }
                    setDataEnable(false);
                } else {
                    if (defaultIccid == null || "".equals(defaultIccid)) {
                        //Not found previous default SIM, don't change.
                        // Case 6: Multi SIM + New SIM + Non Default SIM:
                        // 1. Default Data: Unset
                        // 2. Data Enable: OFF
                        // 3. 34G: No Change
                        log("C6: Multi SIM + New SIM + Non Default SIM: No Change");
                        setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
                        setDataEnable(false);
                    } else {
                        for (int i = 0; i < mPhoneNum; i++) {
                            if (defaultIccid.equals(currIccId[i])) {
                                phoneId = i;
                                break;
                            }
                        }

                        if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
                            // Case 5: Multi SIM + New SIM + Default SIM:
                            // 1. Default Data: Default SIM
                            // 2. Data Enable: No Change
                            // 3. 34G: Default SIM
                            log("C5: Multi SIM + New SIM + Default SIM: Set Default data to "
                                + "phone:" + phoneId);
                            if (setCapability(phoneId)) {
                                setDefaultData(phoneId);
                            }
                        } else {
                            // Case 6: Multi SIM + New SIM + Non Default SIM:
                            // 1. Default Data: Unset
                            // 2. Data Enable: OFF
                            // 3. 34G: No Change
                            log("C6: Multi SIM + New SIM + Non Default SIM: No Change");
                            setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
                            setDataEnable(false);
                        }
                    }
                }
            } else {
                if (defaultIccid == null || "".equals(defaultIccid)) {
                    //Case 8: Multi SIM + All Old SIM + No Default SIM:
                    // 1. Default Data: Unset
                    // 2. Data Enable: No Change
                    // 3. 34G: No change
                    //Do nothing
                    log("C8: Do nothing");
                } else {
                    for (int i = 0; i < mPhoneNum; i++) {
                        if (defaultIccid.equals(currIccId[i])) {
                            phoneId = i;
                            break;
                        }
                    }
                    if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
                        // Case 7: Multi SIM + All Old SIM + Default SIM:
                        // 1. Default Data: Default SIM
                        // 2. Data Enable: No Change
                        // 3. 34G: Default SIM
                        log("C7: Multi SIM + New SIM + Default SIM: Set Default data to phone:"
                                + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                    } else {
                        //Case 8: Multi SIM + All Old SIM + No Default SIM:
                        // 1. Default Data: Unset
                        // 2. Data Enable: No Change
                        // 3. 34G: No change
                        //Do nothing
                        log("C8: Do nothing");
                    }
                }
            }
        }
    }*/

    // subSelectorForOp02 without disabled switching capability
    private void subSelectorForOp02(Intent intent) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = (intent == null) ? getSimStatus() :
                intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for OP02");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            } else {
                log("clear mcc.mnc:" + i);
                String propStr;
                if (i == 0) {
                    propStr = "gsm.sim.ril.mcc.mnc";
                } else {
                    propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
                }
                SystemProperties.set(propStr, "");
            }
        }
        // check pin lock
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("DataSubSelector for OP02: do not switch because of sim locking");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            setSimStatus(intent);
            setNewSimSlot(intent);
            return;
        } else {
            log("DataSubSelector for OP02: no pin lock");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
        }
        if (mAirplaneModeOn) {
            log("DataSubSelector for OP02: do not switch because of mAirplaneModeOn");
            mIsNeedWaitAirplaneModeOff = true;
            setSimStatus(intent);
            setNewSimSlot(intent);
            return;
        }

        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        if (detectedType == SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
            // OP02 Case 0: No SIM change, do nothing
            log("OP02 C0: Inserted status no change, do nothing");
        } else if (insertedSimCount == 0) {
            // OP02 Case 1: No SIM inserted
            // 1. Default Data: unset
            // 2. Data Enable: No Change
            // 3. 34G: Always SIM1
            log("OP02 C1: No SIM inserted, set data unset");
            setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }
            //OP02 Case 2: Single SIM
            // 1. Default Data: This sub
            // 2. Data Enable: No Change
            // 3. 34G: Always SIM1
            log("OP02 C2: Single SIM: Set Default data to phone:" + phoneId);
            boolean enable = getDataEnabledFromSetting(mLastValidDefaultDataSubId);
            if (mLastValidDefaultDataSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                // first boot / factory reset / no valid subId ever
                // turn on SIM 1
                enable = true;
            }
            setDefaultData(phoneId);
            // Set data enabled for phoneId if the data of the other phone is enabled orginally
            setDataEnabled(phoneId, enable);
        } else if (insertedSimCount >= 2) {
            //OP02 Case 3: Multi SIM
            // 1. Default Data: Always SIM1
            // 2. Data Enable: No Change
            // 3. 34G: Always SIM1
            log("OP02 C3: Multi SIM: Set Default data to phone1");

            boolean enable = getDataEnabledFromSetting(mLastValidDefaultDataSubId);
            if (mLastValidDefaultDataSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                // first boot / factory reset / no valid subId ever
                // turn on SIM 1
                enable = true;
            }
            setDefaultData(PhoneConstants.SIM_ID_1);

            // Set data disabled for sim2
            // But before that, we should set data enabled for sim1
            // if the original sim2's data is enabled
            setDataEnabled(PhoneConstants.SIM_ID_1, enable);
            setDataEnabled(PhoneConstants.SIM_ID_2, false);
        }
    }

    private void subSelectorForOp02() {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for op02 (subSelectorForOp02)");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            log("currIccid[" + i + "] : " + currIccId[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                insertedSimCount++;
                insertedStatus = insertedStatus | (1 << i);
            } else {
                log("clear mcc.mnc:" + i);
                String propStr;
                if (i == 0) {
                    propStr = "gsm.sim.ril.mcc.mnc";
                } else {
                    propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
                }
                SystemProperties.set(propStr, "");
                log("sim index: " + i + " not inserted");
            }
        }
        // check pin lock
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("DataSubSelector for OP02: do not switch because of sim locking");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            return;
        } else {
            log("DataSubSelector for OP02: no pin lock");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
        }
        if (mAirplaneModeOn) {
            log("DataSubSelector for OP02: do not switch because of mAirplaneModeOn");
            mIsNeedWaitAirplaneModeOff = true;
            return;
        }

        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        if (insertedSimCount == 0) {
            // No SIM inserted
            // 1. Default Data: Unset
            // 2. Data Enable: off
            // 3. 34G: Slot1
            log("C0: No SIM inserted: set default data unset");
            setDefaultData(phoneId);
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }
            // Case1: Single SIM
            // 1. Default Data: This SIM
            // 2. Data Enable: No change
            // 3. 34G: This SIM
            log("C1: Single SIM inserted: set default data to phone: " + phoneId);
            setCapability(phoneId);
            setDefaultData(phoneId);
            handleDataEnableForOp02(insertedSimCount);
        } else if (insertedSimCount >= 2) {
            if (checkOp02CapSwitch(HOME_POLICY) == false) {
                mIsNeedWaitImsi = true;
            }
        }

        updateDataEnableProperty();
    }

    private void subSelectorForOp01(Intent intent) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = (intent == null) ? getSimStatus() :
                intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for op01");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            log("currIccId[" + i + "] : " + currIccId[i]);
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            } else {
                log("clear mcc.mnc:" + i);
                String propStr;
                if (i == 0) {
                    propStr = "gsm.sim.ril.mcc.mnc";
                } else {
                    propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
                }
                SystemProperties.set(propStr, "");
            }
        }
        // check pin lock
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("DataSubSelector for OP01: do not switch because of sim locking");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            setSimStatus(intent);
            setNewSimSlot(intent);
            mIntent = intent;
            return;
        }
        //check airplane mode
        int airplaneMode = Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);
        if (airplaneMode > 0) {
            log("airplanemode is on, keep intent and do switch when it off.");
            mIntent = intent;
            mIsNeedWaitAirplaneModeOff = true;
            setSimStatus(intent);
            setNewSimSlot(intent);
            return;
        }

        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);

        if (insertedSimCount == 0) {
            // No SIM inserted
            log("OP01 C0: No SIM inserted, do nothing");
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }

            log("OP01 C1: Single SIM: Set Default data to phone:" + phoneId);
            if (setCapability(phoneId)) {
                setDefaultData(phoneId);
            }

            turnOffNewSimData(intent);
        } else if (insertedSimCount >= 2) {
            //Move functions below out from checkOp01CapSwitch6m for
            //CMCC new spec has no data limit on Non-CMCC sim for 5M project
            turnOffNewSimData(intent);
            updateDataEnableProperty();
            
            if (checkOp01CapSwitch6m() == false) {
                // need wait imsi ready
                mIsNeedWaitImsi = true;
                mIntent = intent;
                setSimStatus(intent);
                setNewSimSlot(intent);
                return;
            }
        }

        // clean system property
        resetSimStatus();
        resetNewSimSlot();
    }

    private boolean checkOp01CapSwitch() {
        // check if need to switch capability
        // op01 USIM > op01 SIM > oversea USIM > oversea SIM > others
        int[] simOpInfo = new int[mPhoneNum];
        int[] simType = new int[mPhoneNum];
        int targetSim = -1;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        boolean[] op01Usim = new boolean[mPhoneNum];
        boolean[] op01Sim = new boolean[mPhoneNum];
        boolean[] overseaUsim = new boolean[mPhoneNum];
        boolean[] overseaSim = new boolean[mPhoneNum];
        String capabilitySimIccid = SystemProperties.get(RadioCapabilitySwitchUtil.MAIN_SIM_PROP);
        String[] currIccId = new String[mPhoneNum];

        log("checkOp01CapSwitch start");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return false;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        log("checkOp01CapSwitch : Inserted SIM count: " + insertedSimCount
                + ", insertedStatus: " + insertedStatus);
        if (RadioCapabilitySwitchUtil.getSimInfo(simOpInfo, simType, insertedStatus) == false) {
            return false;
        }
        // check pin lock
        String propStr;
        for (int i = 0; i < mPhoneNum; i++) {
            if (i == 0) {
                propStr = "gsm.sim.ril.mcc.mnc";
            } else {
                propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
            }
            if (SystemProperties.get(propStr, "").equals("sim_lock")) {
                log("checkOp01CapSwitch : phone " + i + " is sim lock");
                setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            }
        }
        int capabilitySimId = Integer.valueOf(
                SystemProperties.get(PhoneConstants.PROPERTY_CAPABILITY_SWITCH, "1")) - 1;
        log("op01: capabilitySimIccid:" + capabilitySimIccid
                + "capabilitySimId:" + capabilitySimId);
        for (int i = 0; i < mPhoneNum; i++) {
            // update SIM status
            if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OP01) {
                if (simType[i] != RadioCapabilitySwitchUtil.SIM_TYPE_SIM) {
                    op01Usim[i] = true;
                } else {
                    op01Sim[i] = true;
                }
            } else if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OVERSEA) {
                if (simType[i] != RadioCapabilitySwitchUtil.SIM_TYPE_SIM) {
                    overseaUsim[i] = true;
                } else {
                    overseaSim[i] = true;
                }
            }
        }
        // dump sim op info
        log("op01Usim: " + Arrays.toString(op01Usim));
        log("op01Sim: " + Arrays.toString(op01Sim));
        log("overseaUsim: " + Arrays.toString(overseaUsim));
        log("overseaSim: " + Arrays.toString(overseaSim));

        for (int i = 0; i < mPhoneNum; i++) {
            if (capabilitySimIccid.equals(currIccId[i])) {
                targetSim = RadioCapabilitySwitchUtil.getHigherPrioritySimForOp01(i, op01Usim
                        , op01Sim, overseaUsim, overseaSim);
                log("op01: i = " + i + ", currIccId : " + currIccId[i] + ", targetSim : " + targetSim);
                // default capability SIM is inserted
                if (op01Usim[i] == true) {
                    log("op01-C1: cur is old op01 USIM, no change");
                    if (capabilitySimId != i) {
                        log("op01-C1a: old op01 USIM change slot, change!");
                        setCapability(i);
                    }
                    return true;
                } else if (op01Sim[i] == true) {
                    if (targetSim != -1) {
                        log("op01-C2: cur is old op01 SIM but find op01 USIM, change!");
                        setCapability(targetSim);
                    } else if (capabilitySimId != i) {
                        log("op01-C2a: old op01 SIM change slot, change!");
                        setCapability(i);
                    }
                    return true;
                } else if (overseaUsim[i] == true) {
                    if (targetSim != -1) {
                        log("op01-C3: cur is old OS USIM but find op01 SIMs, change!");
                        setCapability(targetSim);
                    } else if (capabilitySimId != i) {
                        log("op01-C3a: old OS USIM change slot, change!");
                        setCapability(i);
                    }
                    return true;
                } else if (overseaSim[i] == true) {
                    if (targetSim != -1) {
                        log("op01-C4: cur is old OS SIM but find op01 SIMs/OS USIM, change!");
                        setCapability(targetSim);
                    } else if (capabilitySimId != i) {
                        log("op01-C4a: old OS SIM change slot, change!");
                        setCapability(i);
                    }
                    return true;
                } else if (targetSim != -1) {
                    log("op01-C5: cur is old non-op01 SIM/USIM but find higher SIM, change!");
                    setCapability(targetSim);
                    return true;
                }
                log("op01-C6: no higher priority SIM, no cahnge");
                return true;
            }
        }
        // cannot find default capability SIM, check if higher priority SIM exists
        targetSim = RadioCapabilitySwitchUtil.getHigherPrioritySimForOp01(capabilitySimId,
                op01Usim, op01Sim, overseaUsim, overseaSim);
        log("op01: target SIM :" + targetSim);
        if (op01Usim[capabilitySimId] == true) {
            log("op01-C7: cur is new op01 USIM, no change");
            return true;
        } else if (op01Sim[capabilitySimId] == true) {
            if (targetSim != -1) {
                log("op01-C8: cur is new op01 SIM but find op01 USIM, change!");
                setCapability(targetSim);
            }
            return true;
        } else if (overseaUsim[capabilitySimId] == true) {
            if (targetSim != -1) {
                log("op01-C9: cur is new OS USIM but find op01 SIMs, change!");
                setCapability(targetSim);
            }
            return true;
        } else if (overseaSim[capabilitySimId] == true) {
            if (targetSim != -1) {
                log("op01-C10: cur is new OS SIM but find op01 SIMs/OS USIM, change!");
                setCapability(targetSim);
            }
            return true;
        } else if (targetSim != -1) {
            log("op01-C11: cur is non-op01 but find higher priority SIM, change!");
            setCapability(targetSim);
        } else {
            log("op01-C12: no higher priority SIM, no cahnge");
        }
        return true;
    }

    private boolean checkOp01CapSwitch6m() {
        // check if need to switch capability
        // op01 USIM > op01 SIM > others
        int[] simOpInfo = new int[mPhoneNum];
        int[] simType = new int[mPhoneNum];
        int targetPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        String capabilitySimIccid = SystemProperties.get(RadioCapabilitySwitchUtil.MAIN_SIM_PROP);
        String[] currIccId = new String[mPhoneNum];
        int[] priority = new int[mPhoneNum];
        int capabilityPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        log("checkOp01CapSwitch6m start");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return false;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
            // find phoneid of capability sim.
            if (capabilitySimIccid.equals(currIccId[i])) {
                capabilityPhoneId = i;
            }
        }
        // check pin lock
        String propStr;
        for (int i = 0; i < mPhoneNum; i++) {
            if (i == 0) {
                propStr = "gsm.sim.ril.mcc.mnc";
            } else {
                propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
            }
            if (SystemProperties.get(propStr, "").equals("sim_lock")) {
                log("checkOp01CapSwitch : phone " + i + " is sim lock");
                setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            }
        }
        if (RadioCapabilitySwitchUtil.getSimInfo(simOpInfo, simType, insertedStatus) == false) {
            return false;
        }

        log("op01-6m: capabilitySimIccid:" + capabilitySimIccid
                + ", capabilityPhoneId:" + capabilityPhoneId);
        for (int i = 0; i < mPhoneNum; i++) {
            if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OP01) {
                if (simType[i] == RadioCapabilitySwitchUtil.SIM_TYPE_USIM) {
                    priority[i] = RadioCapabilitySwitchUtil.OP01_6M_PRIORITY_OP01_USIM;
                } else if (simType[i] == RadioCapabilitySwitchUtil.SIM_TYPE_SIM) {
                    priority[i] = RadioCapabilitySwitchUtil.OP01_6M_PRIORITY_OP01_SIM;
                }
            } else {
                priority[i] = RadioCapabilitySwitchUtil.OP01_6M_PRIORITY_OTHER;
            }
        }
        // dump priority info
        log("priority: " + Arrays.toString(priority));

        // get the highest priority SIM
        targetPhoneId = RadioCapabilitySwitchUtil.getHighestPriorityPhone(capabilityPhoneId,
                priority);
        log("op01-6m: target phone: " + targetPhoneId);
        if (targetPhoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
            log("op01-6m: highest priority SIM determined, change!");
            setCapability(targetPhoneId);
        } else {
            log("op01-6m: can't determine highest priority SIM and " + 
                    "capability sim is missed, change to default data sim");
            int defDataSubId = SubscriptionController.getInstance().getDefaultDataSubId();
            int defDataPhoneId = SubscriptionManager.getPhoneId(defDataSubId);
            if (defDataPhoneId >= 0 && defDataPhoneId < mPhoneNum) {
                // make sure default data phone had been set.
                setCapability(defDataPhoneId);
            } else {
                log("never set default data phone, don't switch");
            }
        }

        return true;
    }

    private void subSelectorForOp09(Intent intent) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = (intent == null) ? getSimStatus() :
                intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for op09");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            log("currIccId[" + i + "] : " + currIccId[i]);
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }

        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);

        if (insertedSimCount == 0) {
            // No SIM inserted
            // 1. Default Data: unset
            // 2. Data Enable: OFF
            // 3. 34G: No change
            log("OP09 C0: No SIM inserted, set data unset");
            setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
            for (int i = 0; i < mPhoneNum; i++) {
                setDataEnabled(i, false);
            }
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }

            if (detectedType == SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
                log("OP09 C1: Single SIM unchange: do nothing");
            } else if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                // Case 2: Single SIM + New SIM:
                // 1. Default Data: this sub
                // 2. Data Enable: ON
                log("OP09 C2: Single SIM + New SIM: Set Default data to phone:" + phoneId);
                setDefaultData(phoneId);
                setDataEnabled(phoneId, true);
            } else {
                // Case 3: Single SIM + old SIM:
                // 1. Default Data: this sub
                // 2. Data Enable: No change
                setDefaultData(phoneId);

                log("OP09 C3: Single SIM + Old SIM: check data enable");

                //TODO: Fix
                // Set data enabled for phoneId if the data of the other phone is enabled orginally
                String strEnabled = "0";
                if (phoneId == PhoneConstants.SIM_ID_1) {
                    strEnabled = TelephonyManager.getDefault().getTelephonyProperty(
                            PhoneConstants.SIM_ID_2, PROPERTY_MOBILE_DATA_ENABLE, "0");
                } else {
                    strEnabled = TelephonyManager.getDefault().getTelephonyProperty(
                            PhoneConstants.SIM_ID_1, PROPERTY_MOBILE_DATA_ENABLE, "0");
                }
                if (!strEnabled.equals("0") && strEnabled.length() > 1) {
                    setDataEnabled(phoneId, true);
                }
            }
        } else if (insertedSimCount >= 2) {
            if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                int newSimStatus = (intent == null) ? getNewSimSlot(): intent.getIntExtra(
                        SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, 0);

                boolean isAllNewSim = true;
                for (int i = 0; i < mPhoneNum; i++) {
                    if ((newSimStatus & (1 << i)) == 0) {
                        isAllNewSim = false;
                    }
                }

                if (isAllNewSim) {
                    // Case 4: Multi SIM + All New SIM:
                    // 1. Default Data: SIM1
                    // 2. Data Enable: SIM1 ON / others off
                    // 3. 34G: Sub1
                    log("C4: Multi SIM + All New SIM: Set 34G to sub1");
                    setDefaultData(PhoneConstants.SIM_ID_1);
                    setDataEnabled(PhoneConstants.SIM_ID_1, true);
                    //TODO: Other SIMs
                } else {
                    if (defaultIccid == null || "".equals(defaultIccid)) {
                        //Not found previous default SIM, don't change.
                        // Case 6: Multi SIM + New SIM + Non Default SIM:
                        // 1. Default Data: SIM1
                        // 2. Data Enable: SIM1 no change / SIM2 OFF
                        log("C6: Multi SIM + New SIM + Non Default SIM: No Change");
                        setDefaultData(PhoneConstants.SIM_ID_1);
                        //TODO: Other SIMs
                        setDataEnabled(PhoneConstants.SIM_ID_2, false);
                    } else {
                        for (int i = 0; i < mPhoneNum; i++) {
                            if (defaultIccid.equals(currIccId[i])) {
                                phoneId = i;
                                break;
                            }
                        }

                        if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
                            // Case 5: Multi SIM + New SIM + Default SIM:
                            // 1. Default Data: Default SIM
                            // 2. Data Enable: Old SIM No Change / New SIM OFF
                            log("C5: Multi SIM + New SIM + Default SIM: Set Default data to "
                                + "phone:" + phoneId);

                            // Set the data status of non-default sim to false
                            int nonDefaultPhoneId = 0;
                            if (phoneId == 0) {
                                nonDefaultPhoneId = 1;
                            } else {
                                nonDefaultPhoneId = 0;
                            }
                            setDataEnabled(nonDefaultPhoneId, false);
                        } else {
                            // Case 9: Multi SIM + New SIM + Non Default SIM:
                            // 1. Default Data: SIM1
                            // 2. Data Enable: SIM1 no change / SIM2 OFF
                            int isLastDataEnabled = getLastDataEnabled();
                            log("C9: Multi SIM + New SIM + isLastDataEnabled = "
                                    + isLastDataEnabled);
                            setDefaultData(PhoneConstants.SIM_ID_1);
                            setDataEnabled(PhoneConstants.SIM_ID_1,
                                    (isLastDataEnabled == 0) ? false : true);
                            //TODO: Other SIMs
                            setDataEnabled(PhoneConstants.SIM_ID_2, false);
                        }
                    }
                }
            } else {
                if (defaultIccid == null || "".equals(defaultIccid)) {
                    //Case 8: Multi SIM + All Old SIM + No Default SIM:
                    //Do nothing
                    loge("C8: Do nothing");
                } else {
                    for (int i = 0; i < mPhoneNum; i++) {
                        if (defaultIccid.equals(currIccId[i])) {
                            phoneId = i;
                            break;
                        }
                    }
                    if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
                        // Case 7: Multi SIM + All Old SIM + Default SIM:
                        // 1. Default Data: Default SIM
                        // 2. Data Enable: No Change
                        log("C7: Multi SIM + All Old SIM + Default SIM: Set Default data to phone:"
                                + phoneId);

                        // Set the data status of non-default sim to false
                        int nonDefaultPhoneId = 0;
                        if (phoneId == 0) {
                            nonDefaultPhoneId = 1;
                        } else {
                            nonDefaultPhoneId = 0;
                        }
                        setDataEnabled(nonDefaultPhoneId, false);
                    } else {
                        //Case 8: Multi SIM + All Old SIM + No Default SIM:
                        //Do nothing
                        loge("C8: Do nothing");
                    }
                }
            }
        }
    }

    private void subSelectorForOp18(Intent intent) {
        // op18 has multi policy, decide here
        switch(capability_switch_policy) {
            case POLICY_NO_AUTO:
                log("subSelectorForOp18: no auto policy, skip");
                return;
            case POLICY_DEFAULT:
                // default policy of op18 is to follow OM.
                subSelectorForOm(intent);
                return;
            case POLICY_POLICY1:
                // keep run op18 policy
                break;
            default:
                log("subSelectorForOp18: Unknow policy, skip");
                return;
        }
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for op18");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            log("currIccId[" + i + "] : " + currIccId[i]);
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            } else {
                log("clear mcc.mnc:" + i);
                String propStr;
                if (i == 0) {
                    propStr = "gsm.sim.ril.mcc.mnc";
                } else {
                    propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
                }
                SystemProperties.set(propStr, "");
            }
        }
        // check pin lock
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("DataSubSelector for OP18: do not switch because of sim locking");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            mIntent = intent;
            return;
        } else {
            log("DataSubSelector for OP18: no pin lock");
            setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
        }

        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);

        if (insertedSimCount == 0) {
            // No SIM inserted
            // 1. Default Data: unset
            // 2. Data Enable: OFF
            // 3. 34G: No change
            log("C0: No SIM inserted, set data unset");
            setDefaultData(SubscriptionManager.INVALID_PHONE_INDEX);
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }

            if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                // Case 1: Single SIM + New SIM:
                // 1. Default Data: this sub
                // 2. Data Enable: OFF
                // 3. 34G: this sub
                log("C1: Single SIM + New SIM: Set Default data to phone:" + phoneId);
                if (setCapability(phoneId)) {
                    setDefaultData(phoneId);
                }
                setDataEnabled(phoneId, true);
            } else {
                if (defaultIccid == null || "".equals(defaultIccid)) {
                    //It happened from two SIMs without default SIM -> remove one SIM.
                    // Case 3: Single SIM + Non Data SIM:
                    // 1. Default Data: this sub
                    // 2. Data Enable: OFF
                    // 3. 34G: this sub
                    log("C3: Single SIM + Non Data SIM: Set Default data to phone:" + phoneId);
                    if (setCapability(phoneId)) {
                        setDefaultData(phoneId);
                    }
                    setDataEnabled(phoneId, true);
                } else {
                    if (defaultIccid.equals(currIccId[phoneId])) {
                        // Case 2: Single SIM + Defult Data SIM:
                        // 1. Default Data: this sub
                        // 2. Data Enable: No Change
                        // 3. 34G: this sub
                        log("C2: Single SIM + Data SIM: Set Default data to phone:" + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                    } else {
                        // Case 3: Single SIM + Non Data SIM:
                        // 1. Default Data: this sub
                        // 2. Data Enable: OFF
                        // 3. 34G: this sub
                        log("C3: Single SIM + Non Data SIM: Set Default data to phone:" + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                        setDataEnabled(phoneId, true);
                    }
                }
            }
        }
        else if (insertedSimCount >= 2) {
            // data switching
            if (checkOp18CapSwitch() == false) {
                // need wait imsi ready
                mIsNeedWaitImsi = true;
                mIntent = intent;
                return;
            }
        }
    }

    private boolean checkOp18CapSwitch() {
        // op18 has multi policies, check configuration is default
        if (capability_switch_policy != POLICY_POLICY1) {
            log("checkOp18CapSwitch: config is not default policy, do nothing");
            return true;
        }
        // check if need to switch capability
        // op18 > others
        int[] simOpInfo = new int[mPhoneNum];
        int[] simType = new int[mPhoneNum];
        int targetSim = -1;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        boolean[] op18Usim = new boolean[mPhoneNum];
        String capabilitySimIccid = SystemProperties.get(RadioCapabilitySwitchUtil.MAIN_SIM_PROP);
        String[] currIccId = new String[mPhoneNum];

        log("checkOp18CapSwitch start");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return false;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        log("checkOp18CapSwitch : Inserted SIM count: " + insertedSimCount
                + ", insertedStatus: " + insertedStatus);
        if (RadioCapabilitySwitchUtil.getSimInfo(simOpInfo, simType, insertedStatus) == false) {
            return false;
        }
        // check pin lock
        String propStr;
        for (int i = 0; i < mPhoneNum; i++) {
            if (i == 0) {
                propStr = "gsm.sim.ril.mcc.mnc";
            } else {
                propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
            }
            if (SystemProperties.get(propStr, "").equals("sim_lock")) {
                log("checkOp18CapSwitch : phone " + i + " is sim lock");
                setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            }
        }
        int capabilitySimId = Integer.valueOf(
                SystemProperties.get(PhoneConstants.PROPERTY_CAPABILITY_SWITCH, "1")) - 1;
        log("op18: capabilitySimIccid:" + capabilitySimIccid
                + "capabilitySimId:" + capabilitySimId);
        for (int i = 0; i < mPhoneNum; i++) {
            // update SIM status
            if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OP18) {
                op18Usim[i] = true;
            }
        }
        // dump sim op info
        log("op18Usim: " + Arrays.toString(op18Usim));

        for (int i = 0; i < mPhoneNum; i++) {
            if (capabilitySimIccid.equals(currIccId[i])) {
                if (op18Usim[i] == true) {
                    targetSim = i;
                } else {
                    for (int j = 0; j < mPhoneNum; j++) {
                        if (op18Usim[j] == true) {
                            targetSim = j;
                        }
                    }
                }
                log("op18: i = " + i +
                    ", currIccId : " +
                    currIccId[i] + ", targetSim : " + targetSim);
                // default capability SIM is inserted
                if (op18Usim[i] == true) {
                    log("op18-C1: cur is old op18 USIM, no change");
                    if (capabilitySimId != i) {
                        log("op18-C1a: old op18 SIM change slot, change!");
                        setCapability(i);
                    }
                    setDefaultData(i);
                    setDataEnabled(i, true);
                    return true;
                } else if (targetSim != -1) {
                    log("op18-C2: cur is not op18 SIM but find op18 SIM, change!");
                    setCapability(targetSim);
                    setDefaultData(targetSim);
                    setDataEnabled(targetSim, true);
                    return true;
                }
                setDefaultData(capabilitySimId);
                setDataEnabled(capabilitySimId, true);
                log("op18-C6: no higher priority SIM, no cahnge");
                return true;
            }
        }
        // cannot find default capability SIM, check if higher priority SIM exists
        if (op18Usim[capabilitySimId] == true) {
            targetSim = capabilitySimId;
        } else {
            for (int i = 0; i < mPhoneNum; i++) {
                if (op18Usim[i] == true) {
                    targetSim = i;
                }
            }
        }
        log("op18: target SIM :" + targetSim);
        if (op18Usim[capabilitySimId] == true) {
            log("op18-C7: cur is new op18 USIM, no change");
            setDefaultData(capabilitySimId);
            setDataEnabled(capabilitySimId, true);
            return true;
        } else if (targetSim != -1) {
            log("op18-C8: find op18 USIM, change!");
            setCapability(targetSim);
            setDefaultData(targetSim);
            setDataEnabled(targetSim, true);
            return true;
        } else {
            setDefaultData(capabilitySimId);
            setDataEnabled(capabilitySimId, true);
            log("op18-C12: no higher priority SIM, no cahnge");
        }
        return true;
    }

    private void setDataEnabled(int phoneId, boolean enable) {
        log("setDataEnabled: phoneId=" + phoneId + ", enable=" + enable);

        TelephonyManager telephony = TelephonyManager.getDefault();
        if (telephony != null) {
            if (phoneId == SubscriptionManager.INVALID_PHONE_INDEX) {
                telephony.setDataEnabled(enable);
            } else {
                int phoneSubId = 0;
                if (enable == false) {
                    phoneSubId = PhoneFactory.getPhone(phoneId).getSubId();
                    log("Set Sub" + phoneSubId + " to disable");
                    telephony.setDataEnabled(phoneSubId, enable);
                } else {
                    for (int i = 0; i < mPhoneNum; i++) {
                        phoneSubId = PhoneFactory.getPhone(i).getSubId();
                        if (i != phoneId) {
                            log("Set Sub" + phoneSubId + " to disable");
                            telephony.setDataEnabled(phoneSubId, false);
                        } else {
                            log("Set Sub" + phoneSubId + " to enable");
                            telephony.setDataEnabled(phoneSubId, true);
                        }
                    }
                }
            }
        }
    }

    private void updateDataEnableProperty() {
        TelephonyManager telephony = TelephonyManager.getDefault();
        boolean dataEnabled = false;
        String dataOnIccid = "0";
        int subId = 0;

        for (int i = 0; i < mPhoneNum; i++) {
            // M: [C2K][IRAT] Change LTE_DC_SUB_ID to IRAT support slot sub ID for IRAT.
            subId = PhoneFactory.getPhone(i).getSubId();
            if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
                subId = SvlteUtils.getSvlteSubIdBySubId(subId);
            }

            if (telephony != null) {
               dataEnabled = telephony.getDataEnabled(subId);
            }

            if (dataEnabled) {
                dataOnIccid = SystemProperties.get(PROPERTY_ICCID[i], "0");
            } else {
                dataOnIccid = "0";
            }


            log("setUserDataProperty:" + dataOnIccid);
            TelephonyManager.getDefault().setTelephonyProperty(i, PROPERTY_MOBILE_DATA_ENABLE,
                    dataOnIccid);
        }
    }

    private void subSelectorForSvlte(Intent intent) {
        int c2kP2 = Integer.valueOf(
                SystemProperties.get("ro.mtk.c2k.slot2.support", "0"));
        log("subSelectorForSvlte,c2kP2 = " + c2kP2);
        //For solution 1.5,only op09 project need limite capability
        if (OPERATOR_OP09.equals(mOperatorSpec)) {
           if (RadioCapabilitySwitchUtil.isSimContainsCdmaApp(PhoneConstants.SIM_ID_1)) {
              log("CDMA sim is inserted in slot1, always set to SIM1");
              setCapability(PhoneConstants.SIM_ID_1);
              return;
           }
           if (SvlteModeController.getRadioTechnologyMode() ==
                   SvlteModeController.RADIO_TECH_MODE_SVLTE) {
              // svlte mode
              // check sim 1 status
              int[] cardType = new int[TelephonyManager.getDefault().getPhoneCount()];
              cardType = UiccController.getInstance().getC2KWPCardType();
              log("card type: " + cardType[PhoneConstants.SIM_ID_1]);
              if (cardType[PhoneConstants.SIM_ID_1] == UiccController.CARD_TYPE_NONE) {
                  log("SIM 1 is empty, don't change capability");
              } else {
                  log("SIM 1 is inserted, change capability");
                  setCapability(PhoneConstants.SIM_ID_1);
              }
           } else {
              // csfb mode, follow om project switch
              subSelectorForOm(intent);
           }

        } else {
            // not op09 project, follow om project switch
            // clear default data record for solution 1.5
            if (c2kP2 == 0) {
                clearDefaultsForInactiveSubIds();
                log("clear invalid sub id record.");
            }
            //For solution 1.5, G+C and C+G case default data and capability maybe not sync
            if ((c2kP2 == 0) && checkCdmaCardInsert()){
               subSelectorForSolution15(intent);
            } else {
               subSelectorForOm(intent);
            }
        }
    }

    private void setDefaultData(int phoneId) {
        SubscriptionController subController = SubscriptionController.getInstance();
        int sub = SubscriptionManager.getSubIdUsingPhoneId(phoneId);
        int currSub = SubscriptionManager.getDefaultDataSubId();
        mPrevDefaultDataSubId = currSub;
        // op02 hotplug
        setLastValidDefaultDataSub(currSub);

        log("setDefaultData: " + sub + ", current default sub:" + currSub +
                "last valid default sub:" + mLastValidDefaultDataSubId);
        if (sub != currSub) {
            subController.setDefaultDataSubIdWithoutCapabilitySwitch(sub);
        } else {
            log("setDefaultData: default data unchanged");
        }
    }

    private void turnOffNewSimData(Intent intent) {
        // For single SIM phones, this is a per phone property.
        if (TelephonyManager.getDefault().getSimCount() == 1) {
            log("[turnOffNewSimData] Single SIM project, don't change data enable setting");
            return;
        }

        int detectedType = (intent == null) ? getSimStatus() :
                intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        log("turnOffNewSimData detectedType = " + detectedType);

        //L MR1 Spec, turn off data if new sim inserted.
        if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
            int newSimSlot = (intent == null) ? getNewSimSlot() :
                intent.getIntExtra(SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, 0);

            log("newSimSlot = " + newSimSlot);
            log("default iccid = " + SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID));

            for (int i = 0; i < mPhoneNum; i++) {
                if ((newSimSlot & (1 << i)) != 0) {
                    String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
                    String newSimIccid = SystemProperties.get(PROPERTY_ICCID[i]);
                    if (!newSimIccid.equals(defaultIccid)) {
                        log("Detect NEW SIM, turn off phone " + i + " data.");
                        setDataEnabled(i, false);
                    }
                }
            }
        }
    }

    private boolean setCapability(int phoneId) {
        int[] phoneRat = new int[mPhoneNum];
        boolean isSwitchSuccess = true;

        log("setCapability: " + phoneId);

        String curr3GSim = SystemProperties.get(PROPERTY_3G_SIM, "");
        log("current 3G Sim = " + curr3GSim);

        if (curr3GSim != null && !curr3GSim.equals("")) {
            int curr3GPhoneId = Integer.parseInt(curr3GSim);
            if (curr3GPhoneId == (phoneId + 1) ) {
                log("Current 3G phone equals target phone, don't trigger switch");
                // maybe because of wrong infor
                // keep detectedChange when switchOffAirplandMode is true.
                if (!switchOffAirplandMode) detectedChange = false;
                return isSwitchSuccess;
            }
        }

        try {
            ITelephony iTel = ITelephony.Stub.asInterface(
                    ServiceManager.getService(Context.TELEPHONY_SERVICE));
            ITelephonyEx iTelEx = ITelephonyEx.Stub.asInterface(
                    ServiceManager.getService(Context.TELEPHONY_SERVICE_EX));
            if (null == iTel || null == iTelEx) {
                loge("Can not get phone service");
                return false;
            }

            int currRat = iTel.getRadioAccessFamily(phoneId);
            log("Current phoneRat:" + currRat);

            RadioAccessFamily[] rat = new RadioAccessFamily[mPhoneNum];
            for (int i = 0; i < mPhoneNum; i++) {
                if (phoneId == i) {
                    log("SIM switch to Phone" + i);
                    phoneRat[i] = RadioAccessFamily.RAF_LTE
                            | RadioAccessFamily.RAF_UMTS
                            | RadioAccessFamily.RAF_GSM;
                } else {
                    phoneRat[i] = RadioAccessFamily.RAF_GSM;
                }
                rat[i] = new RadioAccessFamily(i, phoneRat[i]);
            }
            if (false  == iTelEx.setRadioCapability(rat)) {
                log("Set phone rat fail!!!");
                isSwitchSuccess = false;
            }
        } catch (RemoteException ex) {
            log("Set phone rat fail!!!");
            ex.printStackTrace();
            isSwitchSuccess = false;
        }

        return isSwitchSuccess;
    }

    private boolean checkOp02CapSwitch(int policy) {
        int[] simOpInfo = new int[mPhoneNum];
        int[] simType = new int[mPhoneNum];
        int insertedStatus = 0;
        int insertedSimCount = 0;
        String currIccId[] = new String[mPhoneNum];
        ArrayList<Integer> usimIndexList = new ArrayList<Integer>();
        ArrayList<Integer> simIndexList = new ArrayList<Integer>();
        ArrayList<Integer> op02IndexList = new ArrayList<Integer>();
        ArrayList<Integer> otherIndexList = new ArrayList<Integer>();

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return false;
            }
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        log("checkOp02CapSwitch : Inserted SIM count: " + insertedSimCount
                + ", insertedStatus: " + insertedStatus);

        // check pin lock
        if (RadioCapabilitySwitchUtil.isAnySimLocked(mPhoneNum)) {
            log("checkOp02CapSwitch: sim locked");
            if (HOME_POLICY == policy) {
                setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "true");
            } else {
                setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED_ROAMING, "true");
            }
        } else {
            log("checkOp02CapSwitch: no sim locked");
            if (HOME_POLICY == policy) {
                setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED, "false");
            } else {
                setNeedWaitUnlock(NEED_TO_WAIT_UNLOCKED_ROAMING, "false");
            }
        }
        if (RadioCapabilitySwitchUtil.getSimInfo(simOpInfo, simType, insertedStatus) == false) {
            return false;
        }

        if (mAirplaneModeOn) {
            log("DataSubSelector for OP02: do not switch because of mAirplaneModeOn");
            if (HOME_POLICY == policy) {
                mIsNeedWaitAirplaneModeOff = true;
            } else if (ROAMING_POLICY == policy) {
                mIsNeedWaitAirplaneModeOffRoaming = true;
            }
        }

        for (int i = 0; i < mPhoneNum; i++) {
            if (RadioCapabilitySwitchUtil.SIM_OP_INFO_OP02 == simOpInfo[i]) {
                op02IndexList.add(i);
            } else {
                otherIndexList.add(i);
            }
            if (RadioCapabilitySwitchUtil.SIM_TYPE_USIM == simType[i]) {
                usimIndexList.add(i);
            } else {
                simIndexList.add(i);
            }
        }
        log("usimIndexList size = " + usimIndexList.size());
        log("op02IndexList size = " + op02IndexList.size());
        log("policy = " + policy);

        mIsUserConfirmDefaultData = false;
        switch(policy) {
            case HOME_POLICY:
                executeOp02HomePolicy(usimIndexList, op02IndexList, simIndexList);
                break;
            case ROAMING_POLICY:
                executeOp02RoamingPolocy(usimIndexList, op02IndexList, otherIndexList);
                break;
            default:
                loge("Should NOT be here");
                break;
        }
        return true;
    }

    private void executeOp02HomePolicy(ArrayList<Integer> usimIndexList,
        ArrayList<Integer> op02IndexList, ArrayList<Integer> simIndexList) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int op02CardCount = 0;

        log("Enter op02HomePolicy");
        // Home policy: OP02 USIM > other USIM > OP02 SIM > other SIM
        if (usimIndexList.size() >= 2) {
            // check OP02USIM count
            for (int i = 0; i < usimIndexList.size(); i++) {
                if (op02IndexList.contains(usimIndexList.get(i))) {
                    op02CardCount++;
                    phoneId = i;
                }
            }

            if (op02CardCount == 1) {
                // Case2: OP02 USIM + other USIMs/ OP02 USIM + other SIMs
                // 1. Default Data: OP02
                // 2. Data Enable: No change
                // 3. 34G: OP02
                log("C2: Only one OP02 USIM inserted, set default data to phone: " +
                        phoneId);
                setCapability(phoneId);
                setDefaultData(phoneId);
                handleDataEnableForOp02(2);
            } else {
                // Case3: More than two OP02 cards or other operator cards
                // Display dialog
                log("C3: More than two OP02 cards or other operator cards inserted," +
                        "Display dialog");
                mPrevDefaultDataSubId = SubscriptionManager.getDefaultDataSubId();
                log("mPrevDefaultDataSubId:" + mPrevDefaultDataSubId);
                mIsUserConfirmDefaultData = true;
            }
        } else if (usimIndexList.size() == 1) {
            // Case4: USIM + SIMs
            // 1. Default Data: USIM
            // 2. Data Enable: No change
            // 3. 34G: USIM
            phoneId = usimIndexList.get(0);
            log("C4: Only one USIM inserted, set default data to phone: " +
                    phoneId);
            setCapability(phoneId);
            setDefaultData(phoneId);
            handleDataEnableForOp02(2);
        } else {
            // usimCount = 0 (Case: all SIMs)
            // check OP02SIM count
            for (int i = 0; i < simIndexList.size(); i++) {
                if (op02IndexList.contains(simIndexList.get(i))) {
                    op02CardCount++;
                    phoneId = i;
                }
            }

            if (op02CardCount == 1) {
                // Case5: OP02 card + otehr op cards
                // 1. Default Data: OP02 card
                // 2. Data Enable: No change
                // 3. 34G: OP02 card
                log("C5: OP02 card + otehr op cards inserted, set default data to phone: " +
                        phoneId);
                setCapability(phoneId);
                setDefaultData(phoneId);
                handleDataEnableForOp02(2);
            } else {
                // case6: More than two OP02 cards or other operator cards
                // Display dialog
                log("C6: More than two OP02 cards or other operator cards inserted," +
                        "display dialog");
                mPrevDefaultDataSubId = SubscriptionManager.getDefaultDataSubId();
                mIsUserConfirmDefaultData = true;
            }
        }
    }

    private void executeOp02RoamingPolocy(ArrayList<Integer> usimIndexList,
            ArrayList<Integer> op02IndexList, ArrayList<Integer> otherIndexList) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int usimCount = 0;

        log("Enter op02RoamingPolocy");

        if (mContext == null) {
            loge("mContext is null, return");
        }

        // Roaming policy: OP02 USIM > OP02 SIM > other UISM > other SIM
        if (op02IndexList.size() >= 2) {
            // check OP02USIM count
            for (int i = 0; i < op02IndexList.size(); i++) {
                if (usimIndexList.contains(op02IndexList.get(i))) {
                    usimCount++;
                    phoneId = i;
                }
            }

            if (usimCount == 1) {
                // Case2: OP02 USIM + other USIMs / OP02 USIM + other SIMs
                // 1.Deafult Data: USIM
                // 2.Data Enable: No change
                // 3.34G: USIM
                log("C2: Only one OP02 USIM inserted, set default data to phone: "
                        + phoneId);
                setCapability(phoneId);
                setDefaultData(phoneId);
                handleDataEnableForOp02(2);
            } else {
                // Case3: More than two USIM cards or other SIM cards
                // Display dialog
                log("C3: More than two USIM cards or other SIM cards inserted, show dialog");
                mPrevDefaultDataSubId = SubscriptionManager.getDefaultDataSubId();
                mIsUserConfirmDefaultData = true;
            }
        } else if (op02IndexList.size() == 1) {
            // Case4: OP02 card + other cards
            // Default Data: OP02
            // Data Enable: No change
            // 34G: OP02
            phoneId = op02IndexList.get(0);
            log("C4: OP02 card + other cards inserted, set default data to phone: "
                    + phoneId);
            setCapability(phoneId);
            setDefaultData(phoneId);
            handleDataEnableForOp02(2);
        } else {
            // op02CardCount = 0 (Case: other operator cards)
            // Check otherUSIM count
            for (int i = 0; i < otherIndexList.size(); i++) {
                if (usimIndexList.contains(otherIndexList.get(i))) {
                    usimCount++;
                    phoneId = i;
                }
            }

            if (usimCount == 1) {
                // Case5: other USIM + other SIM cards
                // Default Data: USIM
                // Data Enable: No change
                // 34G: USIM
                log("C5: Other USIM + other SIM cards inserted, set default data to phone: " +
                        phoneId);
                setCapability(phoneId);
                setDefaultData(phoneId);
                handleDataEnableForOp02(2);
            } else {
                // Case6: More than two USIM cards or all SIM cards
                // Display dialog
                log("C6: More than two USIM cards or all SIM cards inserted, diaplay dialog");
                mPrevDefaultDataSubId = SubscriptionManager.getDefaultDataSubId();
                mIsUserConfirmDefaultData = true;
            }
        }

        SharedPreferences preferenceRoaming = mContext.getSharedPreferences(FIRST_TIME_ROAMING,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editorRoaming = preferenceRoaming.edit();
        editorRoaming.putBoolean(NEED_TO_EXECUTE_ROAMING, false);
        if (!editorRoaming.commit()) {
            loge("write sharedPreference ERROR");
        }
    }

    private void handleDataEnableForOp02(int insertedSimCount) {
        log("handleDataEnableForOp02: insertedSimCount = " + insertedSimCount);

        TelephonyManager telephony = TelephonyManager.getDefault();
        if (telephony == null) {
            loge("TelephonyManager.getDefault() return null");
            return;
        }

        int nDefaultDataSubId = SubscriptionManager.getDefaultDataSubId();
        if (!SubscriptionManager.isValidSubscriptionId(mPrevDefaultDataSubId)) {
            if (SubscriptionManager.isValidSubscriptionId(mLastValidDefaultDataSubId)) {
                boolean enable = getDataEnabledFromSetting(mLastValidDefaultDataSubId);
                log("setEnable by lastValidDataSub's setting = " + enable);
                setDataEnabled(SubscriptionManager.getPhoneId(nDefaultDataSubId), enable);
            } else {
                setDataEnabled(SubscriptionManager.getPhoneId(nDefaultDataSubId), true);
            }
        } else if (SubscriptionManager.isValidSubscriptionId(mPrevDefaultDataSubId)
                && SubscriptionManager.isValidSubscriptionId(nDefaultDataSubId)) {
            if (mPrevDefaultDataSubId != nDefaultDataSubId) {
                if (getDataEnabledFromSetting(mPrevDefaultDataSubId)) {
                    setDataEnabled(SubscriptionManager.getPhoneId(nDefaultDataSubId), true);
                } else {
                    setDataEnabled(SubscriptionManager.getPhoneId(nDefaultDataSubId), false);
                }
            } else if (insertedSimCount == 2) {
                int nonDefaultPhoneId = 0;
                if (SubscriptionManager.getPhoneId(nDefaultDataSubId) == 0) {
                    nonDefaultPhoneId = 1;
                } else {
                    nonDefaultPhoneId = 0;
                }
                if (getDataEnabledFromSetting(nDefaultDataSubId)) {
                    setDataEnabled(nonDefaultPhoneId, false);
                }
            }
        }
    }

    private void subSelectorForOp09C(Intent intent) {
        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for op09 C");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID[i]);
            if (currIccId[i] == null || "".equals(currIccId[i])) {
                log("error: iccid not found, wait for next sub ready");
                return;
            }
            log("currIccId[" + i + "] : " + currIccId[i]);
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);

        if (insertedSimCount == 0) {
            log("OP09 C0: No SIM inserted, do nothing.");
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }

            if (detectedType == SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
                log("OP09 C1: Single SIM unchange: do nothing");
            } else if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                // Case 2: Single SIM + New SIM:
                // 1. Default Data: this sub
                // 2. Data Enable: ON
                log("OP09 C2: Single SIM + New SIM: Set Default data to phone:" + phoneId);
                setCapability(phoneId);
                setDefaultData(phoneId);

                setDataEnabled(phoneId, true);
            } else {
                // Case 3: Single SIM and it's old SIM:
                // 1. Default Data: this sub
                // 2. Data Enable: No change
                setCapability(phoneId);
                setDefaultData(phoneId);
                log("OP09 C3: Single SIM + Old SIM: set as default data.");

                // Set data enabled for phoneId if the data of the other phone is enabled orginally
                String strEnabled = "0";
                if (phoneId == PhoneConstants.SIM_ID_1) {
                    strEnabled = TelephonyManager.getDefault().getTelephonyProperty(
                            PhoneConstants.SIM_ID_2, PROPERTY_MOBILE_DATA_ENABLE, "0");
                } else {
                    strEnabled = TelephonyManager.getDefault().getTelephonyProperty(
                            PhoneConstants.SIM_ID_1, PROPERTY_MOBILE_DATA_ENABLE, "0");
                }
                if (!strEnabled.equals("0") && strEnabled.length() > 1) {
                    setDataEnabled(phoneId, true);
                }
            }
        } else if (insertedSimCount >= 2) {
            if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                int newSimStatus = intent.getIntExtra(
                        SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, 0);

                boolean isAllNewSim = true;
                for (int i = 0; i < mPhoneNum; i++) {
                    if ((newSimStatus & (1 << i)) == 0) {
                        isAllNewSim = false;
                    }
                }

                if (isAllNewSim) {
                    // Case 4: Multi SIM + All New SIM:
                    // 1. Default Data: SIM1
                    // 2. Data Enable: SIM1 ON / others off
                    // 3. 34G: Sub1
                    log("C4: Multi SIM + All New SIM: Set 34G to sub1");
                    setCapability(PhoneConstants.SIM_ID_1);
                    setDefaultData(PhoneConstants.SIM_ID_1);

                    setDataEnabled(PhoneConstants.SIM_ID_1, true);
                    setDataEnabled(PhoneConstants.SIM_ID_2, false);
                } else {
                    if (defaultIccid == null || "".equals(defaultIccid)) {
                        //Not found previous default SIM, don't change.
                        // Case 6: Multi SIM + New SIM + Non Default SIM:
                        // 1. Default Data: SIM1
                        // 2. Data Enable: SIM1 no change / SIM2 OFF
                        log("C6: Multi SIM + New SIM + Non Default SIM: No Change");
                        setCapability(PhoneConstants.SIM_ID_1);
                        setDefaultData(PhoneConstants.SIM_ID_1);

                        setDataEnabled(PhoneConstants.SIM_ID_1, true);
                        setDataEnabled(PhoneConstants.SIM_ID_2, false);
                    } else {
                        for (int i = 0; i < mPhoneNum; i++) {
                            if (defaultIccid.equals(currIccId[i])) {
                                phoneId = i;
                                break;
                            }
                        }

                        if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
                            // Case 5: Multi SIM + New SIM + Default SIM:
                            // 1. Default Data: Default SIM
                            // 2. Data Enable: Old SIM No Change / New SIM OFF
                            log("C5: Multi SIM + New SIM + Default SIM: Keep Set Default data to "
                                + "phone:" + phoneId);
                            // Set the data status of non-default sim to false
                            int nonDefaultPhoneId = 0;
                            if (phoneId == 0) {
                                nonDefaultPhoneId = 1;
                            } else {
                                nonDefaultPhoneId = 0;
                            }
                            setDataEnabled(nonDefaultPhoneId, false);
                        } else {
                            // Case 7: Multi SIM + New SIM + Non Default SIM:
                            // 1. Default Data: SIM1
                            // 2. Data Enable: SIM1 no change / SIM2 OFF
                            log("C7: Multi SIM + New SIM + Non Default SIM: set to sim1.");
                            setCapability(PhoneConstants.SIM_ID_1);
                            setDefaultData(PhoneConstants.SIM_ID_1);

                            setDataEnabled(PhoneConstants.SIM_ID_1, true);
                            setDataEnabled(PhoneConstants.SIM_ID_2, false);
                        }
                    }
                }
            } else {
                //Case 8: Multi SIM + All Old SIM.
                // 1. Default Data: Default SIM
                // 2. Follow OM rule
                loge("C8: Multi SIM + All Old SIM :set as previous default data");
                subSelectorForOm(intent);
            }
        }

        updateDataEnableProperty();
    }

    private void clearDefaultsForInactiveSubIds() {
        SubscriptionController subController = SubscriptionController.getInstance();

        final List<SubscriptionInfo> records = subController.getActiveSubscriptionInfoList();
        log("[clearDefaultsForInactiveSubIds] records: " + records);
        if (shouldDefaultBeCleared(records, subController.getDefaultDataSubId())) {
            log("[clearDefaultsForInactiveSubIds] clearing default data sub id");
            subController.setDefaultDataSubIdWithoutCapabilitySwitch(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        }
    }

    private boolean shouldDefaultBeCleared(List<SubscriptionInfo> records, int subId) {
        log("[shouldDefaultBeCleared: subId] " + subId);
        if (records == null) {
            log("[shouldDefaultBeCleared] return true no records subId=" + subId);
            return true;
        }
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            // If the subId parameter is not valid its already cleared so return false.
            log("[shouldDefaultBeCleared] return false only one subId, subId=" + subId);
            return false;
        }
        for (SubscriptionInfo record : records) {
            int id = record.getSubscriptionId();
            log("[shouldDefaultBeCleared] Record.id: " + id);
            if (id == subId) {
                log("[shouldDefaultBeCleared] return false subId is active, subId=" + subId);
                return false;
            }
        }
        log("[shouldDefaultBeCleared] return true not active subId=" + subId);
        return true;
    }

    private void log(String txt) {
        if (DBG) {
            Rlog.d("DataSubSelector", txt);
        }
    }

    private void loge(String txt) {
        if (DBG) {
            Rlog.e("DataSubSelector", txt);
        }
    }

    /**
     * Return enable, whether the data of the Last card is enabled.
     */
    private int getLastDataEnabled() {
        int subId = SubscriptionManager.getDefaultDataSubId();
        log("DataSubselector getLastDataEnable subId = " + subId);
        int enabled = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MOBILE_DATA + subId, 0);
        return enabled;
    }

    private void setLastValidDefaultDataSub(int subId) {
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            log("setLastValidDefaultDataSub = " + subId);
            mLastValidDefaultDataSubId = subId;
        } else {
            log("because invalid id to set, keep LastValidDefaultDataSub = "
                    + mLastValidDefaultDataSubId);
        }
    }

    private boolean getDataEnabledFromSetting(int nSubId) {
        log("getDataEnabledFromSetting, nSubId = " + nSubId);

        if (mContext == null || mContext.getContentResolver() == null) {
            log("getDataEnabledFromSetting, context or resolver is null, return");
            return false;
        }

        boolean retVal = false;
        try {
            retVal = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.MOBILE_DATA + nSubId) != 0;
        } catch (SettingNotFoundException snfe) {
            retVal = false;
        }

        log("getDataEnabledFromSetting, retVal = " + retVal);
        return retVal;
    }

    private boolean isNeedWaitUnlock(String prop) {
        return (SystemProperties.getBoolean(prop, false));
    }

    private void setNeedWaitUnlock(String prop, String value) {
        SystemProperties.set(prop, value);
    }

    private void setSimStatus(Intent intent) {
        if (intent == null) {
            log("setSimStatus, intent is null => return");
            return;
        }
        log("setSimStatus");
        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        SystemProperties.set(SIM_STATUS, Integer.toString(detectedType));
    }

    private void resetSimStatus() {
        log("resetSimStatus");
        SystemProperties.set(SIM_STATUS, "");
    }

    private int getSimStatus() {
        log("getSimStatus");
        return SystemProperties.getInt(SIM_STATUS, 0);
    }

    private void setNewSimSlot(Intent intent) {
        if (intent == null) {
            log("setNewSimSlot, intent is null => return");
            return;
        }
        log("setNewSimSlot");
        int newSimStatus = intent.getIntExtra(SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, 0);
        SystemProperties.set(NEW_SIM_SLOT, Integer.toString(newSimStatus));
    }

    private void resetNewSimSlot() {
        log("resetNewSimSlot");
        SystemProperties.set(NEW_SIM_SLOT, "");
    }

    private int getNewSimSlot() {
        log("getNewSimSlot");
        return SystemProperties.getInt(NEW_SIM_SLOT, 0);
    }
}