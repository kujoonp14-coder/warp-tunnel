#include <jni.h>
#include <string>
#include <stdio.h>
#include <time.h>

const std::string EXPECTED_SIGNATURE_HASH = "437FA9EAE3A6D3681E7D77538B1D7E45053A4B9307DC3B7361FB11BE6D9A7387";

// 1. App Signature (Keystore SHA-256)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_myanmar_warpvpn_NativeUtils_verifyAppSignature(JNIEnv *env, jobject thiz, jobject context) {
    if (context == NULL) return JNI_FALSE;

    jclass contextClass = env->GetObjectClass(context);
    jmethodID getPackageManager = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jmethodID getPackageName = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");

    jobject packageManager = env->CallObjectMethod(context, getPackageManager);
    jstring packageName = (jstring)env->CallObjectMethod(context, getPackageName);

    jclass pmClass = env->GetObjectClass(packageManager);
    jmethodID getPackageInfo = env->GetMethodID(pmClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");

    jobject packageInfo = env->CallObjectMethod(packageManager, getPackageInfo, packageName, 64);

    jclass packageInfoClass = env->GetObjectClass(packageInfo);
    jfieldID signaturesField = env->GetFieldID(packageInfoClass, "signatures", "[Landroid/content/pm/Signature;");
    jobjectArray signatures = (jobjectArray)env->GetObjectField(packageInfo, signaturesField);

    if (signatures == NULL || env->GetArrayLength(signatures) == 0) {
        return JNI_FALSE;
    }

    jobject signature = env->GetObjectArrayElement(signatures, 0);

    jclass signatureClass = env->GetObjectClass(signature);
    jmethodID toByteArray = env->GetMethodID(signatureClass, "toByteArray", "()[B");
    jbyteArray certBytes = (jbyteArray)env->CallObjectMethod(signature, toByteArray);

    jclass digestClass = env->FindClass("java/security/MessageDigest");
    jmethodID getInstance = env->GetStaticMethodID(digestClass, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jstring algorithm = env->NewStringUTF("SHA-256");
    jobject digestObj = env->CallStaticObjectMethod(digestClass, getInstance, algorithm);

    jmethodID digestMethod = env->GetMethodID(digestClass, "digest", "([B)[B");
    jbyteArray hashBytes = (jbyteArray)env->CallObjectMethod(digestObj, digestMethod, certBytes);

    jsize length = env->GetArrayLength(hashBytes);
    jbyte* buffer = env->GetByteArrayElements(hashBytes, NULL);

    char hexBuffer[3];
    std::string currentHash = "";
    for (int i = 0; i < length; i++) {
        snprintf(hexBuffer, sizeof(hexBuffer), "%02X", (unsigned char)buffer[i]);
        currentHash += hexBuffer;
    }
    env->ReleaseByteArrayElements(hashBytes, buffer, JNI_ABORT);
        
    if (currentHash == EXPECTED_SIGNATURE_HASH) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

// 2. Native License Validation
extern "C" JNIEXPORT jboolean JNICALL
Java_com_myanmar_warpvpn_NativeUtils_validateLicenseNative(
        JNIEnv* env,
        jobject /* this */,
        jlong expireDateMillis,
        jboolean isActivated) {

    jlong currentTimeMillis = (jlong)time(NULL) * 1000;

    if (isActivated && expireDateMillis > currentTimeMillis) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

// 3. Custom Backup API URL
extern "C" JNIEXPORT jstring JNICALL
Java_com_myanmar_warpvpn_NativeUtils_getCustomApiUrl(
        JNIEnv* env,
        jobject /* this */) {
    std::string customApi = "https://nyeinkokoaung.alwaysdata.net/wg/api.php";
    return env->NewStringUTF(customApi.c_str());
}

// 4. Cloudflare API Base 1
extern "C" JNIEXPORT jstring JNICALL
Java_com_myanmar_warpvpn_NativeUtils_getCfApiBase1(
        JNIEnv* env,
        jobject /* this */) {
    std::string cfApi1 = "https://api.cloudflareclient.com/v0i1909051800";
    return env->NewStringUTF(cfApi1.c_str());
}

// 5. Cloudflare API Base 2
extern "C" JNIEXPORT jstring JNICALL
Java_com_myanmar_warpvpn_NativeUtils_getCfApiBase2(
        JNIEnv* env,
        jobject /* this */) {
    std::string cfApi2 = "https://api.cloudflareclient.com/v0a2109151800";
    return env->NewStringUTF(cfApi2.c_str());
}

// 6. Cloudflare API Base 3
extern "C" JNIEXPORT jstring JNICALL
Java_com_myanmar_warpvpn_NativeUtils_getCfApiBase3(
        JNIEnv* env,
        jobject /* this */) {
    std::string cfApi3 = "https://api.cloudflareclient.com/v0a2409051800";
    return env->NewStringUTF(cfApi3.c_str());
}

// 7. AuthManager License Check API URL
extern "C" JNIEXPORT jstring JNICALL
Java_com_myanmar_warpvpn_AuthManager_getNativeWorkerApiUrl(
        JNIEnv* env,
        jobject /* this */) {

    std::string apiUrl = "https://api.vipplus69.com/api/check-license";

    return env->NewStringUTF(apiUrl.c_str());
}
