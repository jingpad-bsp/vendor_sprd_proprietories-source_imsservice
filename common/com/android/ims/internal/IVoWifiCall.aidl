package com.android.ims.internal;

import com.android.ims.internal.IVoWifiCallCallback;
import android.view.Surface;
import android.os.Bundle;

/**
 * @hide
 */
interface IVoWifiCall {

    void registerCallback(IVoWifiCallCallback callback);

    void unregisterCallback(IVoWifiCallCallback callback);

    void startAudioStream();

    void stopAudioStream();

    /**
     * Establishing session call with video or audio.
     * @return The id of this new created session on succeed, otherwise return
     *         {@link Utils#RESULT_INVALID_ID}.
     */
    int sessCall(String peerNumber, String cookie, boolean needAudio, boolean needVideo,
            boolean ussd, boolean isEmergency);

    /**
     * Set the mute status of microphone.
     *
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int sessSetMicMute(int sessionId, boolean needMute);

    /**
     * Terminate the call for the given seesion id.
     *
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int sessTerm(int sessionId, int termReason);

    /**
     * Hold the call for the given seesion id.
     *
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int sessHold(int sessionId);

    /**
     * Resume the call for the given seesion id.
     *
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int sessResume(int sessionId);

    /**
     * Send the DTMF info.
     *
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int sessDtmf(int sessionId, int dtmfType);

    /**
     * Answer the call.
     *
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int sessAnswer(int sessionId, String cookie, boolean needAudio, boolean needVideo);

    /**
     * Update the call. The update in this function means open a new media stream or close an
     * already exist media stream. The result will be notified by callback.
     *
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int sessUpdate(int sessionId, int videoType);

    int sessRelease(int sessionId);

    int sessUpdateSRVCCResult(int sessionId, int result);

    int confCall(in String[] phoneNumbers, String cookie, boolean isVideo);

    int confInit(boolean isVideo);

    int confSetup(int confId, String cookie);

    int confHold(int confId);

    int confResume(int confId);

    int confAddMembers(int confId, in String[] phoneNumbers, in int[] sessionIds);

    int confAcceptInvite(int confId);

    int confTerm(int confId, int reason);

    int confKickMembers(int confId, in String[] phoneNumbers);

    int confSetMute(int confId, boolean needMute);

    int confSetLocalImageForTrans(int confId, String uri, boolean start);

    int confRelease(int confId);

    int confUpdateSRVCCResult(int confId, int result);

    int confDtmf(int sessionId, int dtmfType);

    // Camera
    /**
     * Session attach the camera.
     *
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int cameraAttach(boolean isConf, int callId, boolean isFrontCamera);

    /**
     * Session detach the camera.
     *
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int cameraDetach(boolean isConf, int callId);

    /**
     * Session start the video transmission.
     *
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int videoStart(boolean isConf, int callId);

    /**
     * Session stop the video transmission.
     *
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int videoStop(boolean isConf, int callId);

    // ZmfVideo
    /**
     * Start running video capture device.
     *
     * @param  isFrontCamera If use the front camera to capture the video.
     * @param  width         The captured image width in pixel.
     * @param  height        The captured image height in pixel.
     * @param  frameRate     The captured frame rate in fps.
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int captureStart(boolean isFrontCamera, int width, int height, int frameRate);

    /**
     * Stop running video capture device.
     *
     * @param isFrontCamera If stop the capture video for the front camera.
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int captureStop(boolean isFrontCamera);

    /**
     * Stop all the running video capture device.
     *
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int captureStopAll();

    /**
     * Set remote rotation angle according to the coordination of the window.
     *
     * @param sessionId The render source session id.
     * @param angle     The rotation angle, @ref ZmfRotationAngle.
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int remoteRenderRotate(boolean isConf, int callId, int angle);

    /**
     * Set local rotation angle according to the coordination of the window.
     *
     * @param isFrontCamera If use the front camera to render.
     * @param angle         The rotation angle, @ref ZmfRotationAngle.
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int localRenderRotate(boolean isFrontCamera, int angle, int deviceOrientation);

    /**
     * Attach render source to specific window.
     *
     * @param surface   The surface view object to render on.
     * @param sessionId The render source session id.
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int remoteRenderAdd(in Surface surface, boolean isConf, int callId);

    /**
     * Attach render source to specific window.
     *
     * @param surface       The surface view object to render on.
     * @param isFrontCamera If use the front camera to render add.
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int localRenderAdd(in Surface surface, boolean isFrontCamera);

    /**
     * Detach render source from special window.
     *
     * @param surface   The surface which render remove.
     * @param sessionId The render source session id.
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int remoteRenderRemove(in Surface surface, boolean isConf, int callId);

    /**
     * Detach render source from special window.
     *
     * @param surface       The surface which render remove.
     * @param isFrontCamera If use the front camera to render remove.
     * @return {@link Utils#RESULT_FAIL} as fail.
     *         {@link Utils#RESULT_SUCCESS} as success.
     */
    int localRenderRemove(in Surface surface, boolean isFrontCamera);

    int setDefaultVideoLevel(int videoLevel);

    int getDefaultVideoLevel();

    int sendSessionModifyRequest(int sessionId, int videoType);

    int sendSessionModifyResponse(int sessionId, int videoType);

    int getMediaLostRatio(int id, boolean isConference, boolean isVideo);

    int getMediaJitter(int id, boolean isConference, boolean isVideo);

    int getMediaRtt(int id, boolean isConference, boolean isVideo);

    int sendUSSDMessage(int sessionId, String message);

    int updateDataRouterState(int attachSessionId, int state);

    int updateCurCallSlot(int slodId);
}
