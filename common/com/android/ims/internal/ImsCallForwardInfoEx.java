
package com.android.ims.internal;

import android.os.Parcel;
import android.os.Parcelable;



/**
  * @hide
  */
public class ImsCallForwardInfoEx implements Parcelable {

    public int mCondition;
    public int mStatus;
    public int mToA;
    public String mNumber;
    public int mTimeSeconds;
    public String  mRuleset;
    public int mNumberType;
    public int mServiceClass;

    public ImsCallForwardInfoEx() {
    }

    public ImsCallForwardInfoEx(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mCondition);
        out.writeInt(mStatus);
        out.writeInt(mToA);
        out.writeString(mNumber);
        out.writeInt(mTimeSeconds);
        out.writeString(mRuleset);
        out.writeInt(mNumberType);
        out.writeInt(mServiceClass);
    }

    @Override
    public String toString() {
        return super.toString() + ", Condition: " + mCondition
            + ", Status: " + ((mStatus == 0) ? "disabled" : "enabled")
            + ", ToA: " + mToA + ", Number=" + mNumber
            + ", Time (seconds): " + mTimeSeconds
            + ", mRuleset:"+mRuleset
            + ", mNumberType:"+mNumberType
            + ", mServiceClass:"+mServiceClass;
    }

    private void readFromParcel(Parcel in) {
        mCondition = in.readInt();
        mStatus = in.readInt();
        mToA = in.readInt();
        mNumber = in.readString();
        mTimeSeconds = in.readInt();
        mRuleset = in.readString();
        mNumberType = in.readInt();
        mServiceClass = in.readInt();
    }

    public static final Creator<ImsCallForwardInfoEx> CREATOR =
            new Creator<ImsCallForwardInfoEx>() {
        @Override
        public ImsCallForwardInfoEx createFromParcel(Parcel in) {
            return new ImsCallForwardInfoEx(in);
        }

        @Override
        public ImsCallForwardInfoEx[] newArray(int size) {
            return new ImsCallForwardInfoEx[size];
        }
    };
}
