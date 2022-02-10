LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

vowifi_adapter_dir := VowifiAdapter

LOCAL_MODULE_TAGS := optional
LOCAL_PACKAGE_NAME := ims
LOCAL_CERTIFICATE := platform

LOCAL_JAVA_LIBRARIES := telephony-common \
        ims-common \
        radio_interactor_common \
        unisoc_ims_common \
        android.hardware.radio-V1.0-java

LOCAL_PRIVILEGED_MODULE := true

LOCAL_PROGUARD_ENABLED := disabled
LOCAL_PROTOC_OPTIMIZE_TYPE := micro

LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_SRC_FILES += $(call all-java-files-under, $(vowifi_adapter_dir)/src)

res_dirs := res $(vowifi_adapter_dir)/res
LOCAL_RESOURCE_DIR := $(addprefix $(LOCAL_PATH)/, $(res_dirs))
LOCAL_AAPT_FLAGS := --auto-add-overlay

LOCAL_PRIVATE_PLATFORM_APIS := true

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))

