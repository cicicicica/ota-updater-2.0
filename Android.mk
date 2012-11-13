LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := OTAUpdater
LOCAL_CERTIFICATE := platform

LOCAL_MODULE_TAGS := optional
LOCAL_SDK_VERSION := 16

LOCAL_SRC_FILES := $(call all-java-files-under, src)
#    ./src/com/otaupdater/IDownloadService.aidl

LOCAL_AIDL_INCLUDES += src
#LOCAL_JAVACFLAGS += -Xlint:deprecation

LOCAL_STATIC_JAVA_LIBRARIES += android-support-v4 gcm commons-net-3.1 annotations

include $(BUILD_PACKAGE)

# prebuilt gcm.jar
# ============================================================
include $(CLEAR_VARS)
LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := gcm:libs/gcm.jar
include $(BUILD_MULTI_PREBUILT)


# prebuilt commons-net-3.1
# ============================================================
include $(CLEAR_VARS)
LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := commons-net-3.1:libs/commons-net-3.1.jar
include $(BUILD_MULTI_PREBUILT)


# prebuilt annotations.jar
# ============================================================
include $(CLEAR_VARS)
LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := annotations:libs/annotations.jar
include $(BUILD_MULTI_PREBUILT)

# Use the following include to make our test apk.
include $(call all-makefiles-under,$(LOCAL_PATH))