#include "pch.h"
#include "bootstrap.h"

#include <jni.h>
#include <tlhelp32.h>

#include <algorithm>
#include <cwchar>
#include <string>

namespace {

using GetCreatedJavaVMsFunction = jint(JNICALL*)(JavaVM**, jsize, jsize*);

void SetError(BootstrapParameters* parameters, BootstrapStatus status, const wchar_t* message) noexcept {
    parameters->status = status;
    if (message == nullptr) {
        parameters->error[0] = L'\0';
        return;
    }
    wcsncpy_s(parameters->error, message, _TRUNCATE);
}

std::wstring JavaStringToWide(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return L"Unknown Java exception";
    }
    const jchar* characters = env->GetStringChars(value, nullptr);
    if (characters == nullptr) {
        return L"Unable to read Java exception";
    }
    const jsize length = env->GetStringLength(value);
    std::wstring result(reinterpret_cast<const wchar_t*>(characters), static_cast<std::size_t>(length));
    env->ReleaseStringChars(value, characters);
    return result;
}

std::wstring ConsumeJavaException(JNIEnv* env) {
    jthrowable throwable = env->ExceptionOccurred();
    if (throwable == nullptr) {
        return L"JNI operation failed without a Java exception";
    }
    env->ExceptionClear();

    jclass throwableClass = env->GetObjectClass(throwable);
    jmethodID toString = throwableClass == nullptr
        ? nullptr
        : env->GetMethodID(throwableClass, "toString", "()Ljava/lang/String;");
    jstring description = toString == nullptr
        ? nullptr
        : static_cast<jstring>(env->CallObjectMethod(throwable, toString));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    std::wstring result = JavaStringToWide(env, description);
    if (description != nullptr) {
        env->DeleteLocalRef(description);
    }
    if (throwableClass != nullptr) {
        env->DeleteLocalRef(throwableClass);
    }
    env->DeleteLocalRef(throwable);
    return result;
}

bool CheckJava(JNIEnv* env, BootstrapParameters* parameters, const wchar_t* operation) {
    if (!env->ExceptionCheck()) {
        return true;
    }
    std::wstring message(operation);
    message.append(L": ").append(ConsumeJavaException(env));
    SetError(parameters, BootstrapStatus::JavaBootstrapFailed, message.c_str());
    return false;
}

using InitializePreloadedBridgeFunction = jint(JNICALL*)(JavaVM*, JNIEnv*, jobject);

InitializePreloadedBridgeFunction FindPreloadedBridge(bool* incompatibleAgentFound) {
    if (incompatibleAgentFound != nullptr) *incompatibleAgentFound = false;
    HANDLE snapshot = CreateToolhelp32Snapshot(
        TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, GetCurrentProcessId());
    if (snapshot == INVALID_HANDLE_VALUE) return nullptr;

    InitializePreloadedBridgeFunction result = nullptr;
    MODULEENTRY32W module{};
    module.dwSize = sizeof(module);
    if (Module32FirstW(snapshot, &module)) {
        do {
            FARPROC entry = GetProcAddress(module.hModule, "JVMRTDP_InitializeJavaBridge");
            if (entry != nullptr) {
                result = reinterpret_cast<InitializePreloadedBridgeFunction>(entry);
                break;
            }
            if (incompatibleAgentFound != nullptr
                && _wcsicmp(module.szModule, L"jvmrtdp-agent.dll") == 0) {
                *incompatibleAgentFound = true;
            }
        } while (Module32NextW(snapshot, &module));
    }
    CloseHandle(snapshot);
    return result;
}

bool BindPreloadedAgentBridge(JavaVM* vm, JNIEnv* env, jobject loader,
        BootstrapParameters* parameters) {
    bool incompatibleAgentFound = false;
    InitializePreloadedBridgeFunction initialize = FindPreloadedBridge(&incompatibleAgentFound);
    if (initialize == nullptr) {
        if (incompatibleAgentFound) {
            SetError(parameters, BootstrapStatus::JavaBootstrapFailed,
                L"A startup-loaded jvmrtdp-agent.dll is incompatible with this JVMRTDP JAR; "
                L"restart the target with the agent DLL from the same build");
            return false;
        }
        return true;
    }

    const jint result = initialize(vm, env, loader);
    if (result == JNI_OK) return true;
    if (env->ExceptionCheck()) {
        return CheckJava(env, parameters, L"Cannot bind the preloaded JVMTI agent to the attach class loader");
    }
    SetError(parameters, BootstrapStatus::JavaBootstrapFailed,
        L"The startup-loaded JVMTI agent rejected the attach class loader; use the agent DLL and JAR from the same build");
    return false;
}

bool StartJavaServer(JavaVM* vm, JNIEnv* env, BootstrapParameters* parameters) {
    jclass fileClass = env->FindClass("java/io/File");
    if (!CheckJava(env, parameters, L"Cannot resolve java.io.File") || fileClass == nullptr) {
        return false;
    }
    jmethodID fileConstructor = env->GetMethodID(fileClass, "<init>", "(Ljava/lang/String;)V");
    jmethodID fileToUri = env->GetMethodID(fileClass, "toURI", "()Ljava/net/URI;");
    if (!CheckJava(env, parameters, L"Cannot resolve java.io.File methods")
        || fileConstructor == nullptr || fileToUri == nullptr) {
        env->DeleteLocalRef(fileClass);
        return false;
    }

    const jsize jarPathLength = static_cast<jsize>(wcsnlen_s(parameters->jarPath, kBootstrapJarPathCapacity));
    jstring jarPath = env->NewString(reinterpret_cast<const jchar*>(parameters->jarPath), jarPathLength);
    jobject file = jarPath == nullptr ? nullptr : env->NewObject(fileClass, fileConstructor, jarPath);
    jobject uri = file == nullptr ? nullptr : env->CallObjectMethod(file, fileToUri);
    if (!CheckJava(env, parameters, L"Cannot create the agent JAR URI") || uri == nullptr) {
        if (uri != nullptr) env->DeleteLocalRef(uri);
        if (file != nullptr) env->DeleteLocalRef(file);
        if (jarPath != nullptr) env->DeleteLocalRef(jarPath);
        env->DeleteLocalRef(fileClass);
        return false;
    }

    jclass uriClass = env->FindClass("java/net/URI");
    jmethodID uriToUrl = uriClass == nullptr ? nullptr
        : env->GetMethodID(uriClass, "toURL", "()Ljava/net/URL;");
    jobject url = uriToUrl == nullptr ? nullptr : env->CallObjectMethod(uri, uriToUrl);
    jclass urlClass = env->FindClass("java/net/URL");
    jobjectArray urls = urlClass == nullptr ? nullptr : env->NewObjectArray(1, urlClass, url);
    if (!CheckJava(env, parameters, L"Cannot create the agent JAR URL") || urls == nullptr) {
        if (urls != nullptr) env->DeleteLocalRef(urls);
        if (urlClass != nullptr) env->DeleteLocalRef(urlClass);
        if (url != nullptr) env->DeleteLocalRef(url);
        if (uriClass != nullptr) env->DeleteLocalRef(uriClass);
        env->DeleteLocalRef(uri);
        env->DeleteLocalRef(file);
        env->DeleteLocalRef(jarPath);
        env->DeleteLocalRef(fileClass);
        return false;
    }

    jclass urlClassLoaderClass = env->FindClass("java/net/URLClassLoader");
    jmethodID loaderConstructor = urlClassLoaderClass == nullptr ? nullptr
        : env->GetMethodID(urlClassLoaderClass, "<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V");
    jobject loader = loaderConstructor == nullptr ? nullptr
        : env->NewObject(urlClassLoaderClass, loaderConstructor, urls, nullptr);
    if (!CheckJava(env, parameters, L"Cannot create the JVMRTDP class loader") || loader == nullptr) {
        return false;
    }
    if (!BindPreloadedAgentBridge(vm, env, loader, parameters)) {
        return false;
    }

    jmethodID loadClass = env->GetMethodID(
        urlClassLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring entryName = env->NewStringUTF("nhcm.jvmrtdp.agent.JVMRTDPAgent");
    jclass entryClass = loadClass == nullptr || entryName == nullptr
        ? nullptr
        : static_cast<jclass>(env->CallObjectMethod(loader, loadClass, entryName));
    if (!CheckJava(env, parameters, L"Cannot load the JVMRTDP entry class") || entryClass == nullptr) {
        return false;
    }

    jmethodID bootstrap = env->GetStaticMethodID(entryClass, "bootstrap", "(Ljava/lang/String;)V");
    if (!CheckJava(env, parameters, L"Cannot resolve JVMRTDPAgent.bootstrap") || bootstrap == nullptr) {
        return false;
    }
    jstring options = env->NewStringUTF(parameters->options);
    if (options != nullptr) {
        env->CallStaticVoidMethod(entryClass, bootstrap, options);
    }
    if (!CheckJava(env, parameters, L"JVMRTDP entry method failed") || options == nullptr) {
        return false;
    }

    env->DeleteLocalRef(options);
    env->DeleteLocalRef(entryClass);
    env->DeleteLocalRef(entryName);
    env->DeleteLocalRef(loader);
    env->DeleteLocalRef(urlClassLoaderClass);
    env->DeleteLocalRef(urls);
    env->DeleteLocalRef(urlClass);
    env->DeleteLocalRef(url);
    env->DeleteLocalRef(uriClass);
    env->DeleteLocalRef(uri);
    env->DeleteLocalRef(file);
    env->DeleteLocalRef(jarPath);
    env->DeleteLocalRef(fileClass);
    return true;
}

} // namespace

extern "C" __declspec(dllexport) DWORD WINAPI JVMRTDP_Bootstrap(
    BootstrapParameters* parameters) noexcept {
    if (parameters == nullptr || parameters->magic != kBootstrapMagic
        || parameters->version != kBootstrapVersion
        || parameters->jarPath[0] == L'\0' || parameters->options[0] == '\0') {
        if (parameters != nullptr) {
            SetError(parameters, BootstrapStatus::InvalidParameters, L"Invalid bootstrap parameters");
        }
        return static_cast<DWORD>(BootstrapStatus::InvalidParameters);
    }

    HMODULE jvmModule = GetModuleHandleW(L"jvm.dll");
    auto getCreatedJavaVMs = jvmModule == nullptr ? nullptr : reinterpret_cast<GetCreatedJavaVMsFunction>(
        GetProcAddress(jvmModule, "JNI_GetCreatedJavaVMs"));
    if (getCreatedJavaVMs == nullptr) {
        SetError(parameters, BootstrapStatus::JvmNotFound, L"Target process does not contain a JVM");
        return static_cast<DWORD>(BootstrapStatus::JvmNotFound);
    }

    JavaVM* javaVm = nullptr;
    jsize vmCount = 0;
    if (getCreatedJavaVMs(&javaVm, 1, &vmCount) != JNI_OK || vmCount == 0 || javaVm == nullptr) {
        SetError(parameters, BootstrapStatus::JvmNotFound, L"No created Java VM was found in the target process");
        return static_cast<DWORD>(BootstrapStatus::JvmNotFound);
    }

    JNIEnv* env = nullptr;
    bool attachedHere = false;
    jint envResult = javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
    if (envResult == JNI_EDETACHED) {
        envResult = javaVm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
        attachedHere = envResult == JNI_OK;
    }
    if (envResult != JNI_OK || env == nullptr) {
        SetError(parameters, BootstrapStatus::AttachThreadFailed, L"Cannot attach the injector thread to the target JVM");
        return static_cast<DWORD>(BootstrapStatus::AttachThreadFailed);
    }

    const bool started = StartJavaServer(javaVm, env, parameters);
    if (attachedHere) {
        javaVm->DetachCurrentThread();
    }
    if (!started) {
        return static_cast<DWORD>(parameters->status);
    }

    SetError(parameters, BootstrapStatus::Success, L"");
    return static_cast<DWORD>(BootstrapStatus::Success);
}
