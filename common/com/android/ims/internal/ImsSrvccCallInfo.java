
package com.android.ims.internal;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public class ImsSrvccCallInfo implements Parcelable {
    public int mCallId;
    public int mDir;
    public int mCallState;
    public int mHoldState;
    public int mMptyState;
    public int mMptyOrder;
    public int mCallType;
    public int mNumType;
    public String mNumber;

    public ImsSrvccCallInfo() {
    }

    public ImsSrvccCallInfo(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mCallId);
        out.writeInt(mDir);
        out.writeInt(mCallState);
        out.writeInt(mHoldState);
        out.writeInt(mMptyState);
        out.writeInt(mMptyOrder);
        out.writeInt(mCallType);
        out.writeInt(mNumType);
        out.writeString(mNumber);
    }

    private void readFromParcel(Parcel in) {
        mCallId = in.readInt();
        mDir = in.readInt();
        mCallState = in.readInt();
        mHoldState = in.readInt();
        mMptyState = in.readInt();
        mMptyOrder = in.readInt();
        mCallType = in.readInt();
        mNumType = in.readInt();
        mNumber = in.readString();
    }

    @Override
    public String toString() {
        return ImsSrvccCallInfo.class.toString() + ":" +
                "mCallId: " + mCallId
                + ", mDir: " + mDir
                + ", mCallState: " + mCallState
                + ", mHoldState=" + mHoldState
                + ", mMptyState: " + mMptyState
                + ", mMptyOrder:"+mMptyOrder
                + ", mCallType:"+mCallType
                + ", mNumType:"+mNumType
                + ", mNumber:"+mNumber;
    }

    public String toAtCommands(){
        return "\"" + mCallId
                + "," + mDir
                + "," + mCallState
                + "," + mHoldState
                + "," + mMptyState
                + ","+mMptyOrder
                + ","+mCallType
                + ","+mNumType
                + ","+mNumber + "\"";
    }

    public static final Creator<ImsSrvccCallInfo> CREATOR =
            new Creator<ImsSrvccCallInfo>() {
        @Override
        public ImsSrvccCallInfo createFromParcel(Parcel in) {
            return new ImsSrvccCallInfo(in);
        }

        @Override
        public ImsSrvccCallInfo[] newArray(int size) {
            return new ImsSrvccCallInfo[size];
        }
    };
}
