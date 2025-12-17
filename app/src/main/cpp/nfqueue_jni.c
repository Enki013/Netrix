/**
 * nfqueue_jni.c
 * 
 * JNI bridge between Kotlin and native NFQUEUE handler.
 */

#include <jni.h>
#include <string.h>
#include <pthread.h>

#include "nfqueue_handler.h"

#include <android/log.h>

#define LOG_TAG "NfqueueJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global JVM reference
static JavaVM* g_jvm = NULL;

// Callback class and method
static jclass g_callback_class = NULL;
static jmethodID g_callback_method = NULL;
static jobject g_callback_object = NULL;

// Mutex for callback
static pthread_mutex_t g_callback_lock = PTHREAD_MUTEX_INITIALIZER;

/**
 * Called when library is loaded
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    LOGI("NFQUEUE JNI loaded");
    return JNI_VERSION_1_6;
}

/**
 * Called when library is unloaded
 */
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    (void)vm;
    (void)reserved;
    
    pthread_mutex_lock(&g_callback_lock);
    
    if (g_callback_object != NULL) {
        JNIEnv* env;
        if ((*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6) == JNI_OK) {
            (*env)->DeleteGlobalRef(env, g_callback_object);
        }
        g_callback_object = NULL;
    }
    
    if (g_callback_class != NULL) {
        JNIEnv* env;
        if ((*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6) == JNI_OK) {
            (*env)->DeleteGlobalRef(env, g_callback_class);
        }
        g_callback_class = NULL;
    }
    
    pthread_mutex_unlock(&g_callback_lock);
    
    LOGI("NFQUEUE JNI unloaded");
}

/**
 * Get JNIEnv for current thread
 */
static JNIEnv* get_env(void) {
    JNIEnv* env = NULL;
    if (g_jvm == NULL) return NULL;
    
    int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) {
            return NULL;
        }
    }
    
    return env;
}

/**
 * Native packet callback - calls Kotlin callback
 */
static NfqueueVerdict native_callback(NfqueuePacket* packet, void* user_data) {
    (void)user_data;
    
    pthread_mutex_lock(&g_callback_lock);
    
    if (g_callback_object == NULL || g_callback_method == NULL) {
        pthread_mutex_unlock(&g_callback_lock);
        return NFQUEUE_ACCEPT;
    }
    
    JNIEnv* env = get_env();
    if (env == NULL) {
        pthread_mutex_unlock(&g_callback_lock);
        return NFQUEUE_ACCEPT;
    }
    
    // Create byte array for payload
    jbyteArray payload_array = NULL;
    if (packet->payload != NULL && packet->payload_len > 0) {
        payload_array = (*env)->NewByteArray(env, packet->payload_len);
        if (payload_array != NULL) {
            (*env)->SetByteArrayRegion(env, payload_array, 0, 
                                       packet->payload_len, (jbyte*)packet->payload);
        }
    }
    
    // Call Kotlin callback
    // Signature: onPacket(packetId: Int, protocol: Int, srcIp: Int, dstIp: Int,
    //                     srcPort: Int, dstPort: Int, payload: ByteArray?): Int
    jint verdict = (*env)->CallIntMethod(env, g_callback_object, g_callback_method,
                                         (jint)packet->packet_id,
                                         (jint)packet->protocol,
                                         (jint)packet->src_ip,
                                         (jint)packet->dst_ip,
                                         (jint)packet->src_port,
                                         (jint)packet->dst_port,
                                         payload_array);
    
    // Check for exception
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        verdict = NFQUEUE_ACCEPT;
    }
    
    // Clean up local ref
    if (payload_array != NULL) {
        (*env)->DeleteLocalRef(env, payload_array);
    }
    
    pthread_mutex_unlock(&g_callback_lock);
    
    return (NfqueueVerdict)verdict;
}

// ============================================================================
// JNI Methods
// ============================================================================

/**
 * Initialize NFQUEUE
 */
JNIEXPORT jboolean JNICALL
Java_com_enki_netrix_native_NfqueueBridge_nativeInit(
    JNIEnv* env, jclass clazz, jint queue_num) {
    
    (void)clazz;
    
    LOGI("Initializing NFQUEUE with queue=%d", queue_num);
    
    int result = nfqueue_init((uint16_t)queue_num);
    if (result < 0) {
        LOGE("nfqueue_init failed: %s", nfqueue_get_error());
        return JNI_FALSE;
    }
    
    nfqueue_set_callback(native_callback, NULL);
    
    return JNI_TRUE;
}

/**
 * Set packet callback
 */
JNIEXPORT jboolean JNICALL
Java_com_enki_netrix_native_NfqueueBridge_nativeSetCallback(
    JNIEnv* env, jclass clazz, jobject callback) {
    
    (void)clazz;
    
    pthread_mutex_lock(&g_callback_lock);
    
    // Clean up old callback
    if (g_callback_object != NULL) {
        (*env)->DeleteGlobalRef(env, g_callback_object);
        g_callback_object = NULL;
    }
    if (g_callback_class != NULL) {
        (*env)->DeleteGlobalRef(env, g_callback_class);
        g_callback_class = NULL;
    }
    g_callback_method = NULL;
    
    if (callback == NULL) {
        pthread_mutex_unlock(&g_callback_lock);
        return JNI_TRUE;
    }
    
    // Get callback class and method
    jclass callback_class = (*env)->GetObjectClass(env, callback);
    if (callback_class == NULL) {
        LOGE("Failed to get callback class");
        pthread_mutex_unlock(&g_callback_lock);
        return JNI_FALSE;
    }
    
    // Method signature: (IIIIII[B)I
    jmethodID method = (*env)->GetMethodID(env, callback_class, "onPacket",
                                           "(IIIIII[B)I");
    if (method == NULL) {
        LOGE("Failed to get onPacket method");
        (*env)->ExceptionClear(env);
        pthread_mutex_unlock(&g_callback_lock);
        return JNI_FALSE;
    }
    
    // Create global refs
    g_callback_class = (*env)->NewGlobalRef(env, callback_class);
    g_callback_object = (*env)->NewGlobalRef(env, callback);
    g_callback_method = method;
    
    LOGI("Callback set successfully");
    pthread_mutex_unlock(&g_callback_lock);
    
    return JNI_TRUE;
}

/**
 * Start NFQUEUE processing
 */
JNIEXPORT jboolean JNICALL
Java_com_enki_netrix_native_NfqueueBridge_nativeStart(
    JNIEnv* env, jclass clazz) {
    
    (void)env;
    (void)clazz;
    
    LOGI("Starting NFQUEUE");
    
    int result = nfqueue_start();
    if (result < 0) {
        LOGE("nfqueue_start failed: %s", nfqueue_get_error());
        return JNI_FALSE;
    }
    
    return JNI_TRUE;
}

/**
 * Stop NFQUEUE processing
 */
JNIEXPORT void JNICALL
Java_com_enki_netrix_native_NfqueueBridge_nativeStop(
    JNIEnv* env, jclass clazz) {
    
    (void)env;
    (void)clazz;
    
    LOGI("Stopping NFQUEUE");
    nfqueue_stop();
}

/**
 * Cleanup NFQUEUE
 */
JNIEXPORT void JNICALL
Java_com_enki_netrix_native_NfqueueBridge_nativeCleanup(
    JNIEnv* env, jclass clazz) {
    
    (void)env;
    (void)clazz;
    
    LOGI("Cleaning up NFQUEUE");
    nfqueue_cleanup();
}

/**
 * Check if running
 */
JNIEXPORT jboolean JNICALL
Java_com_enki_netrix_native_NfqueueBridge_nativeIsRunning(
    JNIEnv* env, jclass clazz) {
    
    (void)env;
    (void)clazz;
    
    return nfqueue_is_running() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Set verdict manually
 */
JNIEXPORT jboolean JNICALL
Java_com_enki_netrix_native_NfqueueBridge_nativeSetVerdict(
    JNIEnv* env, jclass clazz, jint packet_id, jint verdict, 
    jbyteArray modified_payload) {
    
    (void)clazz;
    
    uint8_t* payload = NULL;
    uint32_t payload_len = 0;
    
    if (modified_payload != NULL) {
        payload_len = (*env)->GetArrayLength(env, modified_payload);
        payload = (uint8_t*)(*env)->GetByteArrayElements(env, modified_payload, NULL);
    }
    
    int result = nfqueue_set_verdict_manual(
        (uint32_t)packet_id, 
        (NfqueueVerdict)verdict,
        payload,
        payload_len
    );
    
    if (payload != NULL) {
        (*env)->ReleaseByteArrayElements(env, modified_payload, (jbyte*)payload, JNI_ABORT);
    }
    
    return (result == 0) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Get last error
 */
JNIEXPORT jstring JNICALL
Java_com_enki_netrix_native_NfqueueBridge_nativeGetError(
    JNIEnv* env, jclass clazz) {
    
    (void)clazz;
    
    return (*env)->NewStringUTF(env, nfqueue_get_error());
}

