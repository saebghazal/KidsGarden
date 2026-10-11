# Native forwarding engine for Kids Garden (T041).
#
# Builds libkg-tunnel.so from the audited hev-socks5-tunnel submodule (pinned to upstream
# commit 07bea57, see specs/001-kids-garden-v1/evidence/forwarding-engine-selection.md) plus
# our own JNI bridge. Upstream's JNI file is excluded: its entry points take a config
# file path, and ours pass the config in memory.

KG_CPP_PATH := $(call my-dir)
HEV_PATH := $(KG_CPP_PATH)/hev-socks5-tunnel

include $(HEV_PATH)/third-part/yaml/Android.mk
include $(HEV_PATH)/third-part/lwip/Android.mk
include $(HEV_PATH)/third-part/hev-task-system/Android.mk

LOCAL_PATH := $(HEV_PATH)
SRCDIR := $(HEV_PATH)/src
# Fixed revision string so the build never shells out to git.
REV_ID := 07bea57
include $(HEV_PATH)/build.mk

KG_HEV_SRC := $(filter-out src/hev-jni.c,$(patsubst $(SRCDIR)/%,src/%,$(SRCFILES)))

include $(CLEAR_VARS)
LOCAL_PATH := $(HEV_PATH)
LOCAL_MODULE := kg-tunnel
LOCAL_SRC_FILES := $(KG_HEV_SRC) ../kg_tunnel_jni.c
LOCAL_C_INCLUDES := \
    $(HEV_PATH)/src \
    $(HEV_PATH)/src/misc \
    $(HEV_PATH)/src/core/include \
    $(HEV_PATH)/third-part/yaml/include \
    $(HEV_PATH)/third-part/lwip/src/include \
    $(HEV_PATH)/third-part/lwip/src/ports/include \
    $(HEV_PATH)/third-part/hev-task-system/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY $(VERSION_CFLAGS)
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
# 16 KB page-size compatible (Android 15+).
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
