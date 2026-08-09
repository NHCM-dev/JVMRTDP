#include "pch.h"
#include "jnisupport.h"

#include <jvmti.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <iomanip>
#include <limits>
#include <mutex>
#include <sstream>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {

constexpr std::string_view kBindingClass = "nhcm/jvmrtdp/agent/NativeAgent";
constexpr jint kAccStatic = 0x0008;
constexpr jint kAccNative = 0x0100;
constexpr jint kMethodFlagStatic = 1;
constexpr jint kMethodFlagNative = 2;
constexpr jint kMethodFlagPoppedByException = 4;
constexpr jint kMethodFlagReceiverAvailable = 8;

JavaVM* gJavaVm = nullptr;
jvmtiEnv* gJvmti = nullptr;
bool gCanRetransform = false;
std::atomic<bool> gClassFileHookEnabled{false};
std::atomic<bool> gLoadedAsJvmtiAgent{false};
std::atomic<bool> gCallbacksInstalled{false};
std::atomic<bool> gCapabilitiesRequested{false};
std::recursive_mutex gRetransformMutex;
std::recursive_mutex gEventMutex;
thread_local jclass tRequestedClass = nullptr;
thread_local std::vector<unsigned char>* tRequestedBytes = nullptr;
thread_local bool tInJavaCallback = false;
thread_local bool tCapturingMethodEvent = false;
thread_local int tTemporaryClassFileHookDepth = 0;
jclass gDispatcherClass = nullptr;
jmethodID gDispatchMethod = nullptr;
jmethodID gTransformMethod = nullptr;
jclass gObjectClass = nullptr;

struct BoxingType {
    const char* className;
    const char* descriptor;
    jclass klass = nullptr;
    jmethodID valueOf = nullptr;
};

BoxingType gBoxingTypes[] = {
    {"java/lang/Boolean", "(Z)Ljava/lang/Boolean;"},
    {"java/lang/Byte", "(B)Ljava/lang/Byte;"},
    {"java/lang/Character", "(C)Ljava/lang/Character;"},
    {"java/lang/Short", "(S)Ljava/lang/Short;"},
    {"java/lang/Integer", "(I)Ljava/lang/Integer;"},
    {"java/lang/Long", "(J)Ljava/lang/Long;"},
    {"java/lang/Float", "(F)Ljava/lang/Float;"},
    {"java/lang/Double", "(D)Ljava/lang/Double;"},
};

struct QueuedEvent {
    std::string name;
    std::string text;
    jmethodID method = nullptr;
    jmethodID relatedMethod = nullptr;
    jlocation location = 0;
    jlocation relatedLocation = 0;
    jlong value = 0;
};

std::mutex gQueueMutex;
std::condition_variable gQueueChanged;
std::deque<QueuedEvent> gEventQueue;
std::atomic<bool> gWorkerStopping{false};
// Raw by design: JNI_OnUnload joins/deletes it, while process shutdown may skip JNI_OnUnload.
// A global joinable std::thread would call std::terminate from the DLL's CRT teardown.
std::thread* gEventWorker = nullptr;
std::atomic<jlong> gNativeQueued{0};
std::atomic<jlong> gNativeDropped{0};

jobjectArray NewStringArray(JNIEnv* env, const std::vector<std::string>& values);
bool InitializeJavaBridge(JavaVM* vm, JNIEnv* env, bool preloadedAgent);

std::string JStringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* characters = env->GetStringUTFChars(value, nullptr);
    if (characters == nullptr) return {};
    std::string result(characters);
    env->ReleaseStringUTFChars(value, characters);
    return result;
}

void ThrowJava(JNIEnv* env, const char* className, const std::string& message) {
    if (env->ExceptionCheck()) return;
    jclass exceptionClass = env->FindClass(className);
    if (exceptionClass == nullptr) return;
    env->ThrowNew(exceptionClass, message.c_str());
    env->DeleteLocalRef(exceptionClass);
}

std::string ConsumeJavaException(JNIEnv* env) {
    jthrowable throwable = env->ExceptionOccurred();
    if (throwable == nullptr) return "unknown Java exception";
    env->ExceptionClear();
    std::string result = "target method threw an exception";
    jclass throwableClass = env->GetObjectClass(throwable);
    jmethodID toString = throwableClass == nullptr
        ? nullptr : env->GetMethodID(throwableClass, "toString", "()Ljava/lang/String;");
    jstring description = toString == nullptr
        ? nullptr : static_cast<jstring>(env->CallObjectMethod(throwable, toString));
    if (!env->ExceptionCheck() && description != nullptr) {
        result = JStringToUtf8(env, description);
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (description != nullptr) env->DeleteLocalRef(description);
    if (throwableClass != nullptr) env->DeleteLocalRef(throwableClass);
    env->DeleteLocalRef(throwable);
    return result;
}

bool CacheCallbackTypes(JNIEnv* env) {
    jclass objectClass = env->FindClass("java/lang/Object");
    if (objectClass == nullptr) return false;
    gObjectClass = static_cast<jclass>(env->NewGlobalRef(objectClass));
    env->DeleteLocalRef(objectClass);
    if (gObjectClass == nullptr) return false;
    for (BoxingType& boxing : gBoxingTypes) {
        jclass local = env->FindClass(boxing.className);
        if (local == nullptr) return false;
        boxing.klass = static_cast<jclass>(env->NewGlobalRef(local));
        boxing.valueOf = env->GetStaticMethodID(local, "valueOf", boxing.descriptor);
        env->DeleteLocalRef(local);
        if (boxing.klass == nullptr || boxing.valueOf == nullptr) return false;
    }
    return true;
}

void ReleaseCallbackTypes(JNIEnv* env) {
    if (gObjectClass != nullptr) env->DeleteGlobalRef(gObjectClass);
    gObjectClass = nullptr;
    for (BoxingType& boxing : gBoxingTypes) {
        if (boxing.klass != nullptr) env->DeleteGlobalRef(boxing.klass);
        boxing.klass = nullptr;
        boxing.valueOf = nullptr;
    }
}

std::string JvmtiErrorText(jvmtiError error) {
    if (gJvmti == nullptr) return "JVMTI is unavailable";
    char* name = nullptr;
    const jvmtiError nameError = gJvmti->GetErrorName(error, &name);
    std::string result = nameError == JVMTI_ERROR_NONE && name != nullptr
        ? name : "JVMTI error " + std::to_string(static_cast<int>(error));
    if (name != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    return result;
}

std::string ClassSignature(std::string className) {
    if (!className.empty() && className.front() == '[') {
        std::replace(className.begin(), className.end(), '.', '/');
        return className;
    }
    if (className.size() >= 2 && className.front() == 'L' && className.back() == ';') {
        std::replace(className.begin(), className.end(), '.', '/');
        return className;
    }
    std::replace(className.begin(), className.end(), '.', '/');
    return "L" + className + ";";
}

jclass FindLoadedClass(JNIEnv* env, const std::string& className) {
    if (gJvmti == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException", "JVMTI is unavailable");
        return nullptr;
    }
    jint count = 0;
    jclass* classes = nullptr;
    const jvmtiError error = gJvmti->GetLoadedClasses(&count, &classes);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJava(env, "java/lang/IllegalStateException", "GetLoadedClasses failed: " + JvmtiErrorText(error));
        return nullptr;
    }

    const std::string expected = ClassSignature(className);
    jclass result = nullptr;
    for (jint index = 0; index < count; ++index) {
        char* signature = nullptr;
        char* generic = nullptr;
        if (gJvmti->GetClassSignature(classes[index], &signature, &generic) == JVMTI_ERROR_NONE
            && signature != nullptr && expected == signature && result == nullptr) {
            result = static_cast<jclass>(env->NewLocalRef(classes[index]));
        }
        if (signature != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
        if (generic != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
        env->DeleteLocalRef(classes[index]);
    }
    if (classes != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    if (result == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Class is not loaded: " + className);
    }
    return result;
}

std::string Escape(std::string value) {
    std::string result;
    result.reserve(value.size());
    for (char character : value) {
        switch (character) {
        case '\\': result.append("\\\\"); break;
        case '\r': result.append("\\r"); break;
        case '\n': result.append("\\n"); break;
        case '\t': result.append("\\t"); break;
        default: result.push_back(character); break;
        }
    }
    return result;
}

std::string RenderObject(JNIEnv* env, jobject value) {
    if (value == nullptr) return "null";
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass != nullptr && env->IsInstanceOf(value, stringClass)) {
        const std::string result = "\"" + Escape(JStringToUtf8(env, static_cast<jstring>(value))) + "\"";
        env->DeleteLocalRef(stringClass);
        return result;
    }
    if (stringClass != nullptr) env->DeleteLocalRef(stringClass);

    jclass valueClass = env->FindClass("java/lang/String");
    jmethodID valueOf = valueClass == nullptr
        ? nullptr : env->GetStaticMethodID(valueClass, "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;");
    jstring rendered = valueOf == nullptr
        ? nullptr : static_cast<jstring>(env->CallStaticObjectMethod(valueClass, valueOf, value));
    if (env->ExceptionCheck()) {
        const std::string failure = ConsumeJavaException(env);
        if (valueClass != nullptr) env->DeleteLocalRef(valueClass);
        return "<toString failed: " + Escape(failure) + ">";
    }
    const std::string result = rendered == nullptr ? "null" : Escape(JStringToUtf8(env, rendered));
    if (rendered != nullptr) env->DeleteLocalRef(rendered);
    if (valueClass != nullptr) env->DeleteLocalRef(valueClass);
    return result;
}

std::string ReadStaticFieldValue(JNIEnv* env, jclass klass, jfieldID field, const char* signature) {
    std::ostringstream output;
    output.imbue(std::locale::classic());
    switch (signature[0]) {
    case 'Z': return env->GetStaticBooleanField(klass, field) == JNI_TRUE ? "true" : "false";
    case 'B': output << static_cast<int>(env->GetStaticByteField(klass, field)); break;
    case 'C': output << static_cast<unsigned int>(env->GetStaticCharField(klass, field)); break;
    case 'S': output << env->GetStaticShortField(klass, field); break;
    case 'I': output << env->GetStaticIntField(klass, field); break;
    case 'J': output << env->GetStaticLongField(klass, field); break;
    case 'F': output << std::setprecision(std::numeric_limits<float>::max_digits10)
                     << env->GetStaticFloatField(klass, field); break;
    case 'D': output << std::setprecision(std::numeric_limits<double>::max_digits10)
                     << env->GetStaticDoubleField(klass, field); break;
    case 'L':
    case '[': {
        jobject value = env->GetStaticObjectField(klass, field);
        const std::string result = RenderObject(env, value);
        if (value != nullptr) env->DeleteLocalRef(value);
        return result;
    }
    default: return "<unsupported descriptor>";
    }
    return output.str();
}

bool ParseType(const std::string& descriptor, std::size_t& position, bool allowVoid, std::string& type) {
    const std::size_t start = position;
    while (position < descriptor.size() && descriptor[position] == '[') ++position;
    if (position >= descriptor.size()) return false;
    const char kind = descriptor[position++];
    if (kind == 'L') {
        const std::size_t semicolon = descriptor.find(';', position);
        if (semicolon == std::string::npos) return false;
        position = semicolon + 1;
    } else if (std::string("ZBCSIJFD").find(kind) == std::string::npos
        && !(allowVoid && kind == 'V' && position == start + 1)) {
        return false;
    }
    type = descriptor.substr(start, position - start);
    return true;
}

bool ParseMethodDescriptor(
        const std::string& descriptor, std::vector<std::string>& parameters, std::string& returnType) {
    if (descriptor.empty() || descriptor.front() != '(') return false;
    std::size_t position = 1;
    while (position < descriptor.size() && descriptor[position] != ')') {
        std::string parameter;
        if (!ParseType(descriptor, position, false, parameter) || parameter == "V") return false;
        parameters.push_back(parameter);
    }
    if (position >= descriptor.size() || descriptor[position++] != ')'
        || !ParseType(descriptor, position, true, returnType)) return false;
    return position == descriptor.size();
}

template <typename T, typename Parser>
bool ParseNumber(const std::string& input, T& output, Parser parser) {
    try {
        std::size_t consumed = 0;
        output = static_cast<T>(parser(input, &consumed));
        return consumed == input.size();
    } catch (...) {
        return false;
    }
}

bool ParseArgument(JNIEnv* env, const std::string& descriptor, const std::string& text,
        jvalue& value, std::vector<jobject>& localReferences, std::string& failure) {
    long long integer = 0;
    unsigned long long unsignedInteger = 0;
    double decimal = 0;
    switch (descriptor[0]) {
    case 'Z':
        if (text == "true" || text == "1") value.z = JNI_TRUE;
        else if (text == "false" || text == "0") value.z = JNI_FALSE;
        else failure = "boolean arguments must be true, false, 1 or 0";
        break;
    case 'B':
        if (!ParseNumber<long long>(text, integer, [](const std::string& input, std::size_t* used) {
                return std::stoll(input, used, 0);
            }) || integer < (std::numeric_limits<jbyte>::min)()
                || integer > (std::numeric_limits<jbyte>::max)()) {
            failure = "invalid byte: " + text;
        } else value.b = static_cast<jbyte>(integer);
        break;
    case 'C':
        if (text.size() == 1) value.c = static_cast<unsigned char>(text[0]);
        else if (!ParseNumber<unsigned long long>(text, unsignedInteger,
                [](const std::string& input, std::size_t* used) { return std::stoull(input, used, 0); })
                || unsignedInteger > (std::numeric_limits<jchar>::max)()) failure = "invalid char: " + text;
        else value.c = static_cast<jchar>(unsignedInteger);
        break;
    case 'S':
        if (!ParseNumber<long long>(text, integer, [](const std::string& input, std::size_t* used) {
                return std::stoll(input, used, 0);
            }) || integer < (std::numeric_limits<jshort>::min)()
                || integer > (std::numeric_limits<jshort>::max)()) {
            failure = "invalid short: " + text;
        } else value.s = static_cast<jshort>(integer);
        break;
    case 'I':
        if (!ParseNumber<long long>(text, integer, [](const std::string& input, std::size_t* used) {
                return std::stoll(input, used, 0);
            }) || integer < (std::numeric_limits<jint>::min)()
                || integer > (std::numeric_limits<jint>::max)()) {
            failure = "invalid int: " + text;
        } else value.i = static_cast<jint>(integer);
        break;
    case 'J':
        if (!ParseNumber<long long>(text, integer, [](const std::string& input, std::size_t* used) {
                return std::stoll(input, used, 0);
            })) failure = "invalid long: " + text;
        else value.j = static_cast<jlong>(integer);
        break;
    case 'F':
    case 'D':
        if (!ParseNumber<double>(text, decimal, [](const std::string& input, std::size_t* used) {
                return std::stod(input, used);
            })) failure = "invalid floating-point value: " + text;
        else if (descriptor[0] == 'F') value.f = static_cast<jfloat>(decimal);
        else value.d = static_cast<jdouble>(decimal);
        break;
    case 'L':
    case '[':
        if (text == "null") {
            value.l = nullptr;
        } else if (descriptor == "Ljava/lang/String;") {
            value.l = env->NewStringUTF(text.c_str());
            if (value.l == nullptr) failure = "cannot allocate String argument";
            else localReferences.push_back(value.l);
        } else {
            failure = "only java.lang.String and null object arguments are supported";
        }
        break;
    default: failure = "unsupported parameter descriptor: " + descriptor; break;
    }
    return failure.empty();
}

std::string BinaryClassName(jclass klass) {
    if (klass == nullptr || gJvmti == nullptr) return {};
    char* signature = nullptr;
    char* generic = nullptr;
    std::string result;
    if (gJvmti->GetClassSignature(klass, &signature, &generic) == JVMTI_ERROR_NONE
        && signature != nullptr) {
        result = signature;
        if (result.size() >= 2 && result.front() == 'L' && result.back() == ';') {
            result = result.substr(1, result.size() - 2);
        }
        std::replace(result.begin(), result.end(), '/', '.');
    }
    if (signature != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
    if (generic != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
    return result;
}

struct MethodDetails {
    std::string className;
    std::string name;
    std::string descriptor;
};

MethodDetails DescribeMethod(JNIEnv* env, jmethodID method) {
    MethodDetails result;
    if (method == nullptr || gJvmti == nullptr) return result;
    char* name = nullptr;
    char* signature = nullptr;
    char* generic = nullptr;
    if (gJvmti->GetMethodName(method, &name, &signature, &generic) == JVMTI_ERROR_NONE) {
        if (name != nullptr) result.name = name;
        if (signature != nullptr) result.descriptor = signature;
    }
    jclass declaringClass = nullptr;
    if (gJvmti->GetMethodDeclaringClass(method, &declaringClass) == JVMTI_ERROR_NONE) {
        result.className = BinaryClassName(declaringClass);
    }
    if (declaringClass != nullptr && env != nullptr) env->DeleteLocalRef(declaringClass);
    if (name != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    if (signature != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
    if (generic != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
    return result;
}

jstring NewOptionalString(JNIEnv* env, const std::string& value) {
    return value.empty() ? nullptr : env->NewStringUTF(value.c_str());
}

void DispatchEvent(JNIEnv* env, const char* eventName, jthread thread, jclass eventClass,
        jmethodID method, jlocation location, jobject subject, jlong value,
        jmethodID relatedMethod = nullptr, jlocation relatedLocation = 0,
        const char* memberName = nullptr, const char* memberDescriptor = nullptr,
        jobject secondarySubject = nullptr, const char* text = nullptr,
        jobject receiver = nullptr, const char* receiverError = nullptr,
        jobjectArray methodArguments = nullptr, jobjectArray methodArgumentNames = nullptr,
        jintArray methodArgumentSlots = nullptr, jobjectArray methodArgumentErrors = nullptr,
        jint methodFlags = 0, jobject returnValue = nullptr) {
    if (env == nullptr || gDispatcherClass == nullptr || gDispatchMethod == nullptr || tInJavaCallback) return;
    const MethodDetails details = DescribeMethod(env, method);
    const std::string className = eventClass == nullptr ? details.className : BinaryClassName(eventClass);
    jstring javaEvent = env->NewStringUTF(eventName);
    jstring javaClass = NewOptionalString(env, className);
    jstring javaMethod = NewOptionalString(env, details.name);
    jstring javaDescriptor = NewOptionalString(env, details.descriptor);
    const MethodDetails related = DescribeMethod(env, relatedMethod);
    jstring javaRelatedClass = NewOptionalString(env, related.className);
    jstring javaRelatedMethod = NewOptionalString(env, related.name);
    jstring javaRelatedDescriptor = NewOptionalString(env, related.descriptor);
    jstring javaMemberName = memberName == nullptr ? nullptr : env->NewStringUTF(memberName);
    jstring javaMemberDescriptor = memberDescriptor == nullptr ? nullptr : env->NewStringUTF(memberDescriptor);
    jstring javaText = text == nullptr ? nullptr : env->NewStringUTF(text);
    jstring javaReceiverError = receiverError == nullptr || *receiverError == '\0'
        ? nullptr : env->NewStringUTF(receiverError);
    if (javaEvent != nullptr) {
        tInJavaCallback = true;
        env->CallStaticVoidMethod(gDispatcherClass, gDispatchMethod,
            javaEvent, thread, javaClass, javaMethod, javaDescriptor,
            static_cast<jlong>(location), subject, value,
            javaRelatedClass, javaRelatedMethod, javaRelatedDescriptor,
            static_cast<jlong>(relatedLocation), javaMemberName, javaMemberDescriptor,
            secondarySubject, javaText, receiver, javaReceiverError, methodArguments,
            methodArgumentNames, methodArgumentSlots, methodArgumentErrors, methodFlags, returnValue);
        tInJavaCallback = false;
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (javaEvent != nullptr) env->DeleteLocalRef(javaEvent);
    if (javaClass != nullptr) env->DeleteLocalRef(javaClass);
    if (javaMethod != nullptr) env->DeleteLocalRef(javaMethod);
    if (javaDescriptor != nullptr) env->DeleteLocalRef(javaDescriptor);
    if (javaRelatedClass != nullptr) env->DeleteLocalRef(javaRelatedClass);
    if (javaRelatedMethod != nullptr) env->DeleteLocalRef(javaRelatedMethod);
    if (javaRelatedDescriptor != nullptr) env->DeleteLocalRef(javaRelatedDescriptor);
    if (javaMemberName != nullptr) env->DeleteLocalRef(javaMemberName);
    if (javaMemberDescriptor != nullptr) env->DeleteLocalRef(javaMemberDescriptor);
    if (javaText != nullptr) env->DeleteLocalRef(javaText);
    if (javaReceiverError != nullptr) env->DeleteLocalRef(javaReceiverError);
}

void QueueEvent(QueuedEvent event) noexcept {
    try {
        {
            std::lock_guard<std::mutex> guard(gQueueMutex);
            if (gEventQueue.size() >= 65536) {
                gNativeDropped.fetch_add(1);
                return;
            }
            gEventQueue.push_back(std::move(event));
            gNativeQueued.fetch_add(1);
        }
        gQueueChanged.notify_one();
    } catch (...) {
        gNativeDropped.fetch_add(1);
    }
}

void QueueSimpleEvent(const char* name, jlong value = 0) noexcept {
    try {
        QueuedEvent event;
        event.name = name == nullptr ? "" : name;
        event.value = value;
        QueueEvent(std::move(event));
    } catch (...) {
        gNativeDropped.fetch_add(1);
    }
}

void EventWorkerMain() {
    JNIEnv* env = nullptr;
    if (gJavaVm == nullptr || gJavaVm->AttachCurrentThreadAsDaemon(
            reinterpret_cast<void**>(&env), nullptr) != JNI_OK || env == nullptr) return;
    while (true) {
        QueuedEvent event;
        {
            std::unique_lock<std::mutex> lock(gQueueMutex);
            gQueueChanged.wait(lock, [] { return gWorkerStopping.load() || !gEventQueue.empty(); });
            if (gWorkerStopping.load() && gEventQueue.empty()) break;
            event = std::move(gEventQueue.front());
            gEventQueue.pop_front();
        }
        DispatchEvent(env, event.name.c_str(), nullptr, nullptr, event.method, event.location,
            nullptr, event.value, event.relatedMethod, event.relatedLocation,
            nullptr, nullptr, nullptr, event.text.empty() ? nullptr : event.text.c_str());
    }
    gJavaVm->DetachCurrentThread();
}

jlong ValueBits(char kind, jvalue value, jobject* subject) {
    switch (kind) {
    case 'L':
    case '[': *subject = value.l; return 0;
    case 'Z': return value.z;
    case 'B': return value.b;
    case 'C': return value.c;
    case 'S': return value.s;
    case 'I': return value.i;
    case 'J': return value.j;
    case 'F': {
        jint bits = 0;
        std::memcpy(&bits, &value.f, sizeof(bits));
        return bits;
    }
    case 'D': {
        jlong bits = 0;
        std::memcpy(&bits, &value.d, sizeof(bits));
        return bits;
    }
    default: return 0;
    }
}

jlong ReturnValueBits(const MethodDetails& method, jvalue value, jobject* subject) {
    const std::size_t end = method.descriptor.rfind(')');
    const char kind = end == std::string::npos || end + 1 >= method.descriptor.size()
        ? 'V' : method.descriptor[end + 1];
    return ValueBits(kind, value, subject);
}

std::size_t BoxingIndex(char kind) {
    switch (kind) {
    case 'Z': return 0;
    case 'B': return 1;
    case 'C': return 2;
    case 'S': return 3;
    case 'I': return 4;
    case 'J': return 5;
    case 'F': return 6;
    case 'D': return 7;
    default: return static_cast<std::size_t>(-1);
    }
}

jobject BoxValue(JNIEnv* env, char kind, jvalue value) {
    if (kind == 'L' || kind == '[') return value.l;
    const std::size_t index = BoxingIndex(kind);
    if (index >= sizeof(gBoxingTypes) / sizeof(gBoxingTypes[0])) return nullptr;
    const BoxingType& boxing = gBoxingTypes[index];
    if (boxing.klass == nullptr || boxing.valueOf == nullptr) return nullptr;
    switch (kind) {
    case 'Z': return env->CallStaticObjectMethod(boxing.klass, boxing.valueOf, value.z);
    case 'B': return env->CallStaticObjectMethod(boxing.klass, boxing.valueOf, value.b);
    case 'C': return env->CallStaticObjectMethod(boxing.klass, boxing.valueOf, value.c);
    case 'S': return env->CallStaticObjectMethod(boxing.klass, boxing.valueOf, value.s);
    case 'I': return env->CallStaticObjectMethod(boxing.klass, boxing.valueOf, value.i);
    case 'J': return env->CallStaticObjectMethod(boxing.klass, boxing.valueOf, value.j);
    case 'F': return env->CallStaticObjectMethod(boxing.klass, boxing.valueOf, value.f);
    case 'D': return env->CallStaticObjectMethod(boxing.klass, boxing.valueOf, value.d);
    default: return nullptr;
    }
}

struct MethodCapture {
    jobject receiver = nullptr;
    std::string receiverError;
    std::vector<jobject> arguments;
    std::vector<std::string> argumentNames;
    std::vector<jint> argumentSlots;
    std::vector<std::string> argumentErrors;
    jint flags = 0;
};

void ReadParameterNames(jmethodID method, const std::vector<std::string>& descriptors,
        const std::vector<jint>& slots, std::vector<std::string>& names) {
    names.assign(descriptors.size(), {});
    jint count = 0;
    jvmtiLocalVariableEntry* table = nullptr;
    if (gJvmti->GetLocalVariableTable(method, &count, &table) != JVMTI_ERROR_NONE) return;
    std::vector<jlocation> bestStart(descriptors.size(), (std::numeric_limits<jlocation>::max)());
    for (jint tableIndex = 0; tableIndex < count; ++tableIndex) {
        const jvmtiLocalVariableEntry& entry = table[tableIndex];
        for (std::size_t argumentIndex = 0; argumentIndex < descriptors.size(); ++argumentIndex) {
            if (entry.slot == slots[argumentIndex] && entry.name != nullptr && entry.signature != nullptr
                && descriptors[argumentIndex] == entry.signature
                && entry.start_location < bestStart[argumentIndex]) {
                names[argumentIndex] = entry.name;
                bestStart[argumentIndex] = entry.start_location;
            }
        }
        if (entry.name != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(entry.name));
        if (entry.signature != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(entry.signature));
        if (entry.generic_signature != nullptr) {
            gJvmti->Deallocate(reinterpret_cast<unsigned char*>(entry.generic_signature));
        }
    }
    if (table != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(table));
}

MethodCapture CaptureMethodArguments(JNIEnv* env, jthread thread, jmethodID method,
        const MethodDetails& details) {
    MethodCapture capture;
    jint modifiers = 0;
    const jvmtiError modifiersError = gJvmti->GetMethodModifiers(method, &modifiers);
    const bool isStatic = modifiersError == JVMTI_ERROR_NONE && (modifiers & kAccStatic) != 0;
    const bool isNative = modifiersError == JVMTI_ERROR_NONE && (modifiers & kAccNative) != 0;
    if (isStatic) capture.flags |= kMethodFlagStatic;
    if (isNative) capture.flags |= kMethodFlagNative;

    if (isStatic) {
        capture.flags |= kMethodFlagReceiverAvailable;
    } else if (modifiersError != JVMTI_ERROR_NONE) {
        capture.receiverError = "GetMethodModifiers failed: " + JvmtiErrorText(modifiersError);
    } else {
        const jvmtiError receiverError = gJvmti->GetLocalInstance(thread, 0, &capture.receiver);
        if (receiverError == JVMTI_ERROR_NONE) capture.flags |= kMethodFlagReceiverAvailable;
        else capture.receiverError = "GetLocalInstance failed: " + JvmtiErrorText(receiverError);
    }

    std::vector<std::string> descriptors;
    std::string returnType;
    if (!ParseMethodDescriptor(details.descriptor, descriptors, returnType)) return capture;
    capture.arguments.resize(descriptors.size(), nullptr);
    capture.argumentSlots.resize(descriptors.size());
    capture.argumentErrors.resize(descriptors.size());
    jint slot = isStatic ? 0 : 1;
    for (std::size_t index = 0; index < descriptors.size(); ++index) {
        capture.argumentSlots[index] = slot;
        const char kind = descriptors[index].front();
        jvalue value{};
        jvmtiError error = JVMTI_ERROR_TYPE_MISMATCH;
        switch (kind) {
        case 'Z':
        case 'B':
        case 'C':
        case 'S':
        case 'I': {
            jint integer = 0;
            error = gJvmti->GetLocalInt(thread, 0, slot, &integer);
            if (kind == 'Z') value.z = static_cast<jboolean>(integer);
            else if (kind == 'B') value.b = static_cast<jbyte>(integer);
            else if (kind == 'C') value.c = static_cast<jchar>(integer);
            else if (kind == 'S') value.s = static_cast<jshort>(integer);
            else value.i = integer;
            break;
        }
        case 'J': error = gJvmti->GetLocalLong(thread, 0, slot, &value.j); break;
        case 'F': error = gJvmti->GetLocalFloat(thread, 0, slot, &value.f); break;
        case 'D': error = gJvmti->GetLocalDouble(thread, 0, slot, &value.d); break;
        case 'L':
        case '[': error = gJvmti->GetLocalObject(thread, 0, slot, &value.l); break;
        default: break;
        }
        if (error == JVMTI_ERROR_NONE) {
            capture.arguments[index] = BoxValue(env, kind, value);
            if (env->ExceptionCheck()) {
                capture.argumentErrors[index] = "boxing argument raised a Java exception";
                env->ExceptionClear();
                capture.arguments[index] = nullptr;
            } else if (kind != 'L' && kind != '[' && capture.arguments[index] == nullptr) {
                capture.argumentErrors[index] = "primitive boxing support is unavailable";
            }
        } else {
            capture.argumentErrors[index] = "local slot " + std::to_string(slot)
                + " could not be read: " + JvmtiErrorText(error);
        }
        slot += kind == 'J' || kind == 'D' ? 2 : 1;
    }
    ReadParameterNames(method, descriptors, capture.argumentSlots, capture.argumentNames);
    return capture;
}

jobjectArray NewNullableStringArray(JNIEnv* env, const std::vector<std::string>& values) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    for (jsize index = 0; result != nullptr && index < static_cast<jsize>(values.size()); ++index) {
        const std::string& text = values[static_cast<std::size_t>(index)];
        if (text.empty()) continue;
        jstring value = env->NewStringUTF(text.c_str());
        if (value != nullptr) {
            env->SetObjectArrayElement(result, index, value);
            env->DeleteLocalRef(value);
        }
    }
    env->DeleteLocalRef(stringClass);
    return result;
}

void DispatchMethodEvent(JNIEnv* env, const char* eventName, jthread thread, jmethodID method,
        bool poppedByException, jobject legacySubject, jlong legacyValue, jobject returnValue) {
    const MethodDetails details = DescribeMethod(env, method);
    MethodCapture capture = CaptureMethodArguments(env, thread, method, details);
    if (poppedByException) capture.flags |= kMethodFlagPoppedByException;
    jmethodID frameMethod = nullptr;
    jlocation frameLocation = 0;
    if (gJvmti->GetFrameLocation(thread, 0, &frameMethod, &frameLocation) != JVMTI_ERROR_NONE
        || frameMethod != method) {
        frameLocation = 0;
    }
    jobjectArray arguments = gObjectClass == nullptr ? nullptr : env->NewObjectArray(
        static_cast<jsize>(capture.arguments.size()), gObjectClass, nullptr);
    for (jsize index = 0; arguments != nullptr
            && index < static_cast<jsize>(capture.arguments.size()); ++index) {
        env->SetObjectArrayElement(arguments, index, capture.arguments[static_cast<std::size_t>(index)]);
    }
    jobjectArray names = NewNullableStringArray(env, capture.argumentNames);
    jobjectArray errors = NewNullableStringArray(env, capture.argumentErrors);
    jintArray slots = env->NewIntArray(static_cast<jsize>(capture.argumentSlots.size()));
    if (slots != nullptr && !capture.argumentSlots.empty()) {
        env->SetIntArrayRegion(slots, 0, static_cast<jsize>(capture.argumentSlots.size()),
            capture.argumentSlots.data());
    }
    DispatchEvent(env, eventName, thread, nullptr, method,
        poppedByException ? static_cast<jlocation>(-1) : frameLocation, legacySubject, legacyValue,
        nullptr, 0, nullptr, nullptr, nullptr, nullptr, capture.receiver,
        capture.receiverError.empty() ? nullptr : capture.receiverError.c_str(),
        arguments, names, slots, errors, capture.flags, returnValue);
    if (arguments != nullptr) env->DeleteLocalRef(arguments);
    if (names != nullptr) env->DeleteLocalRef(names);
    if (errors != nullptr) env->DeleteLocalRef(errors);
    if (slots != nullptr) env->DeleteLocalRef(slots);
    if (capture.receiver != nullptr) env->DeleteLocalRef(capture.receiver);
    for (jobject argument : capture.arguments) {
        if (argument != nullptr) env->DeleteLocalRef(argument);
    }
}

struct FieldDetails {
    std::string name;
    std::string descriptor;
};

FieldDetails DescribeField(jclass klass, jfieldID field) {
    FieldDetails result;
    if (klass == nullptr || field == nullptr || gJvmti == nullptr) return result;
    char* name = nullptr;
    char* signature = nullptr;
    char* generic = nullptr;
    if (gJvmti->GetFieldName(klass, field, &name, &signature, &generic) == JVMTI_ERROR_NONE) {
        if (name != nullptr) result.name = name;
        if (signature != nullptr) result.descriptor = signature;
    }
    if (name != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    if (signature != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
    if (generic != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
    return result;
}

void JNICALL VmInit(jvmtiEnv*, JNIEnv* env, jthread thread) {
    if (gLoadedAsJvmtiAgent.load() && gDispatcherClass == nullptr) {
        InitializeJavaBridge(gJavaVm, env, true);
    }
    DispatchEvent(env, "vm_init", thread, nullptr, nullptr, 0, nullptr, 0);
}

void JNICALL VmStart(jvmtiEnv*, JNIEnv* env) {
    DispatchEvent(env, "vm_start", nullptr, nullptr, nullptr, 0, nullptr, 0);
}

void JNICALL VmDeath(jvmtiEnv*, JNIEnv* env) {
    DispatchEvent(env, "vm_death", nullptr, nullptr, nullptr, 0, nullptr, 0);
}

void JNICALL ThreadStart(jvmtiEnv*, JNIEnv* env, jthread thread) {
    DispatchEvent(env, "thread_start", thread, nullptr, nullptr, 0, nullptr, 0);
}

void JNICALL ThreadEnd(jvmtiEnv*, JNIEnv* env, jthread thread) {
    DispatchEvent(env, "thread_end", thread, nullptr, nullptr, 0, nullptr, 0);
}

void JNICALL ClassLoad(jvmtiEnv*, JNIEnv* env, jthread thread, jclass klass) {
    DispatchEvent(env, "class_load", thread, klass, nullptr, 0, klass, 0);
}

void JNICALL ClassPrepare(jvmtiEnv*, JNIEnv* env, jthread thread, jclass klass) {
    DispatchEvent(env, "class_prepare", thread, klass, nullptr, 0, klass, 0);
}

void JNICALL SingleStep(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method, jlocation location) {
    DispatchEvent(env, "single_step", thread, nullptr, method, location, nullptr, 0);
}

void JNICALL FramePop(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method,
        jboolean wasPoppedByException) {
    DispatchEvent(env, "frame_pop", thread, nullptr, method, 0, nullptr,
        wasPoppedByException == JNI_TRUE ? 1 : 0);
}

void JNICALL Breakpoint(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method, jlocation location) {
    DispatchEvent(env, "breakpoint", thread, nullptr, method, location, nullptr, 0);
}

void JNICALL FieldAccess(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method,
        jlocation location, jclass fieldClass, jobject object, jfieldID field) {
    const FieldDetails details = DescribeField(fieldClass, field);
    DispatchEvent(env, "field_access", thread, fieldClass, method, location, object, 0,
        nullptr, 0, details.name.c_str(), details.descriptor.c_str());
}

void JNICALL FieldModification(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method,
        jlocation location, jclass fieldClass, jobject object, jfieldID field,
        char signatureType, jvalue newValue) {
    const FieldDetails details = DescribeField(fieldClass, field);
    jobject newObject = nullptr;
    const jlong bits = ValueBits(signatureType, newValue, &newObject);
    DispatchEvent(env, "field_modification", thread, fieldClass, method, location, object, bits,
        nullptr, 0, details.name.c_str(), details.descriptor.c_str(), newObject);
}

void JNICALL MethodEntry(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method) {
    if (tInJavaCallback || tCapturingMethodEvent) return;
    tCapturingMethodEvent = true;
    try {
        DispatchMethodEvent(env, "method_entry", thread, method, false, nullptr, 0, nullptr);
    } catch (...) {
        gNativeDropped.fetch_add(1);
    }
    tCapturingMethodEvent = false;
}

void JNICALL MethodExit(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method,
        jboolean poppedByException, jvalue returnValue) {
    if (tInJavaCallback || tCapturingMethodEvent) return;
    tCapturingMethodEvent = true;
    try {
    const MethodDetails details = DescribeMethod(env, method);
    jobject subject = nullptr;
    const jlong bits = poppedByException == JNI_TRUE ? 0 : ReturnValueBits(details, returnValue, &subject);
    const std::size_t end = details.descriptor.rfind(')');
    const char returnKind = end == std::string::npos || end + 1 >= details.descriptor.size()
        ? 'V' : details.descriptor[end + 1];
    jobject boxedReturn = poppedByException == JNI_TRUE || returnKind == 'V'
        ? nullptr : BoxValue(env, returnKind, returnValue);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        boxedReturn = nullptr;
    }
    DispatchMethodEvent(env, "method_exit", thread, method, poppedByException == JNI_TRUE,
        subject, bits, boxedReturn);
    if (boxedReturn != nullptr && returnKind != 'L' && returnKind != '[') {
        env->DeleteLocalRef(boxedReturn);
    }
    } catch (...) {
        gNativeDropped.fetch_add(1);
    }
    tCapturingMethodEvent = false;
}

void JNICALL Exception(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method,
        jlocation location, jobject exception, jmethodID catchMethod, jlocation catchLocation) {
    DispatchEvent(env, "exception", thread, nullptr, method, location, exception, 0,
        catchMethod, catchLocation);
}

void JNICALL ExceptionCatch(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method,
        jlocation location, jobject exception) {
    DispatchEvent(env, "exception_catch", thread, nullptr, method, location, exception, 0);
}

void JNICALL MonitorContendedEnter(jvmtiEnv*, JNIEnv* env, jthread thread, jobject object) {
    DispatchEvent(env, "monitor_contended_enter", thread, nullptr, nullptr, 0, object, 0);
}

void JNICALL MonitorContendedEntered(jvmtiEnv*, JNIEnv* env, jthread thread, jobject object) {
    DispatchEvent(env, "monitor_contended_entered", thread, nullptr, nullptr, 0, object, 0);
}

void JNICALL MonitorWait(jvmtiEnv*, JNIEnv* env, jthread thread, jobject object, jlong timeout) {
    DispatchEvent(env, "monitor_wait", thread, nullptr, nullptr, 0, object, timeout);
}

void JNICALL MonitorWaited(jvmtiEnv*, JNIEnv* env, jthread thread, jobject object, jboolean timedOut) {
    DispatchEvent(env, "monitor_waited", thread, nullptr, nullptr, 0, object, timedOut == JNI_TRUE ? 1 : 0);
}

void JNICALL VmObjectAlloc(jvmtiEnv*, JNIEnv* env, jthread thread, jobject object, jclass klass, jlong size) {
    DispatchEvent(env, "vm_object_alloc", thread, klass, nullptr, 0, object, size);
}

void JNICALL NativeMethodBind(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method,
        void* address, void**) {
    DispatchEvent(env, "native_method_bind", thread, nullptr, method,
        static_cast<jlocation>(reinterpret_cast<std::uintptr_t>(address)), nullptr, 0);
}

void JNICALL CompiledMethodLoad(jvmtiEnv*, jmethodID method, jint codeSize, const void* codeAddress,
        jint mapLength, const jvmtiAddrLocationMap*, const void*) {
    try {
        QueuedEvent event;
        event.name = "compiled_method_load";
        event.method = method;
        event.location = static_cast<jlocation>(reinterpret_cast<std::uintptr_t>(codeAddress));
        event.relatedLocation = mapLength;
        event.value = codeSize;
        QueueEvent(std::move(event));
    } catch (...) { gNativeDropped.fetch_add(1); }
}

void JNICALL CompiledMethodUnload(jvmtiEnv*, jmethodID method, const void* codeAddress) {
    try {
        QueuedEvent event;
        event.name = "compiled_method_unload";
        event.method = method;
        event.location = static_cast<jlocation>(reinterpret_cast<std::uintptr_t>(codeAddress));
        QueueEvent(std::move(event));
    } catch (...) { gNativeDropped.fetch_add(1); }
}

void JNICALL DynamicCodeGenerated(jvmtiEnv*, const char* name, const void* address, jint length) {
    try {
        QueuedEvent event;
        event.name = "dynamic_code_generated";
        if (name != nullptr) event.text = name;
        event.location = static_cast<jlocation>(reinterpret_cast<std::uintptr_t>(address));
        event.value = length;
        QueueEvent(std::move(event));
    } catch (...) { gNativeDropped.fetch_add(1); }
}

void JNICALL DataDumpRequest(jvmtiEnv*) {
    QueueSimpleEvent("data_dump_request");
}

void JNICALL ResourceExhausted(jvmtiEnv*, JNIEnv*, jint flags, const void*, const char* description) {
    try {
        QueuedEvent event;
        event.name = "resource_exhausted";
        if (description != nullptr) event.text = description;
        event.value = flags;
        QueueEvent(std::move(event));
    } catch (...) { gNativeDropped.fetch_add(1); }
}

void JNICALL GarbageCollectionStart(jvmtiEnv*) {
    QueueSimpleEvent("garbage_collection_start");
}

void JNICALL GarbageCollectionFinish(jvmtiEnv*) {
    QueueSimpleEvent("garbage_collection_finish");
}

void JNICALL ObjectFree(jvmtiEnv*, jlong tag) {
    QueueSimpleEvent("object_free", tag);
}

void JNICALL ClassFileLoadHook(jvmtiEnv* jvmti, JNIEnv* env, jclass classBeingRedefined,
        jobject loader, const char* name, jobject protectionDomain, jint classDataLength,
        const unsigned char* classData,
        jint* newClassDataLength, unsigned char** newClassData) {
    if (tRequestedClass != nullptr && tRequestedBytes != nullptr && classBeingRedefined != nullptr
        && env->IsSameObject(tRequestedClass, classBeingRedefined)) {
        tRequestedBytes->assign(classData, classData + classDataLength);
    }
    if (gDispatcherClass == nullptr || gTransformMethod == nullptr || classDataLength <= 0 || tInJavaCallback) return;
    std::string binaryName = name == nullptr ? BinaryClassName(classBeingRedefined) : name;
    std::replace(binaryName.begin(), binaryName.end(), '/', '.');
    jstring javaName = NewOptionalString(env, binaryName);
    jbyteArray original = env->NewByteArray(classDataLength);
    if (original != nullptr) {
        env->SetByteArrayRegion(original, 0, classDataLength, reinterpret_cast<const jbyte*>(classData));
    }
    tInJavaCallback = true;
    jbyteArray replacement = original == nullptr ? nullptr : static_cast<jbyteArray>(env->CallStaticObjectMethod(
        gDispatcherClass, gTransformMethod, loader, javaName, classBeingRedefined, protectionDomain, original));
    tInJavaCallback = false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        replacement = nullptr;
    }
    if (replacement != nullptr) {
        const jsize replacementLength = env->GetArrayLength(replacement);
        unsigned char* allocated = nullptr;
        if (replacementLength > 0 && jvmti->Allocate(replacementLength, &allocated) == JVMTI_ERROR_NONE) {
            env->GetByteArrayRegion(replacement, 0, replacementLength, reinterpret_cast<jbyte*>(allocated));
            if (!env->ExceptionCheck()) {
                *newClassDataLength = replacementLength;
                *newClassData = allocated;
                allocated = nullptr;
            } else {
                env->ExceptionClear();
            }
        }
        if (allocated != nullptr) jvmti->Deallocate(allocated);
        env->DeleteLocalRef(replacement);
    }
    if (original != nullptr) env->DeleteLocalRef(original);
    if (javaName != nullptr) env->DeleteLocalRef(javaName);
}

jstring JNICALL NativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("jvmrtdp-agent/0.2");
}

jint JNICALL NativeJvmtiVersion(JNIEnv*, jclass) {
    if (gJvmti == nullptr) return 0;
    jint version = 0;
    return gJvmti->GetVersionNumber(&version) == JVMTI_ERROR_NONE ? version : 0;
}

jbyteArray JNICALL NativeGetClassBytes(JNIEnv* env, jclass, jstring classNameValue) {
    const std::string className = JStringToUtf8(env, classNameValue);
    if (className.empty()) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Class name must not be empty");
        return nullptr;
    }
    if (!gCanRetransform) {
        ThrowJava(env, "java/lang/UnsupportedOperationException",
            "This JVM did not grant can_retransform_classes to the injected JVMTI environment");
        return nullptr;
    }
    jclass klass = FindLoadedClass(env, className);
    if (klass == nullptr) return nullptr;

    jboolean modifiable = JNI_FALSE;
    jvmtiError error = gJvmti->IsModifiableClass(klass, &modifiable);
    if (error != JVMTI_ERROR_NONE || modifiable != JNI_TRUE) {
        env->DeleteLocalRef(klass);
        ThrowJava(env, "java/lang/UnsupportedOperationException", "Class cannot be retransformed: " + className);
        return nullptr;
    }

    std::vector<unsigned char> bytes;
    {
        std::lock_guard<std::recursive_mutex> guard(gRetransformMutex);
        tRequestedClass = klass;
        tRequestedBytes = &bytes;
        std::lock_guard<std::recursive_mutex> eventGuard(gEventMutex);
        const bool ownsTemporaryEnable = !gClassFileHookEnabled.load()
            && tTemporaryClassFileHookDepth == 0;
        error = ownsTemporaryEnable
            ? gJvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr)
            : JVMTI_ERROR_NONE;
        if (error == JVMTI_ERROR_NONE) {
            ++tTemporaryClassFileHookDepth;
            error = gJvmti->RetransformClasses(1, &klass);
            --tTemporaryClassFileHookDepth;
        }
        const bool shouldDisable = ownsTemporaryEnable && !gClassFileHookEnabled.load();
        const jvmtiError disableError = shouldDisable ? gJvmti->SetEventNotificationMode(
            JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr) : JVMTI_ERROR_NONE;
        tRequestedClass = nullptr;
        tRequestedBytes = nullptr;
        if (error == JVMTI_ERROR_NONE) error = disableError;
    }
    env->DeleteLocalRef(klass);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJava(env, "java/lang/IllegalStateException", "RetransformClasses failed: " + JvmtiErrorText(error));
        return nullptr;
    }
    if (bytes.empty()) {
        ThrowJava(env, "java/lang/IllegalStateException", "JVMTI did not provide bytes for " + className);
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
            reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return result;
}

jstring JNICALL NativeReadStaticFields(JNIEnv* env, jclass, jstring classNameValue) {
    const std::string className = JStringToUtf8(env, classNameValue);
    jclass klass = FindLoadedClass(env, className);
    if (klass == nullptr) return nullptr;
    jint fieldCount = 0;
    jfieldID* fields = nullptr;
    const jvmtiError fieldsError = gJvmti->GetClassFields(klass, &fieldCount, &fields);
    if (fieldsError != JVMTI_ERROR_NONE) {
        env->DeleteLocalRef(klass);
        ThrowJava(env, "java/lang/IllegalStateException", "GetClassFields failed: " + JvmtiErrorText(fieldsError));
        return nullptr;
    }

    std::ostringstream output;
    bool found = false;
    for (jint index = 0; index < fieldCount; ++index) {
        jint modifiers = 0;
        if (gJvmti->GetFieldModifiers(klass, fields[index], &modifiers) != JVMTI_ERROR_NONE
            || (modifiers & kAccStatic) == 0) continue;
        char* name = nullptr;
        char* signature = nullptr;
        char* generic = nullptr;
        if (gJvmti->GetFieldName(klass, fields[index], &name, &signature, &generic) == JVMTI_ERROR_NONE
            && name != nullptr && signature != nullptr) {
            const std::string value = ReadStaticFieldValue(env, klass, fields[index], signature);
            if (env->ExceptionCheck()) {
                const std::string failure = ConsumeJavaException(env);
                ThrowJava(env, "java/lang/IllegalStateException", failure);
            } else {
                if (found) output << '\n';
                output << name << '\t' << signature << '\t' << value;
                found = true;
            }
        }
        if (name != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
        if (signature != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
        if (generic != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
        if (env->ExceptionCheck()) break;
    }
    if (fields != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(fields));
    env->DeleteLocalRef(klass);
    if (env->ExceptionCheck()) return nullptr;
    return env->NewStringUTF(found ? output.str().c_str() : "<no declared static fields>");
}

jstring JNICALL NativeReadStaticField(
        JNIEnv* env, jclass, jstring classNameValue, jstring fieldNameValue) {
    const std::string className = JStringToUtf8(env, classNameValue);
    const std::string fieldName = JStringToUtf8(env, fieldNameValue);
    jclass klass = FindLoadedClass(env, className);
    if (klass == nullptr) return nullptr;
    jint fieldCount = 0;
    jfieldID* fields = nullptr;
    const jvmtiError fieldsError = gJvmti->GetClassFields(klass, &fieldCount, &fields);
    if (fieldsError != JVMTI_ERROR_NONE) {
        env->DeleteLocalRef(klass);
        ThrowJava(env, "java/lang/IllegalStateException", "GetClassFields failed: " + JvmtiErrorText(fieldsError));
        return nullptr;
    }

    std::string result;
    bool found = false;
    for (jint index = 0; index < fieldCount && !found; ++index) {
        jint modifiers = 0;
        char* name = nullptr;
        char* signature = nullptr;
        char* generic = nullptr;
        if (gJvmti->GetFieldModifiers(klass, fields[index], &modifiers) == JVMTI_ERROR_NONE
            && (modifiers & kAccStatic) != 0
            && gJvmti->GetFieldName(klass, fields[index], &name, &signature, &generic) == JVMTI_ERROR_NONE
            && name != nullptr && signature != nullptr && fieldName == name) {
            const std::string value = ReadStaticFieldValue(env, klass, fields[index], signature);
            if (env->ExceptionCheck()) {
                const std::string failure = ConsumeJavaException(env);
                ThrowJava(env, "java/lang/IllegalStateException", failure);
            } else {
                result = std::string(signature) + '\t' + value;
            }
            found = true;
        }
        if (name != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
        if (signature != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
        if (generic != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
    }
    if (fields != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(fields));
    env->DeleteLocalRef(klass);
    if (env->ExceptionCheck()) return nullptr;
    if (!found) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "Declared static field was not found: " + className + "." + fieldName);
        return nullptr;
    }
    return env->NewStringUTF(result.c_str());
}

jstring JNICALL NativeCallStaticMethod(JNIEnv* env, jclass, jstring classNameValue,
        jstring methodNameValue, jstring descriptorValue, jobjectArray argumentValues) {
    const std::string className = JStringToUtf8(env, classNameValue);
    const std::string methodName = JStringToUtf8(env, methodNameValue);
    const std::string descriptor = JStringToUtf8(env, descriptorValue);
    std::vector<std::string> parameterTypes;
    std::string returnType;
    if (!ParseMethodDescriptor(descriptor, parameterTypes, returnType)) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Invalid JNI method descriptor: " + descriptor);
        return nullptr;
    }
    const jsize argumentCount = argumentValues == nullptr ? 0 : env->GetArrayLength(argumentValues);
    if (argumentCount != static_cast<jsize>(parameterTypes.size())) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Descriptor expects "
            + std::to_string(parameterTypes.size()) + " arguments, received " + std::to_string(argumentCount));
        return nullptr;
    }

    jclass klass = FindLoadedClass(env, className);
    if (klass == nullptr) return nullptr;
    jmethodID method = env->GetStaticMethodID(klass, methodName.c_str(), descriptor.c_str());
    if (method == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(klass);
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "Static method was not found: " + className + "." + methodName + descriptor);
        return nullptr;
    }

    std::vector<jvalue> arguments(parameterTypes.size());
    std::vector<jobject> localReferences;
    for (jsize index = 0; index < argumentCount; ++index) {
        jstring argument = static_cast<jstring>(env->GetObjectArrayElement(argumentValues, index));
        const std::string text = JStringToUtf8(env, argument);
        std::string failure;
        const bool parsed = ParseArgument(
            env, parameterTypes[static_cast<std::size_t>(index)], text,
            arguments[static_cast<std::size_t>(index)], localReferences, failure);
        if (argument != nullptr) env->DeleteLocalRef(argument);
        if (!parsed) {
            for (jobject reference : localReferences) env->DeleteLocalRef(reference);
            env->DeleteLocalRef(klass);
            ThrowJava(env, "java/lang/IllegalArgumentException",
                "Argument " + std::to_string(index) + ": " + failure);
            return nullptr;
        }
    }

    std::string result;
    const jvalue* values = arguments.empty() ? nullptr : arguments.data();
    switch (returnType[0]) {
    case 'V': env->CallStaticVoidMethodA(klass, method, values); result = "void"; break;
    case 'Z': result = env->CallStaticBooleanMethodA(klass, method, values) == JNI_TRUE ? "true" : "false"; break;
    case 'B': result = std::to_string(static_cast<int>(env->CallStaticByteMethodA(klass, method, values))); break;
    case 'C': result = std::to_string(static_cast<unsigned int>(env->CallStaticCharMethodA(klass, method, values))); break;
    case 'S': result = std::to_string(env->CallStaticShortMethodA(klass, method, values)); break;
    case 'I': result = std::to_string(env->CallStaticIntMethodA(klass, method, values)); break;
    case 'J': result = std::to_string(env->CallStaticLongMethodA(klass, method, values)); break;
    case 'F': {
        std::ostringstream output;
        output << std::setprecision(std::numeric_limits<float>::max_digits10)
               << env->CallStaticFloatMethodA(klass, method, values);
        result = output.str();
        break;
    }
    case 'D': {
        std::ostringstream output;
        output << std::setprecision(std::numeric_limits<double>::max_digits10)
               << env->CallStaticDoubleMethodA(klass, method, values);
        result = output.str();
        break;
    }
    case 'L':
    case '[': {
        jobject object = env->CallStaticObjectMethodA(klass, method, values);
        if (!env->ExceptionCheck()) result = RenderObject(env, object);
        if (object != nullptr) env->DeleteLocalRef(object);
        break;
    }
    default: break;
    }
    for (jobject reference : localReferences) env->DeleteLocalRef(reference);
    env->DeleteLocalRef(klass);
    if (env->ExceptionCheck()) {
        const std::string failure = ConsumeJavaException(env);
        ThrowJava(env, "java/lang/IllegalStateException", failure);
        return nullptr;
    }
    return env->NewStringUTF(result.c_str());
}

jclass JNICALL NativeFindLoadedClass(JNIEnv* env, jclass, jstring classNameValue) {
    const std::string className = JStringToUtf8(env, classNameValue);
    if (className.empty()) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Class name must not be empty");
        return nullptr;
    }
    return FindLoadedClass(env, className);
}

jobjectArray JNICALL NativeListLoadedClassNames(JNIEnv* env, jclass) {
    jint count = 0;
    jclass* classes = nullptr;
    const jvmtiError error = gJvmti->GetLoadedClasses(&count, &classes);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJava(env, "java/lang/IllegalStateException", "GetLoadedClasses failed: " + JvmtiErrorText(error));
        return nullptr;
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = stringClass == nullptr ? nullptr : env->NewObjectArray(count, stringClass, nullptr);
    for (jint index = 0; result != nullptr && index < count; ++index) {
        char* signature = nullptr;
        char* generic = nullptr;
        if (gJvmti->GetClassSignature(classes[index], &signature, &generic) == JVMTI_ERROR_NONE
            && signature != nullptr) {
            std::string name(signature);
            if (name.size() >= 2 && name.front() == 'L' && name.back() == ';') {
                name = name.substr(1, name.size() - 2);
            }
            std::replace(name.begin(), name.end(), '/', '.');
            jstring javaName = env->NewStringUTF(name.c_str());
            if (javaName != nullptr) {
                env->SetObjectArrayElement(result, index, javaName);
                env->DeleteLocalRef(javaName);
            }
        }
        if (signature != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
        if (generic != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
        env->DeleteLocalRef(classes[index]);
    }
    if (classes != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    if (stringClass != nullptr) env->DeleteLocalRef(stringClass);
    return result;
}

jobjectArray JNICALL NativeListLoadedClasses(JNIEnv* env, jclass) {
    jint count = 0;
    jclass* classes = nullptr;
    const jvmtiError error = gJvmti->GetLoadedClasses(&count, &classes);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJava(env, "java/lang/IllegalStateException", "GetLoadedClasses failed: " + JvmtiErrorText(error));
        return nullptr;
    }
    jclass classClass = env->FindClass("java/lang/Class");
    jobjectArray result = classClass == nullptr ? nullptr : env->NewObjectArray(count, classClass, nullptr);
    for (jint index = 0; result != nullptr && index < count; ++index) {
        env->SetObjectArrayElement(result, index, classes[index]);
        env->DeleteLocalRef(classes[index]);
    }
    if (classes != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    if (classClass != nullptr) env->DeleteLocalRef(classClass);
    return result;
}

void ThrowJvmti(JNIEnv* env, const char* operation, jvmtiError error) {
    if (error != JVMTI_ERROR_NONE) {
        ThrowJava(env, "java/lang/IllegalStateException",
            std::string(operation) + " failed: " + JvmtiErrorText(error));
    }
}

jobjectArray NewStringArray(JNIEnv* env, const std::vector<std::string>& values) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    for (jsize index = 0; result != nullptr && index < static_cast<jsize>(values.size()); ++index) {
        jstring value = env->NewStringUTF(values[static_cast<std::size_t>(index)].c_str());
        if (value != nullptr) {
            env->SetObjectArrayElement(result, index, value);
            env->DeleteLocalRef(value);
        }
    }
    env->DeleteLocalRef(stringClass);
    return result;
}

jclass JNICALL NativeDefineClass(JNIEnv* env, jclass, jstring classNameValue,
        jbyteArray classBytes, jobject loader) {
    std::string className = JStringToUtf8(env, classNameValue);
    std::replace(className.begin(), className.end(), '.', '/');
    if (className.empty() || classBytes == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Class name and bytes are required");
        return nullptr;
    }
    const jsize length = env->GetArrayLength(classBytes);
    if (length < 4) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Invalid JVM class bytes");
        return nullptr;
    }
    jbyte* bytes = env->GetByteArrayElements(classBytes, nullptr);
    if (bytes == nullptr) return nullptr;
    jclass result = env->DefineClass(className.c_str(), loader, bytes, length);
    env->ReleaseByteArrayElements(classBytes, bytes, JNI_ABORT);
    return result;
}

void JNICALL NativeAddToClassLoaderSearch(JNIEnv* env, jclass, jstring jarPathValue, jboolean bootstrap) {
    const std::string jarPath = JStringToUtf8(env, jarPathValue);
    if (jarPath.empty()) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "JAR path must not be empty");
        return;
    }
    const jvmtiError error = bootstrap == JNI_TRUE
        ? gJvmti->AddToBootstrapClassLoaderSearch(jarPath.c_str())
        : gJvmti->AddToSystemClassLoaderSearch(jarPath.c_str());
    ThrowJvmti(env, bootstrap == JNI_TRUE ? "AddToBootstrapClassLoaderSearch"
        : "AddToSystemClassLoaderSearch", error);
}

bool ResolveEvent(const std::string& input, jvmtiEvent* event) {
    std::string name = input;
    std::transform(name.begin(), name.end(), name.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    std::replace(name.begin(), name.end(), '-', '_');
    static const std::unordered_map<std::string, jvmtiEvent> events = {
        {"vm_init", JVMTI_EVENT_VM_INIT}, {"vm_death", JVMTI_EVENT_VM_DEATH},
        {"thread_start", JVMTI_EVENT_THREAD_START}, {"thread_end", JVMTI_EVENT_THREAD_END},
        {"class_load", JVMTI_EVENT_CLASS_LOAD}, {"class_prepare", JVMTI_EVENT_CLASS_PREPARE},
        {"class_file_load_hook", JVMTI_EVENT_CLASS_FILE_LOAD_HOOK},
        {"vm_start", JVMTI_EVENT_VM_START},
        {"single_step", JVMTI_EVENT_SINGLE_STEP}, {"frame_pop", JVMTI_EVENT_FRAME_POP},
        {"breakpoint", JVMTI_EVENT_BREAKPOINT}, {"field_access", JVMTI_EVENT_FIELD_ACCESS},
        {"field_modification", JVMTI_EVENT_FIELD_MODIFICATION},
        {"method_entry", JVMTI_EVENT_METHOD_ENTRY}, {"method_exit", JVMTI_EVENT_METHOD_EXIT},
        {"exception", JVMTI_EVENT_EXCEPTION}, {"exception_catch", JVMTI_EVENT_EXCEPTION_CATCH},
        {"native_method_bind", JVMTI_EVENT_NATIVE_METHOD_BIND},
        {"compiled_method_load", JVMTI_EVENT_COMPILED_METHOD_LOAD},
        {"compiled_method_unload", JVMTI_EVENT_COMPILED_METHOD_UNLOAD},
        {"dynamic_code_generated", JVMTI_EVENT_DYNAMIC_CODE_GENERATED},
        {"data_dump_request", JVMTI_EVENT_DATA_DUMP_REQUEST},
        {"monitor_contended_enter", JVMTI_EVENT_MONITOR_CONTENDED_ENTER},
        {"monitor_contended_entered", JVMTI_EVENT_MONITOR_CONTENDED_ENTERED},
        {"monitor_wait", JVMTI_EVENT_MONITOR_WAIT}, {"monitor_waited", JVMTI_EVENT_MONITOR_WAITED},
        {"vm_object_alloc", JVMTI_EVENT_VM_OBJECT_ALLOC},
        {"garbage_collection_start", JVMTI_EVENT_GARBAGE_COLLECTION_START},
        {"garbage_collection_finish", JVMTI_EVENT_GARBAGE_COLLECTION_FINISH},
        {"object_free", JVMTI_EVENT_OBJECT_FREE},
        {"resource_exhausted", JVMTI_EVENT_RESOURCE_EXHAUSTED},
    };
    const auto found = events.find(name);
    if (found == events.end()) return false;
    *event = found->second;
    return true;
}

jmethodID ResolveMethod(JNIEnv* env, jclass klass, const std::string& expectedName,
        const std::string& expectedDescriptor) {
    jint count = 0;
    jmethodID* methods = nullptr;
    const jvmtiError error = gJvmti->GetClassMethods(klass, &count, &methods);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetClassMethods", error);
        return nullptr;
    }
    jmethodID result = nullptr;
    for (jint index = 0; index < count; ++index) {
        char* name = nullptr;
        char* descriptor = nullptr;
        char* generic = nullptr;
        if (gJvmti->GetMethodName(methods[index], &name, &descriptor, &generic) == JVMTI_ERROR_NONE
            && name != nullptr && descriptor != nullptr && expectedName == name
            && expectedDescriptor == descriptor) {
            result = methods[index];
        }
        if (name != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
        if (descriptor != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(descriptor));
        if (generic != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
        if (result != nullptr) break;
    }
    if (methods != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(methods));
    if (result == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "Method was not found: " + expectedName + expectedDescriptor);
    }
    return result;
}

jfieldID ResolveField(JNIEnv* env, jclass klass, const std::string& expectedName,
        const std::string& expectedDescriptor) {
    jint count = 0;
    jfieldID* fields = nullptr;
    const jvmtiError error = gJvmti->GetClassFields(klass, &count, &fields);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetClassFields", error);
        return nullptr;
    }
    jfieldID result = nullptr;
    for (jint index = 0; index < count; ++index) {
        char* name = nullptr;
        char* descriptor = nullptr;
        char* generic = nullptr;
        if (gJvmti->GetFieldName(klass, fields[index], &name, &descriptor, &generic) == JVMTI_ERROR_NONE
            && name != nullptr && descriptor != nullptr && expectedName == name
            && expectedDescriptor == descriptor) {
            result = fields[index];
        }
        if (name != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
        if (descriptor != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(descriptor));
        if (generic != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
        if (result != nullptr) break;
    }
    if (fields != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(fields));
    if (result == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "Field was not found: " + expectedName + " " + expectedDescriptor);
    }
    return result;
}

void JNICALL NativeSetEventNotification(JNIEnv* env, jclass, jstring eventNameValue, jboolean enabled) {
    const std::string eventName = JStringToUtf8(env, eventNameValue);
    jvmtiEvent event{};
    if (!ResolveEvent(eventName, &event)) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Unsupported JVMTI event: " + eventName);
        return;
    }
    std::lock_guard<std::recursive_mutex> guard(gEventMutex);
    const jvmtiError error = gJvmti->SetEventNotificationMode(
        enabled == JNI_TRUE ? JVMTI_ENABLE : JVMTI_DISABLE, event, nullptr);
    if (error == JVMTI_ERROR_NONE && event == JVMTI_EVENT_CLASS_FILE_LOAD_HOOK) {
        gClassFileHookEnabled.store(enabled == JNI_TRUE);
    }
    ThrowJvmti(env, "SetEventNotificationMode", error);
}

void JNICALL NativeSetBreakpoint(JNIEnv* env, jclass, jclass klass, jstring methodNameValue,
        jstring descriptorValue, jlong location, jboolean enabled) {
    if (klass == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Class must not be null");
        return;
    }
    const std::string methodName = JStringToUtf8(env, methodNameValue);
    const std::string descriptor = JStringToUtf8(env, descriptorValue);
    jmethodID method = ResolveMethod(env, klass, methodName, descriptor);
    if (method == nullptr) return;
    const jvmtiError error = enabled == JNI_TRUE
        ? gJvmti->SetBreakpoint(method, static_cast<jlocation>(location))
        : gJvmti->ClearBreakpoint(method, static_cast<jlocation>(location));
    ThrowJvmti(env, enabled == JNI_TRUE ? "SetBreakpoint" : "ClearBreakpoint", error);
}

void JNICALL NativeSetFieldWatch(JNIEnv* env, jclass, jclass klass, jstring fieldNameValue,
        jstring descriptorValue, jboolean modification, jboolean enabled) {
    if (klass == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Class must not be null");
        return;
    }
    const std::string fieldName = JStringToUtf8(env, fieldNameValue);
    const std::string descriptor = JStringToUtf8(env, descriptorValue);
    jfieldID field = ResolveField(env, klass, fieldName, descriptor);
    if (field == nullptr) return;
    jvmtiError error;
    const char* operation;
    if (modification == JNI_TRUE && enabled == JNI_TRUE) {
        error = gJvmti->SetFieldModificationWatch(klass, field);
        operation = "SetFieldModificationWatch";
    } else if (modification == JNI_TRUE) {
        error = gJvmti->ClearFieldModificationWatch(klass, field);
        operation = "ClearFieldModificationWatch";
    } else if (enabled == JNI_TRUE) {
        error = gJvmti->SetFieldAccessWatch(klass, field);
        operation = "SetFieldAccessWatch";
    } else {
        error = gJvmti->ClearFieldAccessWatch(klass, field);
        operation = "ClearFieldAccessWatch";
    }
    ThrowJvmti(env, operation, error);
}

void JNICALL NativeNotifyFramePop(JNIEnv* env, jclass, jthread thread, jint depth) {
    if (thread == nullptr || depth < 0) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Thread is required and depth must not be negative");
        return;
    }
    ThrowJvmti(env, "NotifyFramePop", gJvmti->NotifyFramePop(thread, depth));
}

jlongArray JNICALL NativeEventQueueStatistics(JNIEnv* env, jclass) {
    jlong values[3] = {gNativeQueued.load(), gNativeDropped.load(), 0};
    {
        std::lock_guard<std::mutex> guard(gQueueMutex);
        values[2] = static_cast<jlong>(gEventQueue.size());
    }
    jlongArray result = env->NewLongArray(3);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 3, values);
    return result;
}

void JNICALL NativeRetransformClass(JNIEnv* env, jclass, jclass klass) {
    if (klass == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Class must not be null");
        return;
    }
    ThrowJvmti(env, "RetransformClasses", gJvmti->RetransformClasses(1, &klass));
}

void JNICALL NativeRedefineClass(JNIEnv* env, jclass, jclass klass, jbyteArray classBytes) {
    if (klass == nullptr || classBytes == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Class and bytes are required");
        return;
    }
    const jsize length = env->GetArrayLength(classBytes);
    jbyte* bytes = env->GetByteArrayElements(classBytes, nullptr);
    if (bytes == nullptr) return;
    jvmtiClassDefinition definition{};
    definition.klass = klass;
    definition.class_byte_count = length;
    definition.class_bytes = reinterpret_cast<unsigned char*>(bytes);
    const jvmtiError error = gJvmti->RedefineClasses(1, &definition);
    env->ReleaseByteArrayElements(classBytes, bytes, JNI_ABORT);
    ThrowJvmti(env, "RedefineClasses", error);
}

jstring JNICALL NativeCapabilities(JNIEnv* env, jclass) {
    jvmtiCapabilities capabilities{};
    const jvmtiError error = gJvmti->GetCapabilities(&capabilities);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetCapabilities", error);
        return nullptr;
    }
    std::vector<std::string> enabled;
#define JVMRTDP_CAPABILITY(field) if (capabilities.field) enabled.emplace_back(#field)
    JVMRTDP_CAPABILITY(can_tag_objects);
    JVMRTDP_CAPABILITY(can_generate_field_modification_events);
    JVMRTDP_CAPABILITY(can_generate_field_access_events);
    JVMRTDP_CAPABILITY(can_get_bytecodes);
    JVMRTDP_CAPABILITY(can_get_synthetic_attribute);
    JVMRTDP_CAPABILITY(can_get_owned_monitor_info);
    JVMRTDP_CAPABILITY(can_get_current_contended_monitor);
    JVMRTDP_CAPABILITY(can_get_monitor_info);
    JVMRTDP_CAPABILITY(can_pop_frame);
    JVMRTDP_CAPABILITY(can_redefine_classes);
    JVMRTDP_CAPABILITY(can_signal_thread);
    JVMRTDP_CAPABILITY(can_get_source_file_name);
    JVMRTDP_CAPABILITY(can_get_line_numbers);
    JVMRTDP_CAPABILITY(can_get_source_debug_extension);
    JVMRTDP_CAPABILITY(can_access_local_variables);
    JVMRTDP_CAPABILITY(can_maintain_original_method_order);
    JVMRTDP_CAPABILITY(can_generate_single_step_events);
    JVMRTDP_CAPABILITY(can_generate_frame_pop_events);
    JVMRTDP_CAPABILITY(can_generate_breakpoint_events);
    JVMRTDP_CAPABILITY(can_suspend);
    JVMRTDP_CAPABILITY(can_redefine_any_class);
    JVMRTDP_CAPABILITY(can_get_current_thread_cpu_time);
    JVMRTDP_CAPABILITY(can_get_thread_cpu_time);
    JVMRTDP_CAPABILITY(can_generate_method_entry_events);
    JVMRTDP_CAPABILITY(can_generate_method_exit_events);
    JVMRTDP_CAPABILITY(can_generate_all_class_hook_events);
    JVMRTDP_CAPABILITY(can_generate_compiled_method_load_events);
    JVMRTDP_CAPABILITY(can_generate_exception_events);
    JVMRTDP_CAPABILITY(can_generate_monitor_events);
    JVMRTDP_CAPABILITY(can_generate_vm_object_alloc_events);
    JVMRTDP_CAPABILITY(can_generate_native_method_bind_events);
    JVMRTDP_CAPABILITY(can_generate_garbage_collection_events);
    JVMRTDP_CAPABILITY(can_generate_object_free_events);
    JVMRTDP_CAPABILITY(can_force_early_return);
    JVMRTDP_CAPABILITY(can_get_owned_monitor_stack_depth_info);
    JVMRTDP_CAPABILITY(can_get_constant_pool);
    JVMRTDP_CAPABILITY(can_set_native_method_prefix);
    JVMRTDP_CAPABILITY(can_retransform_classes);
    JVMRTDP_CAPABILITY(can_retransform_any_class);
    JVMRTDP_CAPABILITY(can_generate_resource_exhaustion_heap_events);
    JVMRTDP_CAPABILITY(can_generate_resource_exhaustion_threads_events);
#undef JVMRTDP_CAPABILITY
    std::ostringstream output;
    for (std::size_t index = 0; index < enabled.size(); ++index) {
        if (index != 0) output << '\n';
        output << enabled[index];
    }
    return env->NewStringUTF(output.str().c_str());
}

jobjectArray JNICALL NativeCapabilityStatuses(JNIEnv* env, jclass) {
    jvmtiCapabilities enabled{};
    jvmtiCapabilities potential{};
    jvmtiError error = gJvmti->GetCapabilities(&enabled);
    if (error == JVMTI_ERROR_NONE) error = gJvmti->GetPotentialCapabilities(&potential);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "Get JVMTI capability status", error);
        return nullptr;
    }
    std::vector<std::string> rows;
#define JVMRTDP_CAPABILITY_STATUS(field) rows.emplace_back(std::string(#field) + "|" \
        + (enabled.field ? "1" : "0") + "|" + (potential.field ? "1" : "0"))
    JVMRTDP_CAPABILITY_STATUS(can_tag_objects);
    JVMRTDP_CAPABILITY_STATUS(can_generate_field_modification_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_field_access_events);
    JVMRTDP_CAPABILITY_STATUS(can_get_bytecodes);
    JVMRTDP_CAPABILITY_STATUS(can_get_synthetic_attribute);
    JVMRTDP_CAPABILITY_STATUS(can_get_owned_monitor_info);
    JVMRTDP_CAPABILITY_STATUS(can_get_current_contended_monitor);
    JVMRTDP_CAPABILITY_STATUS(can_get_monitor_info);
    JVMRTDP_CAPABILITY_STATUS(can_pop_frame);
    JVMRTDP_CAPABILITY_STATUS(can_redefine_classes);
    JVMRTDP_CAPABILITY_STATUS(can_signal_thread);
    JVMRTDP_CAPABILITY_STATUS(can_get_source_file_name);
    JVMRTDP_CAPABILITY_STATUS(can_get_line_numbers);
    JVMRTDP_CAPABILITY_STATUS(can_get_source_debug_extension);
    JVMRTDP_CAPABILITY_STATUS(can_access_local_variables);
    JVMRTDP_CAPABILITY_STATUS(can_maintain_original_method_order);
    JVMRTDP_CAPABILITY_STATUS(can_generate_single_step_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_frame_pop_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_breakpoint_events);
    JVMRTDP_CAPABILITY_STATUS(can_suspend);
    JVMRTDP_CAPABILITY_STATUS(can_redefine_any_class);
    JVMRTDP_CAPABILITY_STATUS(can_get_current_thread_cpu_time);
    JVMRTDP_CAPABILITY_STATUS(can_get_thread_cpu_time);
    JVMRTDP_CAPABILITY_STATUS(can_generate_method_entry_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_method_exit_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_all_class_hook_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_compiled_method_load_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_exception_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_monitor_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_vm_object_alloc_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_native_method_bind_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_garbage_collection_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_object_free_events);
    JVMRTDP_CAPABILITY_STATUS(can_force_early_return);
    JVMRTDP_CAPABILITY_STATUS(can_get_owned_monitor_stack_depth_info);
    JVMRTDP_CAPABILITY_STATUS(can_get_constant_pool);
    JVMRTDP_CAPABILITY_STATUS(can_set_native_method_prefix);
    JVMRTDP_CAPABILITY_STATUS(can_retransform_classes);
    JVMRTDP_CAPABILITY_STATUS(can_retransform_any_class);
    JVMRTDP_CAPABILITY_STATUS(can_generate_resource_exhaustion_heap_events);
    JVMRTDP_CAPABILITY_STATUS(can_generate_resource_exhaustion_threads_events);
#undef JVMRTDP_CAPABILITY_STATUS
    return NewStringArray(env, rows);
}

jobjectArray JNICALL NativeGetAllThreads(JNIEnv* env, jclass) {
    jint count = 0;
    jthread* threads = nullptr;
    const jvmtiError error = gJvmti->GetAllThreads(&count, &threads);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetAllThreads", error);
        return nullptr;
    }
    jclass threadClass = env->FindClass("java/lang/Thread");
    jobjectArray result = threadClass == nullptr ? nullptr : env->NewObjectArray(count, threadClass, nullptr);
    for (jint index = 0; result != nullptr && index < count; ++index) {
        env->SetObjectArrayElement(result, index, threads[index]);
        env->DeleteLocalRef(threads[index]);
    }
    if (threads != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(threads));
    if (threadClass != nullptr) env->DeleteLocalRef(threadClass);
    return result;
}

jint JNICALL NativeGetThreadState(JNIEnv* env, jclass, jthread thread) {
    jint state = 0;
    const jvmtiError error = gJvmti->GetThreadState(thread, &state);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetThreadState", error);
        return 0;
    }
    return state;
}

jobjectArray JNICALL NativeGetStackTrace(JNIEnv* env, jclass, jthread thread, jint maxFrames) {
    std::vector<jvmtiFrameInfo> frames(static_cast<std::size_t>(maxFrames));
    jint count = 0;
    const jvmtiError error = gJvmti->GetStackTrace(thread, 0, maxFrames, frames.data(), &count);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetStackTrace", error);
        return nullptr;
    }
    std::vector<std::string> values;
    values.reserve(static_cast<std::size_t>(count));
    for (jint index = 0; index < count; ++index) {
        const MethodDetails details = DescribeMethod(env, frames[static_cast<std::size_t>(index)].method);
        values.push_back(details.className + "." + details.name + details.descriptor + "@"
            + std::to_string(frames[static_cast<std::size_t>(index)].location));
    }
    return NewStringArray(env, values);
}

void JNICALL NativeThreadControl(JNIEnv* env, jclass, jthread thread, jint operation) {
    jvmtiError error = JVMTI_ERROR_ILLEGAL_ARGUMENT;
    const char* name = "ThreadControl";
    if (operation == 1) { error = gJvmti->SuspendThread(thread); name = "SuspendThread"; }
    else if (operation == 2) { error = gJvmti->ResumeThread(thread); name = "ResumeThread"; }
    else if (operation == 3) { error = gJvmti->InterruptThread(thread); name = "InterruptThread"; }
    ThrowJvmti(env, name, error);
}

jlong JNICALL NativeGetObjectSize(JNIEnv* env, jclass, jobject object) {
    jlong size = 0;
    const jvmtiError error = gJvmti->GetObjectSize(object, &size);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetObjectSize", error);
        return 0;
    }
    return size;
}

jlong JNICALL NativeGetTag(JNIEnv* env, jclass, jobject object) {
    jlong tag = 0;
    const jvmtiError error = gJvmti->GetTag(object, &tag);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetTag", error);
        return 0;
    }
    return tag;
}

void JNICALL NativeSetTag(JNIEnv* env, jclass, jobject object, jlong tag) {
    ThrowJvmti(env, "SetTag", gJvmti->SetTag(object, tag));
}

void JNICALL NativeForceGarbageCollection(JNIEnv* env, jclass) {
    ThrowJvmti(env, "ForceGarbageCollection", gJvmti->ForceGarbageCollection());
}

jobjectArray JNICALL NativeSystemProperties(JNIEnv* env, jclass) {
    jint count = 0;
    char** properties = nullptr;
    const jvmtiError error = gJvmti->GetSystemProperties(&count, &properties);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetSystemProperties", error);
        return nullptr;
    }
    std::vector<std::string> values;
    values.reserve(static_cast<std::size_t>(count));
    for (jint index = 0; index < count; ++index) {
        char* value = nullptr;
        const jvmtiError valueError = gJvmti->GetSystemProperty(properties[index], &value);
        values.push_back(std::string(properties[index]) + "="
            + (valueError == JVMTI_ERROR_NONE && value != nullptr ? value : ""));
        if (value != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(value));
        gJvmti->Deallocate(reinterpret_cast<unsigned char*>(properties[index]));
    }
    if (properties != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(properties));
    return NewStringArray(env, values);
}

JNINativeMethod kMethods[] = {
    {const_cast<char*>("nativeVersion"), const_cast<char*>("()Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeVersion)},
    {const_cast<char*>("nativeJvmtiVersion"), const_cast<char*>("()I"),
     reinterpret_cast<void*>(&NativeJvmtiVersion)},
    {const_cast<char*>("nativeGetClassBytes"), const_cast<char*>("(Ljava/lang/String;)[B"),
     reinterpret_cast<void*>(&NativeGetClassBytes)},
    {const_cast<char*>("nativeReadStaticFields"), const_cast<char*>("(Ljava/lang/String;)Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeReadStaticFields)},
    {const_cast<char*>("nativeReadStaticField"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeReadStaticField)},
    {const_cast<char*>("nativeCallStaticMethod"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeCallStaticMethod)},
    {const_cast<char*>("nativeFindLoadedClass"),
     const_cast<char*>("(Ljava/lang/String;)Ljava/lang/Class;"),
     reinterpret_cast<void*>(&NativeFindLoadedClass)},
    {const_cast<char*>("nativeListLoadedClassNames"), const_cast<char*>("()[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeListLoadedClassNames)},
    {const_cast<char*>("nativeListLoadedClasses"), const_cast<char*>("()[Ljava/lang/Class;"),
     reinterpret_cast<void*>(&NativeListLoadedClasses)},
    {const_cast<char*>("nativeDefineClass"),
     const_cast<char*>("(Ljava/lang/String;[BLjava/lang/ClassLoader;)Ljava/lang/Class;"),
     reinterpret_cast<void*>(&NativeDefineClass)},
    {const_cast<char*>("nativeAddToClassLoaderSearch"),
     const_cast<char*>("(Ljava/lang/String;Z)V"),
     reinterpret_cast<void*>(&NativeAddToClassLoaderSearch)},
    {const_cast<char*>("nativeSetEventNotification"),
     const_cast<char*>("(Ljava/lang/String;Z)V"),
     reinterpret_cast<void*>(&NativeSetEventNotification)},
    {const_cast<char*>("nativeSetBreakpoint"),
     const_cast<char*>("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;JZ)V"),
     reinterpret_cast<void*>(&NativeSetBreakpoint)},
    {const_cast<char*>("nativeSetFieldWatch"),
     const_cast<char*>("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;ZZ)V"),
     reinterpret_cast<void*>(&NativeSetFieldWatch)},
    {const_cast<char*>("nativeNotifyFramePop"), const_cast<char*>("(Ljava/lang/Thread;I)V"),
     reinterpret_cast<void*>(&NativeNotifyFramePop)},
    {const_cast<char*>("nativeEventQueueStatistics"), const_cast<char*>("()[J"),
     reinterpret_cast<void*>(&NativeEventQueueStatistics)},
    {const_cast<char*>("nativeRetransformClass"), const_cast<char*>("(Ljava/lang/Class;)V"),
     reinterpret_cast<void*>(&NativeRetransformClass)},
    {const_cast<char*>("nativeRedefineClass"), const_cast<char*>("(Ljava/lang/Class;[B)V"),
     reinterpret_cast<void*>(&NativeRedefineClass)},
    {const_cast<char*>("nativeCapabilities"), const_cast<char*>("()Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeCapabilities)},
    {const_cast<char*>("nativeCapabilityStatuses"), const_cast<char*>("()[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeCapabilityStatuses)},
    {const_cast<char*>("nativeGetAllThreads"), const_cast<char*>("()[Ljava/lang/Thread;"),
     reinterpret_cast<void*>(&NativeGetAllThreads)},
    {const_cast<char*>("nativeGetThreadState"), const_cast<char*>("(Ljava/lang/Thread;)I"),
     reinterpret_cast<void*>(&NativeGetThreadState)},
    {const_cast<char*>("nativeGetStackTrace"), const_cast<char*>("(Ljava/lang/Thread;I)[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeGetStackTrace)},
    {const_cast<char*>("nativeThreadControl"), const_cast<char*>("(Ljava/lang/Thread;I)V"),
     reinterpret_cast<void*>(&NativeThreadControl)},
    {const_cast<char*>("nativeGetObjectSize"), const_cast<char*>("(Ljava/lang/Object;)J"),
     reinterpret_cast<void*>(&NativeGetObjectSize)},
    {const_cast<char*>("nativeGetTag"), const_cast<char*>("(Ljava/lang/Object;)J"),
     reinterpret_cast<void*>(&NativeGetTag)},
    {const_cast<char*>("nativeSetTag"), const_cast<char*>("(Ljava/lang/Object;J)V"),
     reinterpret_cast<void*>(&NativeSetTag)},
    {const_cast<char*>("nativeForceGarbageCollection"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(&NativeForceGarbageCollection)},
    {const_cast<char*>("nativeSystemProperties"), const_cast<char*>("()[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeSystemProperties)},
};

bool RequestAllCapabilities() {
    if (gCapabilitiesRequested.load()) return true;
    jvmtiCapabilities potential{};
    if (gJvmti == nullptr || gJvmti->GetPotentialCapabilities(&potential) != JVMTI_ERROR_NONE
        || gJvmti->AddCapabilities(&potential) != JVMTI_ERROR_NONE) return false;
    gCapabilitiesRequested.store(true);
    jvmtiCapabilities capabilities{};
    if (gJvmti->GetCapabilities(&capabilities) == JVMTI_ERROR_NONE) {
        gCanRetransform = capabilities.can_retransform_classes != 0;
    }
    return true;
}

bool InstallJvmtiCallbacks() {
    if (gCallbacksInstalled.load()) return true;
    jvmtiEventCallbacks callbacks{};
    callbacks.VMInit = &VmInit;
    callbacks.VMDeath = &VmDeath;
    callbacks.ThreadStart = &ThreadStart;
    callbacks.ThreadEnd = &ThreadEnd;
    callbacks.ClassFileLoadHook = &ClassFileLoadHook;
    callbacks.ClassLoad = &ClassLoad;
    callbacks.ClassPrepare = &ClassPrepare;
    callbacks.VMStart = &VmStart;
    callbacks.Exception = &Exception;
    callbacks.ExceptionCatch = &ExceptionCatch;
    callbacks.SingleStep = &SingleStep;
    callbacks.FramePop = &FramePop;
    callbacks.Breakpoint = &Breakpoint;
    callbacks.FieldAccess = &FieldAccess;
    callbacks.FieldModification = &FieldModification;
    callbacks.MethodEntry = &MethodEntry;
    callbacks.MethodExit = &MethodExit;
    callbacks.NativeMethodBind = &NativeMethodBind;
    callbacks.CompiledMethodLoad = &CompiledMethodLoad;
    callbacks.CompiledMethodUnload = &CompiledMethodUnload;
    callbacks.DynamicCodeGenerated = &DynamicCodeGenerated;
    callbacks.DataDumpRequest = &DataDumpRequest;
    callbacks.MonitorContendedEnter = &MonitorContendedEnter;
    callbacks.MonitorContendedEntered = &MonitorContendedEntered;
    callbacks.MonitorWait = &MonitorWait;
    callbacks.MonitorWaited = &MonitorWaited;
    callbacks.GarbageCollectionStart = &GarbageCollectionStart;
    callbacks.GarbageCollectionFinish = &GarbageCollectionFinish;
    callbacks.ObjectFree = &ObjectFree;
    callbacks.VMObjectAlloc = &VmObjectAlloc;
    callbacks.ResourceExhausted = &ResourceExhausted;
    if (gJvmti == nullptr || gJvmti->SetEventCallbacks(&callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) {
        return false;
    }
    gCallbacksInstalled.store(true);
    return true;
}

void SetPreloadedAgentProperty(JNIEnv* env) {
    jclass systemClass = env->FindClass("java/lang/System");
    jmethodID setProperty = systemClass == nullptr ? nullptr : env->GetStaticMethodID(systemClass,
        "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring key = setProperty == nullptr ? nullptr : env->NewStringUTF("jvmrtdp.native.preloaded");
    jstring value = key == nullptr ? nullptr : env->NewStringUTF("true");
    jobject previous = value == nullptr ? nullptr
        : env->CallStaticObjectMethod(systemClass, setProperty, key, value);
    if (previous != nullptr) env->DeleteLocalRef(previous);
    if (key != nullptr) env->DeleteLocalRef(key);
    if (value != nullptr) env->DeleteLocalRef(value);
    if (systemClass != nullptr) env->DeleteLocalRef(systemClass);
}

jclass LoadSystemClass(JNIEnv* env, const char* binaryName) {
    jclass loaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getSystem = loaderClass == nullptr ? nullptr : env->GetStaticMethodID(
        loaderClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jmethodID loadClass = loaderClass == nullptr ? nullptr : env->GetMethodID(
        loaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jobject loader = getSystem == nullptr ? nullptr
        : env->CallStaticObjectMethod(loaderClass, getSystem);
    jstring name = loader == nullptr || loadClass == nullptr ? nullptr : env->NewStringUTF(binaryName);
    jclass result = name == nullptr ? nullptr : static_cast<jclass>(
        env->CallObjectMethod(loader, loadClass, name));
    if (name != nullptr) env->DeleteLocalRef(name);
    if (loader != nullptr) env->DeleteLocalRef(loader);
    if (loaderClass != nullptr) env->DeleteLocalRef(loaderClass);
    return result;
}

bool InitializeJavaBridge(JavaVM* vm, JNIEnv* env, bool preloadedAgent) {
    if (gDispatcherClass != nullptr && gDispatchMethod != nullptr && gTransformMethod != nullptr) return true;
    if (vm == nullptr || env == nullptr) return false;
    jclass bindingClass = preloadedAgent
        ? LoadSystemClass(env, "nhcm.jvmrtdp.agent.NativeAgent")
        : env->FindClass(kBindingClass.data());
    if (bindingClass == nullptr) {
        if (preloadedAgent && env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    if (preloadedAgent) SetPreloadedAgentProperty(env);
    if (env->ExceptionCheck() || !CacheCallbackTypes(env)) {
        if (preloadedAgent && env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(bindingClass);
        return false;
    }
    const jint registration = env->RegisterNatives(
        bindingClass, kMethods, static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0])));
    env->DeleteLocalRef(bindingClass);
    if (registration != JNI_OK) return false;
    gJavaVm = vm;

    jclass dispatcher = preloadedAgent
        ? LoadSystemClass(env, "nhcm.jvmrtdp.remoteside.JvmtiCallbackDispatcher")
        : env->FindClass("nhcm/jvmrtdp/remoteside/JvmtiCallbackDispatcher");
    if (dispatcher == nullptr) return false;
    gDispatcherClass = static_cast<jclass>(env->NewGlobalRef(dispatcher));
    gDispatchMethod = env->GetStaticMethodID(dispatcher, "dispatch",
        "(Ljava/lang/String;Ljava/lang/Thread;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        "JLjava/lang/Object;JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;J"
        "Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;"
        "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;[I"
        "[Ljava/lang/String;ILjava/lang/Object;)V");
    gTransformMethod = env->GetStaticMethodID(dispatcher, "transform",
        "(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/Class;Ljava/security/ProtectionDomain;[B)[B");
    env->DeleteLocalRef(dispatcher);
    if (gDispatcherClass == nullptr || gDispatchMethod == nullptr || gTransformMethod == nullptr) return false;
    if (gEventWorker == nullptr) {
        gWorkerStopping.store(false);
        try {
            gEventWorker = new std::thread(&EventWorkerMain);
        } catch (...) {
            return false;
        }
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char*, void*) {
    if (vm == nullptr || vm->GetEnv(reinterpret_cast<void**>(&gJvmti), JVMTI_VERSION_1_2) != JNI_OK
        || gJvmti == nullptr) return JNI_ERR;
    gJavaVm = vm;
    gLoadedAsJvmtiAgent.store(true);
    if (!RequestAllCapabilities() || !InstallJvmtiCallbacks()) return JNI_ERR;
    if (gJvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, nullptr)
        != JVMTI_ERROR_NONE) return JNI_ERR;
    return JNI_OK;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    if (gJvmti == nullptr
        && (vm->GetEnv(reinterpret_cast<void**>(&gJvmti), JVMTI_VERSION_1_2) != JNI_OK || gJvmti == nullptr)) {
        return JNI_ERR;
    }
    if (!RequestAllCapabilities() || !InstallJvmtiCallbacks()
        || !InitializeJavaBridge(vm, env, false)) return JNI_ERR;
    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
    if (gJvmti != nullptr) {
        jvmtiEventCallbacks callbacks{};
        gJvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    }
    gWorkerStopping.store(true);
    {
        std::lock_guard<std::mutex> guard(gQueueMutex);
        gEventQueue.clear();
    }
    gQueueChanged.notify_all();
    if (gEventWorker != nullptr) {
        if (gEventWorker->joinable()) gEventWorker->join();
        delete gEventWorker;
        gEventWorker = nullptr;
    }
    JNIEnv* env = nullptr;
    if (vm != nullptr && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK
        && env != nullptr) {
        if (gDispatcherClass != nullptr) env->DeleteGlobalRef(gDispatcherClass);
        ReleaseCallbackTypes(env);
    }
    gDispatcherClass = nullptr;
    gDispatchMethod = nullptr;
    gTransformMethod = nullptr;
    gClassFileHookEnabled.store(false);
    gCallbacksInstalled.store(false);
    gCapabilitiesRequested.store(false);
    gLoadedAsJvmtiAgent.store(false);
    gCanRetransform = false;
    gJvmti = nullptr;
    gJavaVm = nullptr;
}
