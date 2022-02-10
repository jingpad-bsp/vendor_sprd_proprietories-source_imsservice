package com.spreadtrum.ims;

public class ImsServiceState {
    public boolean mImsRegistered = false;
    public int mRegState = -1;
    public int mSrvccState = -1;

    public ImsServiceState(boolean imsRegistered, int regState){
        mImsRegistered = imsRegistered;
        mRegState = regState;
    }
}
