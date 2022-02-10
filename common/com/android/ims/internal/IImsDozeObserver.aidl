package com.android.ims.internal;

/**
 * @hide
 */
interface IImsDozeObserver {

   /* notify if Doze mode can be enabled
    * if switchedOn is true, Doze mode can be enabled;
    * if switchedOn is false, Doze mode should be disabled.
    */
   void onDozeModeOnOff(boolean switchedOn);

}
