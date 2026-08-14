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
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {

constexpr std::string_view kRuntimeBindingClass =
    "nhcm/jvmrtdp/agent/nativebridge/NativeRuntimeBridge";
constexpr std::string_view kJniBindingClass =
    "nhcm/jvmrtdp/agent/nativebridge/NativeJniBridge";
constexpr std::string_view kJvmtiBindingClass =
    "nhcm/jvmrtdp/agent/nativebridge/NativeJvmtiBridge";
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
thread_local bool tMatchingStringAllocation = false;

class ThreadFlagGuard {
public:
    explicit ThreadFlagGuard(bool& flag) : flag_(flag) { flag_ = true; }
    ~ThreadFlagGuard() { flag_ = false; }
    ThreadFlagGuard(const ThreadFlagGuard&) = delete;
    ThreadFlagGuard& operator=(const ThreadFlagGuard&) = delete;

private:
    bool& flag_;
};
thread_local int tTemporaryClassFileHookDepth = 0;
jclass gDispatcherClass = nullptr;
jmethodID gDispatchMethod = nullptr;
jmethodID gTransformMethod = nullptr;
jclass gObjectClass = nullptr;
jclass gStringClass = nullptr;

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

std::mutex gDebuggerMutex;
std::condition_variable gDebuggerChanged;
bool gDebuggerEnabled = false;
bool gStartupBreakpointInstalled = false;
bool gStartupClinitBreakpointInstalled = false;
std::atomic<bool> gStartupClassPrepareOwned{false};
std::atomic<bool> gJavaMethodEntryDispatchEnabled{false};
std::atomic<bool> gJavaMethodExitDispatchEnabled{false};
std::atomic<bool> gJavaExceptionDispatchEnabled{false};
jmethodID gStartupMainMethod = nullptr;
jlocation gStartupMainLocation = 0;
jmethodID gStartupClinitMethod = nullptr;
jlocation gStartupClinitLocation = 0;
jlong gDebuggerSequence = 0;
std::string gStartupMainClass;
std::string gStartupClinitClass;

struct DebuggerStop {
    jobject thread = nullptr;
    jobject returnValue = nullptr;
    bool paused = true;
    // Breakpoint/single-step callbacks wait on gDebuggerChanged. A thread paused
    // from the debugger console is instead suspended with JVMTI SuspendThread.
    bool externallySuspended = false;
    jlong sequence = 0;
    std::string reason;
    std::string className;
    std::string methodName;
    std::string descriptor;
    jlocation location = 0;
    jint line = -1;
    std::string returnState;
};

std::vector<std::shared_ptr<DebuggerStop>> gDebuggerStops;

enum class DebugEventKind : jint {
    METHOD_ENTRY = 0,
    METHOD_EXIT = 1,
    EXCEPTION_THROW = 2,
};

struct DebugEventBreakpoint {
    std::string id;
    DebugEventKind kind = DebugEventKind::METHOD_ENTRY;
    jobject declaredClass = nullptr;
    std::string classPattern;
    std::string methodPattern;
    std::string descriptorPattern;
    bool includeSubtypes = false;
};

std::mutex gDebugEventBreakpointMutex;
std::vector<DebugEventBreakpoint> gDebugEventBreakpoints;

struct StringAllocationHook {
    std::string id;
    std::string contentPattern;
    std::string creatorClassPattern;
    std::string creatorMethodPattern;
    std::string creatorDescriptorPattern;
    bool caseSensitive = true;
};

std::mutex gStringAllocationHookMutex;
std::vector<StringAllocationHook> gStringAllocationHooks;
std::vector<jweak> gMatchedStringAllocations;
std::atomic<std::size_t> gStringAllocationHookCount{0};

// Step-out is completed at the first bytecode in the caller. A FramePop event
// alone fires while the returning frame is still visible and is therefore too early.
std::mutex gStepOutMutex;
std::vector<jobject> gStepOutAwaitingPop;
std::vector<jobject> gStepOutAwaitingCallerStep;

std::string gStartupEntrySpec;
std::string gStartupExitSpec;
std::string gStartupExceptionPattern;

struct PersistentBreakpoint {
    std::string id;
    std::string className;
    jobject klass = nullptr;
    jmethodID method = nullptr;
    std::string methodName;
    std::string descriptor;
    jlocation location = 0;
    jobject receiver = nullptr;
    std::string callerClass;
    std::string callerMethod;
    std::string callerDescriptor;
};

std::mutex gBreakpointMutex;
std::vector<PersistentBreakpoint> gPersistentBreakpoints;

struct PersistentFieldWatch {
    std::string id;
    std::string className;
    std::string fieldName;
    std::string descriptor;
    jobject klass = nullptr;
    jfieldID field = nullptr;
    bool modification = false;
    jobject receiver = nullptr;
};

std::mutex gFieldWatchMutex;
std::vector<PersistentFieldWatch> gPersistentFieldWatches;

std::string BinaryClassName(jclass klass);

bool SamePhysicalBreakpoint(JNIEnv* env, const PersistentBreakpoint& breakpoint,
        jclass klass, jmethodID method, jlocation location) {
    return breakpoint.method == method && breakpoint.location == location
        && breakpoint.klass != nullptr && env->IsSameObject(breakpoint.klass, klass) == JNI_TRUE;
}

bool HasPersistentBreakpoint(JNIEnv* env, jmethodID method, jlocation location) {
    std::lock_guard<std::mutex> guard(gBreakpointMutex);
    for (const PersistentBreakpoint& breakpoint : gPersistentBreakpoints) {
        if (breakpoint.method == method && breakpoint.location == location) return true;
    }
    return false;
}

void RememberPersistentBreakpoint(JNIEnv* env, jclass klass, jmethodID method,
        const std::string& id, const std::string& methodName, const std::string& descriptor,
        jlocation location, jobject receiver, const std::string& callerClass,
        const std::string& callerMethod, const std::string& callerDescriptor) {
    std::lock_guard<std::mutex> guard(gBreakpointMutex);
    for (const PersistentBreakpoint& breakpoint : gPersistentBreakpoints) {
        if (breakpoint.id == id) return;
    }
    jobject reference = env->NewGlobalRef(klass);
    if (reference == nullptr) return;
    jobject receiverReference = receiver == nullptr ? nullptr : env->NewGlobalRef(receiver);
    if (receiver != nullptr && receiverReference == nullptr) {
        env->DeleteGlobalRef(reference);
        return;
    }
    gPersistentBreakpoints.push_back(
        {id, BinaryClassName(klass), reference, method, methodName, descriptor, location, receiverReference,
            callerClass, callerMethod, callerDescriptor});
}

void RememberPendingBreakpoint(const std::string& id, const std::string& className,
        const std::string& methodName, const std::string& descriptor, jlocation location,
        const std::string& callerClass, const std::string& callerMethod,
        const std::string& callerDescriptor) {
    std::lock_guard<std::mutex> guard(gBreakpointMutex);
    for (const PersistentBreakpoint& breakpoint : gPersistentBreakpoints) {
        if (breakpoint.id == id) return;
    }
    gPersistentBreakpoints.push_back({id, className, nullptr, nullptr, methodName,
        descriptor, location, nullptr, callerClass, callerMethod, callerDescriptor});
}

bool ForgetPendingBreakpoint(const std::string& id) {
    std::lock_guard<std::mutex> guard(gBreakpointMutex);
    const std::size_t before = gPersistentBreakpoints.size();
    gPersistentBreakpoints.erase(std::remove_if(gPersistentBreakpoints.begin(),
        gPersistentBreakpoints.end(), [&](const PersistentBreakpoint& breakpoint) {
            return breakpoint.id == id && breakpoint.klass == nullptr;
        }), gPersistentBreakpoints.end());
    return before != gPersistentBreakpoints.size();
}

bool ForgetPersistentBreakpoint(JNIEnv* env, const std::string& id,
        jclass klass, jmethodID method, jlocation location) {
    std::lock_guard<std::mutex> guard(gBreakpointMutex);
    for (auto iterator = gPersistentBreakpoints.begin(); iterator != gPersistentBreakpoints.end();) {
        if (iterator->id != id) {
            ++iterator;
            continue;
        }
        if (iterator->klass != nullptr) env->DeleteGlobalRef(iterator->klass);
        if (iterator->receiver != nullptr) env->DeleteGlobalRef(iterator->receiver);
        iterator = gPersistentBreakpoints.erase(iterator);
    }
    for (const PersistentBreakpoint& breakpoint : gPersistentBreakpoints) {
        if (SamePhysicalBreakpoint(env, breakpoint, klass, method, location)) return true;
    }
    return false;
}

bool SamePhysicalFieldWatch(JNIEnv* env, const PersistentFieldWatch& watch,
        jclass klass, jfieldID field, bool modification) {
    return watch.field == field && watch.modification == modification
        && watch.klass != nullptr && env->IsSameObject(watch.klass, klass) == JNI_TRUE;
}

void RememberPersistentFieldWatch(JNIEnv* env, const std::string& id, jclass klass,
        jfieldID field, const std::string& fieldName, const std::string& descriptor,
        bool modification, jobject receiver) {
    std::lock_guard<std::mutex> guard(gFieldWatchMutex);
    for (const PersistentFieldWatch& watch : gPersistentFieldWatches) {
        if (watch.id == id) return;
    }
    jobject klassReference = env->NewGlobalRef(klass);
    if (klassReference == nullptr) return;
    jobject receiverReference = receiver == nullptr ? nullptr : env->NewGlobalRef(receiver);
    if (receiver != nullptr && receiverReference == nullptr) {
        env->DeleteGlobalRef(klassReference);
        return;
    }
    gPersistentFieldWatches.push_back(
        {id, BinaryClassName(klass), fieldName, descriptor, klassReference,
            field, modification, receiverReference});
}

void RememberPendingFieldWatch(const std::string& id, const std::string& className,
        const std::string& fieldName, const std::string& descriptor, bool modification) {
    std::lock_guard<std::mutex> guard(gFieldWatchMutex);
    for (const PersistentFieldWatch& watch : gPersistentFieldWatches) {
        if (watch.id == id) return;
    }
    gPersistentFieldWatches.push_back({id, className, fieldName, descriptor,
        nullptr, nullptr, modification, nullptr});
}

bool ForgetPendingFieldWatch(const std::string& id) {
    std::lock_guard<std::mutex> guard(gFieldWatchMutex);
    const std::size_t before = gPersistentFieldWatches.size();
    gPersistentFieldWatches.erase(std::remove_if(gPersistentFieldWatches.begin(),
        gPersistentFieldWatches.end(), [&](const PersistentFieldWatch& watch) {
            return watch.id == id && watch.klass == nullptr;
        }), gPersistentFieldWatches.end());
    return before != gPersistentFieldWatches.size();
}

bool ForgetPersistentFieldWatch(JNIEnv* env, const std::string& id, jclass klass,
        jfieldID field, bool modification) {
    std::lock_guard<std::mutex> guard(gFieldWatchMutex);
    for (auto iterator = gPersistentFieldWatches.begin();
            iterator != gPersistentFieldWatches.end();) {
        if (iterator->id != id) { ++iterator; continue; }
        if (iterator->klass != nullptr) env->DeleteGlobalRef(iterator->klass);
        if (iterator->receiver != nullptr) env->DeleteGlobalRef(iterator->receiver);
        iterator = gPersistentFieldWatches.erase(iterator);
    }
    for (const PersistentFieldWatch& watch : gPersistentFieldWatches) {
        if (SamePhysicalFieldWatch(env, watch, klass, field, modification)) return true;
    }
    return false;
}

bool MatchesPersistentFieldWatch(JNIEnv* env, jclass klass, jfieldID field,
        bool modification, jobject receiver) {
    std::lock_guard<std::mutex> guard(gFieldWatchMutex);
    bool physicalRegistration = false;
    for (const PersistentFieldWatch& watch : gPersistentFieldWatches) {
        if (!SamePhysicalFieldWatch(env, watch, klass, field, modification)) continue;
        physicalRegistration = true;
        if (watch.receiver == nullptr
                || (receiver != nullptr
                    && env->IsSameObject(receiver, watch.receiver) == JNI_TRUE)) return true;
    }
    return !physicalRegistration;
}

jvmtiError ReapplyPersistentBreakpoints(JNIEnv* env, jclass klass) {
    std::lock_guard<std::mutex> guard(gBreakpointMutex);
    for (const PersistentBreakpoint& breakpoint : gPersistentBreakpoints) {
        if (breakpoint.klass == nullptr
                || env->IsSameObject(breakpoint.klass, klass) != JNI_TRUE) continue;
        const jvmtiError error = gJvmti->SetBreakpoint(breakpoint.method, breakpoint.location);
        if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_DUPLICATE) return error;
    }
    return JVMTI_ERROR_NONE;
}

jobjectArray NewStringArray(JNIEnv* env, const std::vector<std::string>& values);
bool InitializeJavaBridge(JavaVM* vm, JNIEnv* env, bool preloadedAgent, jobject classLoader = nullptr);
void ReleaseCallbackTypes(JNIEnv* env);
void InstallPendingDebugRegistrations(JNIEnv* env, jclass klass);

std::string JStringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* characters = env->GetStringUTFChars(value, nullptr);
    if (characters == nullptr) return {};
    const jsize length = env->GetStringUTFLength(value);
    std::string result(characters, static_cast<std::size_t>(length));
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
    bool complete = gObjectClass != nullptr && gStringClass != nullptr;
    for (const BoxingType& boxing : gBoxingTypes) {
        complete = complete && boxing.klass != nullptr && boxing.valueOf != nullptr;
    }
    if (complete) return true;
    if (gObjectClass != nullptr) ReleaseCallbackTypes(env);

    jclass objectClass = env->FindClass("java/lang/Object");
    if (objectClass == nullptr) return false;
    gObjectClass = static_cast<jclass>(env->NewGlobalRef(objectClass));
    env->DeleteLocalRef(objectClass);
    if (gObjectClass == nullptr) return false;
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return false;
    gStringClass = static_cast<jclass>(env->NewGlobalRef(stringClass));
    env->DeleteLocalRef(stringClass);
    if (gStringClass == nullptr) return false;
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
    if (gStringClass != nullptr) env->DeleteGlobalRef(gStringClass);
    gStringClass = nullptr;
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

std::string JvmtiPhaseText() {
    if (gJvmti == nullptr) return "unavailable";
    jvmtiPhase phase{};
    if (gJvmti->GetPhase(&phase) != JVMTI_ERROR_NONE) return "unknown";
    switch (phase) {
    case JVMTI_PHASE_ONLOAD: return "ONLOAD";
    case JVMTI_PHASE_PRIMORDIAL: return "PRIMORDIAL";
    case JVMTI_PHASE_START: return "START";
    case JVMTI_PHASE_LIVE: return "LIVE";
    case JVMTI_PHASE_DEAD: return "DEAD";
    default: return std::to_string(static_cast<int>(phase));
    }
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

jclass FindLoadedClass(JNIEnv* env, const std::string& className, bool throwIfMissing = true) {
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
    if (result == nullptr && throwIfMissing) {
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

bool GlobMatches(const std::string& pattern, const std::string& value) {
    if (pattern.empty() || pattern == "*") return true;
    std::size_t source = 0, candidate = 0, star = std::string::npos, retry = 0;
    while (candidate < value.size()) {
        if (source < pattern.size()
                && (pattern[source] == '?' || pattern[source] == value[candidate])) {
            ++source;
            ++candidate;
        } else if (source < pattern.size() && pattern[source] == '*') {
            star = source++;
            retry = candidate;
        } else if (star != std::string::npos) {
            source = star + 1;
            candidate = ++retry;
        } else return false;
    }
    while (source < pattern.size() && pattern[source] == '*') ++source;
    return source == pattern.size();
}

bool MatchesDebugMethodEvent(JNIEnv* env, DebugEventKind kind, jmethodID method) {
    if (env == nullptr || method == nullptr) return false;
    {
        std::lock_guard<std::mutex> guard(gDebugEventBreakpointMutex);
        bool interested = false;
        for (const DebugEventBreakpoint& breakpoint : gDebugEventBreakpoints) {
            if (breakpoint.kind == kind) { interested = true; break; }
        }
        if (!interested) return false;
    }
    const MethodDetails details = DescribeMethod(env, method);
    jclass actualClass = nullptr;
    gJvmti->GetMethodDeclaringClass(method, &actualClass);
    bool matched = false;
    {
        std::lock_guard<std::mutex> guard(gDebugEventBreakpointMutex);
        for (const DebugEventBreakpoint& breakpoint : gDebugEventBreakpoints) {
            if (breakpoint.kind != kind
                    || !GlobMatches(breakpoint.methodPattern, details.name)
                    || !GlobMatches(breakpoint.descriptorPattern, details.descriptor)) continue;
            bool classMatches = GlobMatches(breakpoint.classPattern, details.className);
            if (!classMatches && breakpoint.includeSubtypes && actualClass != nullptr
                    && breakpoint.declaredClass != nullptr) {
                classMatches = env->IsAssignableFrom(actualClass,
                    static_cast<jclass>(breakpoint.declaredClass)) == JNI_TRUE;
            }
            if (classMatches) { matched = true; break; }
        }
    }
    if (actualClass != nullptr) env->DeleteLocalRef(actualClass);
    return matched;
}

bool MatchesDebugException(JNIEnv* env, jobject exception) {
    if (env == nullptr || exception == nullptr) return false;
    {
        std::lock_guard<std::mutex> guard(gDebugEventBreakpointMutex);
        bool interested = false;
        for (const DebugEventBreakpoint& breakpoint : gDebugEventBreakpoints) {
            if (breakpoint.kind == DebugEventKind::EXCEPTION_THROW) { interested = true; break; }
        }
        if (!interested) return false;
    }
    jclass exceptionClass = env->GetObjectClass(exception);
    const std::string className = BinaryClassName(exceptionClass);
    bool matched = false;
    {
        std::lock_guard<std::mutex> guard(gDebugEventBreakpointMutex);
        for (const DebugEventBreakpoint& breakpoint : gDebugEventBreakpoints) {
            if (breakpoint.kind == DebugEventKind::EXCEPTION_THROW
                    && GlobMatches(breakpoint.classPattern, className)) {
                matched = true;
                break;
            }
        }
    }
    if (exceptionClass != nullptr) env->DeleteLocalRef(exceptionClass);
    return matched;
}

void RemoveStepOutState(JNIEnv* env, jthread thread) {
    std::lock_guard<std::mutex> guard(gStepOutMutex);
    const auto remove = [env, thread](std::vector<jobject>& references) {
        for (auto iterator = references.begin(); iterator != references.end();) {
            if (thread == nullptr || env->IsSameObject(thread, *iterator) == JNI_TRUE) {
                if (gJvmti != nullptr) {
                    gJvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP,
                        static_cast<jthread>(*iterator));
                    gJvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_FRAME_POP,
                        static_cast<jthread>(*iterator));
                }
                env->DeleteGlobalRef(*iterator);
                iterator = references.erase(iterator);
            } else ++iterator;
        }
    };
    remove(gStepOutAwaitingPop);
    remove(gStepOutAwaitingCallerStep);
}

bool AdvanceStepOutAfterFramePop(JNIEnv* env, jthread thread) {
    std::lock_guard<std::mutex> guard(gStepOutMutex);
    for (auto iterator = gStepOutAwaitingPop.begin(); iterator != gStepOutAwaitingPop.end(); ++iterator) {
        if (env->IsSameObject(thread, *iterator) != JNI_TRUE) continue;
        gStepOutAwaitingCallerStep.push_back(*iterator);
        gStepOutAwaitingPop.erase(iterator);
        return true;
    }
    return false;
}

bool CompleteStepOutAtCaller(JNIEnv* env, jthread thread) {
    std::lock_guard<std::mutex> guard(gStepOutMutex);
    for (auto iterator = gStepOutAwaitingCallerStep.begin();
            iterator != gStepOutAwaitingCallerStep.end(); ++iterator) {
        if (env->IsSameObject(thread, *iterator) != JNI_TRUE) continue;
        env->DeleteGlobalRef(*iterator);
        gStepOutAwaitingCallerStep.erase(iterator);
        return true;
    }
    return false;
}

/** Returns true when any logical registration at this physical BCI accepts the hit. */
bool MatchesPersistentBreakpoint(JNIEnv* env, jthread thread,
        jmethodID method, jlocation location) {
    std::lock_guard<std::mutex> guard(gBreakpointMutex);
    bool physicalRegistration = false;
    bool receiverLoaded = false;
    jobject receiver = nullptr;
    bool callerLoaded = false;
    MethodDetails caller;
    for (const PersistentBreakpoint& breakpoint : gPersistentBreakpoints) {
        if (breakpoint.method != method || breakpoint.location != location) continue;
        physicalRegistration = true;
        if (breakpoint.receiver != nullptr) {
            if (!receiverLoaded) {
                receiverLoaded = true;
                if (gJvmti->GetLocalInstance(thread, 0, &receiver) != JVMTI_ERROR_NONE) {
                    receiver = nullptr;
                }
            }
            if (receiver == nullptr
                    || env->IsSameObject(receiver, breakpoint.receiver) != JNI_TRUE) continue;
        }
        if (!breakpoint.callerClass.empty() || !breakpoint.callerMethod.empty()
                || !breakpoint.callerDescriptor.empty()) {
            if (!callerLoaded) {
                callerLoaded = true;
                jvmtiFrameInfo frame{};
                jint count = 0;
                if (gJvmti->GetStackTrace(thread, 1, 1, &frame, &count) == JVMTI_ERROR_NONE
                        && count == 1) caller = DescribeMethod(env, frame.method);
            }
            if (!GlobMatches(breakpoint.callerClass, caller.className)
                    || !GlobMatches(breakpoint.callerMethod, caller.name)
                    || !GlobMatches(breakpoint.callerDescriptor, caller.descriptor)) continue;
        }
        if (receiver != nullptr) env->DeleteLocalRef(receiver);
        return true;
    }
    if (receiver != nullptr) env->DeleteLocalRef(receiver);
    // Startup/foreign breakpoints have no logical registration and keep legacy behavior.
    return !physicalRegistration;
}

jint LineAtLocation(jmethodID method, jlocation location) {
    if (method == nullptr || gJvmti == nullptr) return -1;
    jint count = 0;
    jvmtiLineNumberEntry* lines = nullptr;
    if (gJvmti->GetLineNumberTable(method, &count, &lines) != JVMTI_ERROR_NONE) return -1;
    jint result = -1;
    for (jint index = 0; index < count; ++index) {
        if (lines[index].start_location > location) break;
        result = lines[index].line_number;
    }
    if (lines != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(lines));
    return result;
}

bool IsJvmrtdpServiceThread(JNIEnv* env, jthread thread) {
    if (env == nullptr || thread == nullptr || gJvmti == nullptr) return false;
    jvmtiThreadInfo info{};
    if (gJvmti->GetThreadInfo(thread, &info) != JVMTI_ERROR_NONE) return false;
    std::string name = info.name == nullptr ? "" : info.name;
    if (info.name != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(info.name));
    if (info.thread_group != nullptr) env->DeleteLocalRef(info.thread_group);
    if (info.context_class_loader != nullptr) env->DeleteLocalRef(info.context_class_loader);
    std::transform(name.begin(), name.end(), name.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    return name.rfind("jvmrtdp", 0) == 0;
}

void DebuggerTrap(JNIEnv* env, jthread thread, jmethodID method, jlocation location,
        const char* reason, jobject returnValue = nullptr, const char* returnState = nullptr) {
    if (env == nullptr || thread == nullptr || method == nullptr) return;
    // Breakpoints in java.lang.String/collections can also be reached by the agent's
    // protocol implementation. Never stop the only threads capable of resuming a target.
    if (IsJvmrtdpServiceThread(env, thread)) return;
    const MethodDetails details = DescribeMethod(env, method);
    jobject threadReference = env->NewGlobalRef(thread);
    if (threadReference == nullptr) return;
    std::shared_ptr<DebuggerStop> stop = std::make_shared<DebuggerStop>();
    stop->thread = threadReference;
    stop->returnValue = returnValue == nullptr ? nullptr : env->NewGlobalRef(returnValue);
    stop->reason = reason == nullptr ? "breakpoint" : reason;
    stop->className = details.className;
    stop->methodName = details.name;
    stop->descriptor = details.descriptor;
    stop->location = location;
    stop->line = LineAtLocation(method, location);
    stop->returnState = returnState == nullptr ? "" : returnState;
    std::unique_lock<std::mutex> lock(gDebuggerMutex);
    if (!gDebuggerEnabled || gDebuggerStops.size() >= 128) {
        lock.unlock();
        if (stop->returnValue != nullptr) env->DeleteGlobalRef(stop->returnValue);
        env->DeleteGlobalRef(threadReference);
        return;
    }
    stop->sequence = ++gDebuggerSequence;
    gDebuggerStops.push_back(stop);
    gDebuggerChanged.notify_all();
    gDebuggerChanged.wait(lock, [&stop] { return !stop->paused || !gDebuggerEnabled; });
    gDebuggerStops.erase(std::remove(gDebuggerStops.begin(), gDebuggerStops.end(), stop), gDebuggerStops.end());
    lock.unlock();
    if (stop->returnValue != nullptr) env->DeleteGlobalRef(stop->returnValue);
    env->DeleteGlobalRef(threadReference);
}

std::string FoldAscii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    return value;
}

bool StringAllocationPatternMatches(const std::string& pattern,
        const std::string& value, bool caseSensitive) {
    return caseSensitive ? GlobMatches(pattern, value)
        : GlobMatches(FoldAscii(pattern), FoldAscii(value));
}

bool WasMatchedStringAllocation(JNIEnv* env, jobject value) {
    std::lock_guard<std::mutex> guard(gStringAllocationHookMutex);
    bool matched = false;
    for (auto iterator = gMatchedStringAllocations.begin();
            iterator != gMatchedStringAllocations.end();) {
        if (env->IsSameObject(*iterator, nullptr) == JNI_TRUE) {
            env->DeleteWeakGlobalRef(*iterator);
            iterator = gMatchedStringAllocations.erase(iterator);
            continue;
        }
        if (env->IsSameObject(*iterator, value) == JNI_TRUE) matched = true;
        ++iterator;
    }
    return matched;
}

void RememberMatchedStringAllocation(JNIEnv* env, jobject value) {
    jweak reference = env->NewWeakGlobalRef(value);
    if (reference == nullptr) return;
    std::lock_guard<std::mutex> guard(gStringAllocationHookMutex);
    gMatchedStringAllocations.push_back(reference);
}

bool AllocatedByNewInstruction(jmethodID method, jlocation location) {
    if (method == nullptr || location < 0 || gJvmti == nullptr) return false;
    jint count = 0;
    unsigned char* bytecodes = nullptr;
    if (gJvmti->GetBytecodes(method, &count, &bytecodes) != JVMTI_ERROR_NONE
            || bytecodes == nullptr) return false;
    const bool result = location < count && bytecodes[location] == 0xbb; // JVM NEW
    gJvmti->Deallocate(bytecodes);
    return result;
}

bool TrapMatchingStringAllocation(JNIEnv* env, jthread thread, jobject stringValue) {
    if (env == nullptr || thread == nullptr || stringValue == nullptr
            || gStringAllocationHookCount.load() == 0 || tMatchingStringAllocation) return false;
    if (WasMatchedStringAllocation(env, stringValue)) return false;
    ThreadFlagGuard matchingGuard(tMatchingStringAllocation);
    const char* characters = env->GetStringUTFChars(static_cast<jstring>(stringValue), nullptr);
    if (characters == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    const jsize contentLength = env->GetStringUTFLength(static_cast<jstring>(stringValue));
    const std::string content(characters, static_cast<std::size_t>(contentLength));
    env->ReleaseStringUTFChars(static_cast<jstring>(stringValue), characters);

    constexpr jint kMaximumFrames = 64;
    jvmtiFrameInfo frames[kMaximumFrames]{};
    jint frameCount = 0;
    if (gJvmti->GetStackTrace(thread, 0, kMaximumFrames, frames, &frameCount)
            != JVMTI_ERROR_NONE || frameCount == 0) {
        return false;
    }
    std::vector<MethodDetails> details;
    details.reserve(static_cast<std::size_t>(frameCount));
    for (jint index = 0; index < frameCount; ++index) {
        details.push_back(DescribeMethod(env, frames[index].method));
    }

    std::vector<StringAllocationHook> hooks;
    {
        std::lock_guard<std::mutex> guard(gStringAllocationHookMutex);
        hooks = gStringAllocationHooks;
    }
    std::vector<std::string> matchedIds;
    for (const StringAllocationHook& hook : hooks) {
        if (!StringAllocationPatternMatches(
                hook.contentPattern, content, hook.caseSensitive)) continue;
        bool creatorMatched = false;
        for (const MethodDetails& frame : details) {
            if (StringAllocationPatternMatches(hook.creatorClassPattern,
                        frame.className, hook.caseSensitive)
                    && StringAllocationPatternMatches(hook.creatorMethodPattern,
                        frame.name, hook.caseSensitive)
                    && StringAllocationPatternMatches(hook.creatorDescriptorPattern,
                        frame.descriptor, hook.caseSensitive)) {
                creatorMatched = true;
                break;
            }
        }
        if (creatorMatched) matchedIds.push_back(hook.id);
    }
    if (matchedIds.empty()) {
        return false;
    }

    std::string reason = "string_alloc:";
    for (std::size_t index = 0; index < matchedIds.size(); ++index) {
        if (index != 0) reason.push_back(',');
        reason += matchedIds[index];
    }
    RememberMatchedStringAllocation(env, stringValue);
    DebuggerTrap(env, thread, frames[0].method, frames[0].location,
        reason.c_str(), stringValue, "allocation");
    return true;
}

bool TrapCompletedStringConstructor(JNIEnv* env, jthread thread, jmethodID method,
        jboolean poppedByException) {
    if (poppedByException == JNI_TRUE || gStringAllocationHookCount.load() == 0) return false;
    jclass declaringClass = nullptr;
    if (gJvmti->GetMethodDeclaringClass(method, &declaringClass) != JVMTI_ERROR_NONE
            || declaringClass == nullptr) return false;
    const bool stringMethod = gStringClass != nullptr
        && env->IsSameObject(declaringClass, gStringClass) == JNI_TRUE;
    env->DeleteLocalRef(declaringClass);
    if (!stringMethod) return false;
    char* methodName = nullptr;
    const jvmtiError nameError = gJvmti->GetMethodName(method, &methodName, nullptr, nullptr);
    const bool constructor = nameError == JVMTI_ERROR_NONE && methodName != nullptr
        && std::strcmp(methodName, "<init>") == 0;
    if (methodName != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(methodName));
    if (!constructor) return false;
    jobject receiver = nullptr;
    if (gJvmti->GetLocalInstance(thread, 0, &receiver) != JVMTI_ERROR_NONE
            || receiver == nullptr) return false;
    const bool stopped = TrapMatchingStringAllocation(env, thread, receiver);
    env->DeleteLocalRef(receiver);
    return stopped;
}

void ConfigureStartupDebugger(const char* options) {
    if (options == nullptr) return;
    std::string input(options);
    const auto option = [&input](const std::string& key) {
        std::size_t position = input.find(key);
        if (position == std::string::npos) return std::string();
        position += key.size();
        // Comma is the option separator. Semicolons are valid inside JVM descriptors.
        const std::size_t end = input.find(',', position);
        return input.substr(position,
            end == std::string::npos ? std::string::npos : end - position);
    };
    gStartupMainClass = option("break-main=");
    gStartupClinitClass = option("break-clinit=");
    std::replace(gStartupMainClass.begin(), gStartupMainClass.end(), '/', '.');
    std::replace(gStartupClinitClass.begin(), gStartupClinitClass.end(), '/', '.');
    gStartupEntrySpec = option("break-entry=");
    gStartupExitSpec = option("break-exit=");
    gStartupExceptionPattern = option("break-exception=");
    const auto rememberMethodEvent = [](DebugEventKind kind, const std::string& spec,
            const char* id) {
        if (spec.empty()) return;
        const std::size_t first = spec.find('#');
        const std::size_t second = first == std::string::npos
            ? std::string::npos : spec.find('#', first + 1);
        DebugEventBreakpoint breakpoint;
        breakpoint.id = id;
        breakpoint.kind = kind;
        breakpoint.classPattern = first == std::string::npos ? spec : spec.substr(0, first);
        std::replace(breakpoint.classPattern.begin(), breakpoint.classPattern.end(), '/', '.');
        breakpoint.methodPattern = first == std::string::npos ? "*" : spec.substr(first + 1,
            second == std::string::npos ? std::string::npos : second - first - 1);
        breakpoint.descriptorPattern = second == std::string::npos ? "*" : spec.substr(second + 1);
        gDebugEventBreakpoints.push_back(std::move(breakpoint));
    };
    rememberMethodEvent(DebugEventKind::METHOD_ENTRY, gStartupEntrySpec, "startup-entry");
    rememberMethodEvent(DebugEventKind::METHOD_EXIT, gStartupExitSpec, "startup-exit");
    if (!gStartupExceptionPattern.empty()) {
        DebugEventBreakpoint breakpoint;
        breakpoint.id = "startup-exception";
        breakpoint.kind = DebugEventKind::EXCEPTION_THROW;
        breakpoint.classPattern = gStartupExceptionPattern;
        std::replace(breakpoint.classPattern.begin(), breakpoint.classPattern.end(), '/', '.');
        gDebugEventBreakpoints.push_back(std::move(breakpoint));
    }
    if (!gStartupMainClass.empty() || !gStartupClinitClass.empty()
            || !gStartupEntrySpec.empty() || !gStartupExitSpec.empty()
            || !gStartupExceptionPattern.empty()) gDebuggerEnabled = true;
}

void ReleaseStartupClassPrepareIfReady() {
    if (!gStartupMainClass.empty() || !gStartupClinitClass.empty()) return;
    if (gStartupClassPrepareOwned.exchange(false)) {
        gJvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_CLASS_PREPARE, nullptr);
    }
}

void InstallStartupMainBreakpoint(JNIEnv* env, jclass klass) {
    if (env == nullptr || klass == nullptr || gStartupMainClass.empty()
        || gStartupBreakpointInstalled || BinaryClassName(klass) != gStartupMainClass) return;
    jint count = 0;
    jmethodID* methods = nullptr;
    if (gJvmti->GetClassMethods(klass, &count, &methods) != JVMTI_ERROR_NONE) return;
    for (jint index = 0; index < count; ++index) {
        const MethodDetails details = DescribeMethod(env, methods[index]);
        if (details.name != "main" || details.descriptor != "([Ljava/lang/String;)V") continue;
        jint modifiers = 0;
        jlocation start = 0;
        jlocation end = 0;
        if (gJvmti->GetMethodModifiers(methods[index], &modifiers) == JVMTI_ERROR_NONE
            && (modifiers & kAccStatic) != 0
            && gJvmti->GetMethodLocation(methods[index], &start, &end) == JVMTI_ERROR_NONE
            && gJvmti->SetBreakpoint(methods[index], start) == JVMTI_ERROR_NONE) {
            gStartupBreakpointInstalled = true;
            gStartupMainMethod = methods[index];
            gStartupMainLocation = start;
        }
        break;
    }
    if (methods != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(methods));
    gStartupMainClass.clear();
    ReleaseStartupClassPrepareIfReady();
}

void InstallStartupClinitBreakpoint(JNIEnv* env, jclass klass) {
    if (env == nullptr || klass == nullptr || gStartupClinitClass.empty()
        || gStartupClinitBreakpointInstalled || BinaryClassName(klass) != gStartupClinitClass) return;
    jint count = 0;
    jmethodID* methods = nullptr;
    if (gJvmti->GetClassMethods(klass, &count, &methods) != JVMTI_ERROR_NONE) return;
    for (jint index = 0; index < count; ++index) {
        const MethodDetails details = DescribeMethod(env, methods[index]);
        if (details.name != "<clinit>" || details.descriptor != "()V") continue;
        jlocation start = 0;
        jlocation end = 0;
        if (gJvmti->GetMethodLocation(methods[index], &start, &end) == JVMTI_ERROR_NONE
            && gJvmti->SetBreakpoint(methods[index], start) == JVMTI_ERROR_NONE) {
            gStartupClinitBreakpointInstalled = true;
            gStartupClinitMethod = methods[index];
            gStartupClinitLocation = start;
        }
        break;
    }
    if (methods != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(methods));
    // ClassPrepare proves the target is loaded. If it has no <clinit>, there is nothing
    // the VM can stop before; do not keep a global ClassPrepare notification forever.
    gStartupClinitClass.clear();
    ReleaseStartupClassPrepareIfReady();
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
        InitializeJavaBridge(gJavaVm, env, true, nullptr);
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
    // Resolve pending symbolic breakpoints/watchpoints before <clinit> or ordinary
    // bytecode can execute. The controller may be disconnected when this fires.
    InstallPendingDebugRegistrations(env, klass);
    DispatchEvent(env, "class_prepare", thread, klass, nullptr, 0, klass, 0);
    InstallStartupMainBreakpoint(env, klass);
    InstallStartupClinitBreakpoint(env, klass);
}

void JNICALL SingleStep(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method, jlocation location) {
    gJvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
    const bool stepOut = CompleteStepOutAtCaller(env, thread);
    DispatchEvent(env, "single_step", thread, nullptr, method, location, nullptr, 0);
    DebuggerTrap(env, thread, method, location, stepOut ? "step_out" : "single_step");
}

void JNICALL FramePop(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method,
        jboolean wasPoppedByException) {
    DispatchEvent(env, "frame_pop", thread, nullptr, method, 0, nullptr,
        wasPoppedByException == JNI_TRUE ? 1 : 0);
    if (AdvanceStepOutAfterFramePop(env, thread)) {
        gJvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_FRAME_POP, thread);
        if (gJvmti->SetEventNotificationMode(JVMTI_ENABLE,
                JVMTI_EVENT_SINGLE_STEP, thread) != JVMTI_ERROR_NONE) {
            RemoveStepOutState(env, thread);
        }
    }
}

void JNICALL Breakpoint(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method, jlocation location) {
    DispatchEvent(env, "breakpoint", thread, nullptr, method, location, nullptr, 0);
    const bool startupMain = gStartupBreakpointInstalled && method == gStartupMainMethod
        && location == gStartupMainLocation;
    const bool startupClinit = gStartupClinitBreakpointInstalled && method == gStartupClinitMethod
        && location == gStartupClinitLocation;
    if (startupMain) {
        // break-main is a one-shot bootstrap stop. Leaving it installed can retrap the
        // launcher and keeps startup-only bookkeeping alive during AWT/Swing initialization.
        if (!HasPersistentBreakpoint(env, gStartupMainMethod, gStartupMainLocation)) {
            gJvmti->ClearBreakpoint(gStartupMainMethod, gStartupMainLocation);
        }
        gStartupBreakpointInstalled = false;
        gStartupMainMethod = nullptr;
        gStartupMainLocation = 0;
    }
    if (startupClinit) {
        if (!HasPersistentBreakpoint(env, gStartupClinitMethod, gStartupClinitLocation)) {
            gJvmti->ClearBreakpoint(gStartupClinitMethod, gStartupClinitLocation);
        }
        gStartupClinitBreakpointInstalled = false;
        gStartupClinitMethod = nullptr;
        gStartupClinitLocation = 0;
    }
    if (!startupMain && !startupClinit
            && !MatchesPersistentBreakpoint(env, thread, method, location)) return;
    DebuggerTrap(env, thread, method, location,
        startupMain ? "main_entry" : startupClinit ? "class_init" : "breakpoint");
}

void JNICALL FieldAccess(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method,
        jlocation location, jclass fieldClass, jobject object, jfieldID field) {
    const FieldDetails details = DescribeField(fieldClass, field);
    DispatchEvent(env, "field_access", thread, fieldClass, method, location, object, 0,
        nullptr, 0, details.name.c_str(), details.descriptor.c_str());
    if (!MatchesPersistentFieldWatch(env, fieldClass, field, false, object)) return;
    DebuggerTrap(env, thread, method, location,
        ("field_read:" + BinaryClassName(fieldClass) + "." + details.name
            + details.descriptor).c_str());
}

void JNICALL FieldModification(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method,
        jlocation location, jclass fieldClass, jobject object, jfieldID field,
        char signatureType, jvalue newValue) {
    const FieldDetails details = DescribeField(fieldClass, field);
    jobject newObject = nullptr;
    const jlong bits = ValueBits(signatureType, newValue, &newObject);
    DispatchEvent(env, "field_modification", thread, fieldClass, method, location, object, bits,
        nullptr, 0, details.name.c_str(), details.descriptor.c_str(), newObject);
    if (!MatchesPersistentFieldWatch(env, fieldClass, field, true, object)) return;
    DebuggerTrap(env, thread, method, location,
        ("field_write:" + BinaryClassName(fieldClass) + "." + details.name
            + details.descriptor).c_str());
}

void JNICALL MethodEntry(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method) {
    if (tInJavaCallback || tCapturingMethodEvent) return;
    const bool shouldStop = MatchesDebugMethodEvent(env, DebugEventKind::METHOD_ENTRY, method);
    if (gJavaMethodEntryDispatchEnabled.load()) {
        tCapturingMethodEvent = true;
        try {
            DispatchMethodEvent(env, "method_entry", thread, method, false, nullptr, 0, nullptr);
        } catch (...) {
            gNativeDropped.fetch_add(1);
        }
        tCapturingMethodEvent = false;
    }
    if (shouldStop) DebuggerTrap(env, thread, method, 0, "method_entry");
}

void JNICALL MethodExit(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method,
        jboolean poppedByException, jvalue returnValue) {
    if (tInJavaCallback || tCapturingMethodEvent) return;
    const bool shouldStop = MatchesDebugMethodEvent(env, DebugEventKind::METHOD_EXIT, method);
    const bool dispatchEvent = gJavaMethodExitDispatchEnabled.load();
    const bool allocationInterest = gStringAllocationHookCount.load() != 0
        && poppedByException != JNI_TRUE;
    if (!shouldStop && !dispatchEvent) {
        if (!allocationInterest) return;
        ThreadFlagGuard capturingGuard(tCapturingMethodEvent);
        try { TrapCompletedStringConstructor(env, thread, method, poppedByException); }
        catch (...) { gNativeDropped.fetch_add(1); }
        return;
    }
    // Always suppress recursive method events caused by boxing the return value.
    ThreadFlagGuard capturingGuard(tCapturingMethodEvent);
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
    if (dispatchEvent) {
        DispatchMethodEvent(env, "method_exit", thread, method, poppedByException == JNI_TRUE,
            subject, bits, boxedReturn);
    }
    const bool stringAllocationStop = TrapCompletedStringConstructor(
        env, thread, method, poppedByException);
    if (shouldStop && !stringAllocationStop) DebuggerTrap(env, thread, method, -1,
        poppedByException == JNI_TRUE ? "method_exit_exception" : "method_exit",
        boxedReturn, poppedByException == JNI_TRUE ? "exception"
            : returnKind == 'V' ? "void" : "value");
    if (boxedReturn != nullptr && returnKind != 'L' && returnKind != '[') {
        env->DeleteLocalRef(boxedReturn);
    }
    } catch (...) {
        gNativeDropped.fetch_add(1);
    }
}

void JNICALL Exception(jvmtiEnv*, JNIEnv* env, jthread thread, jmethodID method,
        jlocation location, jobject exception, jmethodID catchMethod, jlocation catchLocation) {
    if (gJavaExceptionDispatchEnabled.load()) {
        DispatchEvent(env, "exception", thread, nullptr, method, location, exception, 0,
            catchMethod, catchLocation);
    }
    if (MatchesDebugException(env, exception)) {
        DebuggerTrap(env, thread, method, location, "exception");
    }
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
    if (gStringAllocationHookCount.load() == 0
            || BinaryClassName(klass) != "java.lang.String") return;
    jmethodID method = nullptr;
    jlocation location = 0;
    if (gJvmti->GetFrameLocation(thread, 0, &method, &location) == JVMTI_ERROR_NONE
            && AllocatedByNewInstruction(method, location)) {
        // The String is not initialized until its constructor exits; match it there.
        return;
    }
    TrapMatchingStringAllocation(env, thread, object);
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
            if (error == JVMTI_ERROR_NONE) error = ReapplyPersistentBreakpoints(env, klass);
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
        const std::string& expectedDescriptor, bool throwIfMissing = true) {
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
    if (result == nullptr && throwIfMissing) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "Method was not found: " + expectedName + expectedDescriptor);
    }
    return result;
}

jfieldID ResolveField(JNIEnv* env, jclass klass, const std::string& expectedName,
        const std::string& expectedDescriptor, bool throwIfMissing = true) {
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
    if (result == nullptr && throwIfMissing) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "Field was not found: " + expectedName + " " + expectedDescriptor);
    }
    return result;
}

void InstallPendingDebugRegistrations(JNIEnv* env, jclass klass) {
    if (env == nullptr || klass == nullptr || gJvmti == nullptr) return;
    const std::string className = BinaryClassName(klass);
    if (className.empty()) return;

    {
        std::lock_guard<std::mutex> guard(gBreakpointMutex);
        for (PersistentBreakpoint& breakpoint : gPersistentBreakpoints) {
            if (breakpoint.klass != nullptr || breakpoint.className != className) continue;
            jmethodID method = ResolveMethod(env, klass, breakpoint.methodName,
                breakpoint.descriptor, false);
            if (method == nullptr || env->ExceptionCheck()) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                continue;
            }
            const jvmtiError error = gJvmti->SetBreakpoint(method, breakpoint.location);
            if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_DUPLICATE) continue;
            jobject reference = env->NewGlobalRef(klass);
            if (reference == nullptr) continue;
            breakpoint.klass = reference;
            breakpoint.method = method;
        }
    }

    {
        std::lock_guard<std::mutex> guard(gFieldWatchMutex);
        for (PersistentFieldWatch& watch : gPersistentFieldWatches) {
            if (watch.klass != nullptr || watch.className != className) continue;
            jfieldID field = ResolveField(env, klass, watch.fieldName, watch.descriptor, false);
            if (field == nullptr || env->ExceptionCheck()) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                continue;
            }
            const jvmtiError error = watch.modification
                ? gJvmti->SetFieldModificationWatch(klass, field)
                : gJvmti->SetFieldAccessWatch(klass, field);
            if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_DUPLICATE) continue;
            jobject reference = env->NewGlobalRef(klass);
            if (reference == nullptr) continue;
            watch.klass = reference;
            watch.field = field;
        }
    }

    // An unloaded declared type cannot have a jclass reference at registration
    // time. Attach it here so include-subtypes event breakpoints gain exact JVM
    // assignability checks as soon as the declared type is prepared.
    {
        std::lock_guard<std::mutex> guard(gDebugEventBreakpointMutex);
        for (DebugEventBreakpoint& breakpoint : gDebugEventBreakpoints) {
            if (!breakpoint.includeSubtypes || breakpoint.declaredClass != nullptr
                    || breakpoint.classPattern != className) continue;
            breakpoint.declaredClass = env->NewGlobalRef(klass);
        }
    }
}

void JNICALL NativeSetEventNotification(JNIEnv* env, jclass, jstring eventNameValue, jboolean enabled) {
    const std::string eventName = JStringToUtf8(env, eventNameValue);
    jvmtiEvent event{};
    if (!ResolveEvent(eventName, &event)) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Unsupported JVMTI event: " + eventName);
        return;
    }
    if (event == JVMTI_EVENT_METHOD_ENTRY) {
        gJavaMethodEntryDispatchEnabled.store(enabled == JNI_TRUE);
    } else if (event == JVMTI_EVENT_METHOD_EXIT) {
        gJavaMethodExitDispatchEnabled.store(enabled == JNI_TRUE);
    } else if (event == JVMTI_EVENT_EXCEPTION) {
        gJavaExceptionDispatchEnabled.store(enabled == JNI_TRUE);
    }
    if (enabled != JNI_TRUE && (event == JVMTI_EVENT_METHOD_ENTRY
            || event == JVMTI_EVENT_METHOD_EXIT || event == JVMTI_EVENT_EXCEPTION)) {
        const DebugEventKind kind = event == JVMTI_EVENT_METHOD_ENTRY
            ? DebugEventKind::METHOD_ENTRY : event == JVMTI_EVENT_METHOD_EXIT
                ? DebugEventKind::METHOD_EXIT : DebugEventKind::EXCEPTION_THROW;
        std::lock_guard<std::mutex> breakpointGuard(gDebugEventBreakpointMutex);
        for (const DebugEventBreakpoint& breakpoint : gDebugEventBreakpoints) {
            // Java callback leases may be released independently of debugger leases.
            if (breakpoint.kind == kind) return;
        }
    }
    std::lock_guard<std::recursive_mutex> guard(gEventMutex);
    const jvmtiError error = gJvmti->SetEventNotificationMode(
        enabled == JNI_TRUE ? JVMTI_ENABLE : JVMTI_DISABLE, event, nullptr);
    if (error == JVMTI_ERROR_NONE && event == JVMTI_EVENT_CLASS_FILE_LOAD_HOOK) {
        gClassFileHookEnabled.store(enabled == JNI_TRUE);
    }
    if (event == JVMTI_EVENT_CLASS_PREPARE) gStartupClassPrepareOwned.store(false);
    ThrowJvmti(env, "SetEventNotificationMode", error);
}

void JNICALL NativeSetBreakpoint(JNIEnv* env, jclass, jclass klass, jstring methodNameValue,
        jstring descriptorValue, jlong location, jboolean enabled, jstring registrationIdValue,
        jobject receiver, jstring callerClassValue, jstring callerMethodValue,
        jstring callerDescriptorValue) {
    if (klass == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Class must not be null");
        return;
    }
    const std::string methodName = JStringToUtf8(env, methodNameValue);
    const std::string descriptor = JStringToUtf8(env, descriptorValue);
    const std::string registrationId = JStringToUtf8(env, registrationIdValue);
    const std::string callerClass = JStringToUtf8(env, callerClassValue);
    const std::string callerMethod = JStringToUtf8(env, callerMethodValue);
    const std::string callerDescriptor = JStringToUtf8(env, callerDescriptorValue);
    if (registrationId.empty()) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Breakpoint registration id must not be empty");
        return;
    }
    jmethodID method = ResolveMethod(env, klass, methodName, descriptor);
    if (method == nullptr) return;
    if (enabled == JNI_TRUE) {
        const jvmtiError error = gJvmti->SetBreakpoint(method, static_cast<jlocation>(location));
        if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_DUPLICATE) {
            ThrowJvmti(env, "SetBreakpoint", error);
            return;
        }
        RememberPersistentBreakpoint(env, klass, method, registrationId, methodName, descriptor,
            static_cast<jlocation>(location), receiver, callerClass, callerMethod, callerDescriptor);
        return;
    }
    const bool remaining = ForgetPersistentBreakpoint(env, registrationId, klass, method,
        static_cast<jlocation>(location));
    if (remaining) return;
    const jvmtiError error = gJvmti->ClearBreakpoint(method, static_cast<jlocation>(location));
    if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_NOT_FOUND) {
        ThrowJvmti(env, "ClearBreakpoint", error);
    }
}

void JNICALL NativeSetBreakpointByName(JNIEnv* env, jclass, jstring classNameValue,
        jstring methodNameValue, jstring descriptorValue, jlong location, jboolean enabled,
        jstring registrationIdValue, jstring callerClassValue, jstring callerMethodValue,
        jstring callerDescriptorValue) {
    std::string className = JStringToUtf8(env, classNameValue);
    std::replace(className.begin(), className.end(), '/', '.');
    const std::string methodName = JStringToUtf8(env, methodNameValue);
    const std::string descriptor = JStringToUtf8(env, descriptorValue);
    const std::string registrationId = JStringToUtf8(env, registrationIdValue);
    const std::string callerClass = JStringToUtf8(env, callerClassValue);
    const std::string callerMethod = JStringToUtf8(env, callerMethodValue);
    const std::string callerDescriptor = JStringToUtf8(env, callerDescriptorValue);
    if (className.empty() || methodName.empty() || descriptor.empty()
            || registrationId.empty()) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "Class, method, descriptor and breakpoint registration id are required");
        return;
    }

    if (enabled != JNI_TRUE) {
        ForgetPendingBreakpoint(registrationId);
        jclass klass = FindLoadedClass(env, className, false);
        if (klass == nullptr) return;
        jmethodID method = ResolveMethod(env, klass, methodName, descriptor, false);
        if (method != nullptr) {
            const bool remaining = ForgetPersistentBreakpoint(env, registrationId, klass,
                method, static_cast<jlocation>(location));
            if (!remaining) {
                const jvmtiError error = gJvmti->ClearBreakpoint(
                    method, static_cast<jlocation>(location));
                if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_NOT_FOUND) {
                    ThrowJvmti(env, "ClearBreakpoint", error);
                }
            }
        }
        env->DeleteLocalRef(klass);
        return;
    }

    const jvmtiError prepareError = gJvmti->SetEventNotificationMode(
        JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, nullptr);
    if (prepareError != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "Enable pending breakpoint class prepare event", prepareError);
        return;
    }
    RememberPendingBreakpoint(registrationId, className, methodName, descriptor,
        static_cast<jlocation>(location), callerClass, callerMethod, callerDescriptor);
    // Recheck after recording to close the ClassPrepare race window.
    jclass klass = FindLoadedClass(env, className, false);
    if (klass != nullptr) {
        InstallPendingDebugRegistrations(env, klass);
        env->DeleteLocalRef(klass);
    }
}

void JNICALL NativeDebuggerConfigure(JNIEnv* env, jclass, jboolean enabled) {
    if (enabled == JNI_TRUE) {
        const jvmtiError error = gJvmti->SetEventNotificationMode(
            JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, nullptr);
        if (error != JVMTI_ERROR_NONE) {
            ThrowJvmti(env, "Enable debugger breakpoint events", error);
            return;
        }
    }
    std::vector<jobject> release;
    {
        std::lock_guard<std::mutex> guard(gDebuggerMutex);
        gDebuggerEnabled = enabled == JNI_TRUE;
        if (!gDebuggerEnabled) {
            for (const std::shared_ptr<DebuggerStop>& stop : gDebuggerStops) {
                if (stop->paused && stop->externallySuspended && stop->thread != nullptr) {
                    gJvmti->ResumeThread(static_cast<jthread>(stop->thread));
                    release.push_back(stop->thread);
                }
                stop->paused = false;
            }
            gDebuggerStops.erase(std::remove_if(gDebuggerStops.begin(), gDebuggerStops.end(),
                [](const std::shared_ptr<DebuggerStop>& stop) {
                    return stop->externallySuspended;
                }), gDebuggerStops.end());
        }
    }
    for (jobject reference : release) env->DeleteGlobalRef(reference);
    if (enabled != JNI_TRUE) RemoveStepOutState(env, nullptr);
    gDebuggerChanged.notify_all();
}

void JNICALL NativeSetDebugEventBreakpoint(JNIEnv* env, jclass, jint kindValue,
        jclass declaredClass, jstring classPatternValue, jstring methodPatternValue,
        jstring descriptorPatternValue, jboolean includeSubtypes, jstring registrationIdValue,
        jboolean enabled) {
    if (kindValue < static_cast<jint>(DebugEventKind::METHOD_ENTRY)
            || kindValue > static_cast<jint>(DebugEventKind::EXCEPTION_THROW)) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Unknown debugger event breakpoint kind");
        return;
    }
    const std::string registrationId = JStringToUtf8(env, registrationIdValue);
    if (registrationId.empty()) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Event breakpoint id must not be empty");
        return;
    }
    const DebugEventKind kind = static_cast<DebugEventKind>(kindValue);
    bool remainingKind = false;
    jobject declaredReference = enabled == JNI_TRUE && declaredClass != nullptr
        ? env->NewGlobalRef(declaredClass) : nullptr;
    {
        std::lock_guard<std::mutex> guard(gDebugEventBreakpointMutex);
        for (auto iterator = gDebugEventBreakpoints.begin();
                iterator != gDebugEventBreakpoints.end();) {
            if (iterator->id != registrationId) { ++iterator; continue; }
            if (iterator->declaredClass != nullptr) env->DeleteGlobalRef(iterator->declaredClass);
            iterator = gDebugEventBreakpoints.erase(iterator);
        }
        if (enabled == JNI_TRUE) {
            DebugEventBreakpoint breakpoint;
            breakpoint.id = registrationId;
            breakpoint.kind = kind;
            breakpoint.declaredClass = declaredReference;
            breakpoint.classPattern = JStringToUtf8(env, classPatternValue);
            breakpoint.methodPattern = JStringToUtf8(env, methodPatternValue);
            breakpoint.descriptorPattern = JStringToUtf8(env, descriptorPatternValue);
            breakpoint.includeSubtypes = includeSubtypes == JNI_TRUE;
            std::replace(breakpoint.classPattern.begin(), breakpoint.classPattern.end(), '/', '.');
            if (breakpoint.classPattern.empty()) breakpoint.classPattern = "*";
            if (breakpoint.methodPattern.empty()) breakpoint.methodPattern = "*";
            if (breakpoint.descriptorPattern.empty()) breakpoint.descriptorPattern = "*";
            gDebugEventBreakpoints.push_back(std::move(breakpoint));
        }
        for (const DebugEventBreakpoint& breakpoint : gDebugEventBreakpoints) {
            if (breakpoint.kind == kind) { remainingKind = true; break; }
        }
    }
    const jvmtiEvent event = kind == DebugEventKind::METHOD_ENTRY ? JVMTI_EVENT_METHOD_ENTRY
        : kind == DebugEventKind::METHOD_EXIT ? JVMTI_EVENT_METHOD_EXIT : JVMTI_EVENT_EXCEPTION;
    if (enabled != JNI_TRUE) {
        const bool callbackOwned = kind == DebugEventKind::METHOD_ENTRY
            ? gJavaMethodEntryDispatchEnabled.load() : kind == DebugEventKind::METHOD_EXIT
                ? gJavaMethodExitDispatchEnabled.load() : gJavaExceptionDispatchEnabled.load();
        if (!remainingKind && !callbackOwned) {
            gJvmti->SetEventNotificationMode(JVMTI_DISABLE, event, nullptr);
        }
        return;
    }
    if (includeSubtypes == JNI_TRUE && declaredClass == nullptr && kind != DebugEventKind::EXCEPTION_THROW) {
        const jvmtiError prepareError = gJvmti->SetEventNotificationMode(
            JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, nullptr);
        if (prepareError != JVMTI_ERROR_NONE) {
            ThrowJvmti(env, "Enable event-breakpoint class prepare event", prepareError);
            return;
        }
    }
    const jvmtiError error = gJvmti->SetEventNotificationMode(JVMTI_ENABLE, event, nullptr);
    if (error != JVMTI_ERROR_NONE) {
        std::lock_guard<std::mutex> guard(gDebugEventBreakpointMutex);
        for (auto iterator = gDebugEventBreakpoints.begin();
                iterator != gDebugEventBreakpoints.end();) {
            if (iterator->id != registrationId) { ++iterator; continue; }
            if (iterator->declaredClass != nullptr) env->DeleteGlobalRef(iterator->declaredClass);
            iterator = gDebugEventBreakpoints.erase(iterator);
        }
        ThrowJvmti(env, "Enable debugger event breakpoint", error);
    }
}

void JNICALL NativeSetStringAllocationHook(JNIEnv* env, jclass,
        jstring registrationIdValue, jstring contentPatternValue,
        jstring creatorClassPatternValue, jstring creatorMethodPatternValue,
        jstring creatorDescriptorPatternValue, jboolean caseSensitive, jboolean enabled) {
    const std::string registrationId = JStringToUtf8(env, registrationIdValue);
    if (registrationId.empty()) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "String allocation registration ID must not be empty");
        return;
    }
    std::lock_guard<std::mutex> guard(gStringAllocationHookMutex);
    for (auto iterator = gStringAllocationHooks.begin();
            iterator != gStringAllocationHooks.end();) {
        if (iterator->id == registrationId) iterator = gStringAllocationHooks.erase(iterator);
        else ++iterator;
    }
    if (enabled == JNI_TRUE) {
        StringAllocationHook hook;
        hook.id = registrationId;
        hook.contentPattern = JStringToUtf8(env, contentPatternValue);
        hook.creatorClassPattern = JStringToUtf8(env, creatorClassPatternValue);
        hook.creatorMethodPattern = JStringToUtf8(env, creatorMethodPatternValue);
        hook.creatorDescriptorPattern = JStringToUtf8(env, creatorDescriptorPatternValue);
        if (hook.contentPattern.empty()) hook.contentPattern = "*";
        if (hook.creatorClassPattern.empty()) hook.creatorClassPattern = "*";
        if (hook.creatorMethodPattern.empty()) hook.creatorMethodPattern = "*";
        if (hook.creatorDescriptorPattern.empty()) hook.creatorDescriptorPattern = "*";
        hook.caseSensitive = caseSensitive == JNI_TRUE;
        gStringAllocationHooks.push_back(std::move(hook));
    }
    gStringAllocationHookCount.store(gStringAllocationHooks.size());
    if (gStringAllocationHooks.empty()) {
        for (jweak reference : gMatchedStringAllocations) env->DeleteWeakGlobalRef(reference);
        gMatchedStringAllocations.clear();
    }
}

void JNICALL NativeDebuggerPauseThread(JNIEnv* env, jclass, jobject requestedThread,
        jstring reasonValue) {
    if (requestedThread == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Thread must not be null");
        return;
    }
    if (IsJvmrtdpServiceThread(env, static_cast<jthread>(requestedThread))) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "JVMRTDP service threads cannot be paused because they carry debugger commands");
        return;
    }
    jthread current = nullptr;
    if (gJvmti->GetCurrentThread(&current) == JVMTI_ERROR_NONE && current != nullptr) {
        const bool same = env->IsSameObject(current, requestedThread) == JNI_TRUE;
        env->DeleteLocalRef(current);
        if (same) {
            ThrowJava(env, "java/lang/IllegalArgumentException",
                "The debugger command thread cannot suspend itself");
            return;
        }
    }
    {
        std::lock_guard<std::mutex> guard(gDebuggerMutex);
        for (const std::shared_ptr<DebuggerStop>& stop : gDebuggerStops) {
            if (stop->paused && stop->thread != nullptr
                    && env->IsSameObject(requestedThread, stop->thread)) {
                ThrowJava(env, "java/lang/IllegalStateException",
                    "Selected debugger thread is already paused");
                return;
            }
        }
    }
    const jvmtiError suspendError = gJvmti->SuspendThread(static_cast<jthread>(requestedThread));
    if (suspendError != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "Suspend selected debugger thread", suspendError);
        return;
    }
    jmethodID method = nullptr;
    jlocation location = 0;
    const jvmtiError frameError = gJvmti->GetFrameLocation(
        static_cast<jthread>(requestedThread), 0, &method, &location);
    if (frameError != JVMTI_ERROR_NONE || method == nullptr) {
        gJvmti->ResumeThread(static_cast<jthread>(requestedThread));
        if (frameError != JVMTI_ERROR_NONE) ThrowJvmti(env, "Get selected thread location", frameError);
        else ThrowJava(env, "java/lang/IllegalStateException",
            "Selected thread has no Java frame to debug");
        return;
    }
    jobject reference = env->NewGlobalRef(requestedThread);
    if (reference == nullptr) {
        gJvmti->ResumeThread(static_cast<jthread>(requestedThread));
        return;
    }
    std::string pauseReason = JStringToUtf8(env, reasonValue);
    if (pauseReason != "live_sample" && pauseReason != "manual_pause") {
        pauseReason = "manual_pause";
    }
    const MethodDetails details = DescribeMethod(env, method);
    std::shared_ptr<DebuggerStop> stop = std::make_shared<DebuggerStop>();
    stop->thread = reference;
    stop->externallySuspended = true;
    stop->reason = pauseReason;
    stop->className = details.className;
    stop->methodName = details.name;
    stop->descriptor = details.descriptor;
    stop->location = location;
    stop->line = LineAtLocation(method, location);
    {
        std::lock_guard<std::mutex> guard(gDebuggerMutex);
        if (!gDebuggerEnabled || gDebuggerStops.size() >= 128) {
            gJvmti->ResumeThread(static_cast<jthread>(requestedThread));
            env->DeleteGlobalRef(reference);
            ThrowJava(env, "java/lang/IllegalStateException",
                gDebuggerEnabled ? "Too many paused debugger threads" : "Debugger is disabled");
            return;
        }
        stop->sequence = ++gDebuggerSequence;
        gDebuggerStops.push_back(stop);
    }
    gDebuggerChanged.notify_all();
}

static jobjectArray NewDebuggerSnapshot(JNIEnv* env, const std::shared_ptr<DebuggerStop>& stop) {
    jclass objectClass = env->FindClass("java/lang/Object");
    if (objectClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(12, objectClass, nullptr);
    env->DeleteLocalRef(objectClass);
    if (result == nullptr) return nullptr;

    if (stop != nullptr && stop->thread != nullptr) {
        jobject thread = env->NewLocalRef(stop->thread);
        if (thread != nullptr) {
            env->SetObjectArrayElement(result, 0, thread);
            env->DeleteLocalRef(thread);
        }
    }
    if (stop != nullptr && stop->returnValue != nullptr) {
        jobject value = env->NewLocalRef(stop->returnValue);
        if (value != nullptr) {
            env->SetObjectArrayElement(result, 10, value);
            env->DeleteLocalRef(value);
        }
    }
    const std::string fields[] = {
        gDebuggerEnabled ? "true" : "false",
        stop != nullptr && stop->paused ? "true" : "false",
        stop == nullptr ? "" : stop->reason,
        stop == nullptr ? "" : stop->className,
        stop == nullptr ? "" : stop->methodName,
        stop == nullptr ? "" : stop->descriptor,
        std::to_string(stop == nullptr ? 0 : stop->location),
        std::to_string(stop == nullptr ? -1 : stop->line),
        std::to_string(stop == nullptr ? gDebuggerSequence : stop->sequence),
    };
    for (jsize index = 0; index < 9; ++index) {
        jstring value = env->NewStringUTF(fields[index].c_str());
        if (value != nullptr) {
            env->SetObjectArrayElement(result, index + 1, value);
            env->DeleteLocalRef(value);
        }
    }
    jstring returnState = env->NewStringUTF(stop == nullptr ? "" : stop->returnState.c_str());
    if (returnState != nullptr) {
        env->SetObjectArrayElement(result, 11, returnState);
        env->DeleteLocalRef(returnState);
    }
    return result;
}

jobjectArray JNICALL NativeDebuggerSnapshot(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> guard(gDebuggerMutex);
    std::shared_ptr<DebuggerStop> stop;
    for (const std::shared_ptr<DebuggerStop>& candidate : gDebuggerStops) {
        if (candidate->paused) { stop = candidate; break; }
    }
    return NewDebuggerSnapshot(env, stop);
}

jobjectArray JNICALL NativeDebuggerSnapshots(JNIEnv* env, jclass) {
    jclass rowClass = env->FindClass("[Ljava/lang/Object;");
    if (rowClass == nullptr) return nullptr;
    std::lock_guard<std::mutex> guard(gDebuggerMutex);
    jsize count = 0;
    for (const std::shared_ptr<DebuggerStop>& stop : gDebuggerStops) if (stop->paused) ++count;
    // Preserve debugger enabled/running state even when no thread is stopped.
    jobjectArray result = env->NewObjectArray(std::max<jsize>(1, count), rowClass, nullptr);
    env->DeleteLocalRef(rowClass);
    if (result == nullptr) return nullptr;
    jsize index = 0;
    for (const std::shared_ptr<DebuggerStop>& stop : gDebuggerStops) {
        if (!stop->paused) continue;
        jobjectArray row = NewDebuggerSnapshot(env, stop);
        if (row != nullptr) {
            env->SetObjectArrayElement(result, index++, row);
            env->DeleteLocalRef(row);
        }
    }
    if (count == 0) {
        jobjectArray row = NewDebuggerSnapshot(env, nullptr);
        if (row != nullptr) {
            env->SetObjectArrayElement(result, 0, row);
            env->DeleteLocalRef(row);
        }
    }
    return result;
}

static void SetDebuggerLocalText(JNIEnv* env, jobjectArray row, jsize index,
        const std::string& value) {
    jstring text = env->NewStringUTF(value.c_str());
    if (text == nullptr) return;
    env->SetObjectArrayElement(row, index, text);
    env->DeleteLocalRef(text);
}

static bool UnboxDebuggerLocal(JNIEnv* env, jobject value, char kind, jvalue* result) {
    if (value == nullptr || result == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "A primitive debugger local requires a non-null boxed value");
        return false;
    }
    const char* method = nullptr;
    const char* signature = nullptr;
    switch (kind) {
    case 'Z': method = "booleanValue"; signature = "()Z"; break;
    case 'B': method = "byteValue"; signature = "()B"; break;
    case 'C': method = "charValue"; signature = "()C"; break;
    case 'S': method = "shortValue"; signature = "()S"; break;
    case 'I': method = "intValue"; signature = "()I"; break;
    case 'J': method = "longValue"; signature = "()J"; break;
    case 'F': method = "floatValue"; signature = "()F"; break;
    case 'D': method = "doubleValue"; signature = "()D"; break;
    default:
        ThrowJava(env, "java/lang/IllegalArgumentException", "Unsupported primitive local descriptor");
        return false;
    }
    jclass valueClass = env->GetObjectClass(value);
    jmethodID converter = valueClass == nullptr ? nullptr
        : env->GetMethodID(valueClass, method, signature);
    if (valueClass != nullptr) env->DeleteLocalRef(valueClass);
    if (converter == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "Replacement value is incompatible with the debugger local descriptor");
        return false;
    }
    switch (kind) {
    case 'Z': result->z = env->CallBooleanMethod(value, converter); break;
    case 'B': result->b = env->CallByteMethod(value, converter); break;
    case 'C': result->c = env->CallCharMethod(value, converter); break;
    case 'S': result->s = env->CallShortMethod(value, converter); break;
    case 'I': result->i = env->CallIntMethod(value, converter); break;
    case 'J': result->j = env->CallLongMethod(value, converter); break;
    case 'F': result->f = env->CallFloatMethod(value, converter); break;
    case 'D': result->d = env->CallDoubleMethod(value, converter); break;
    }
    return !env->ExceptionCheck();
}

void JNICALL NativeDebuggerSetLocal(JNIEnv* env, jclass, jobject requestedThread,
        jint depth, jint slot, jstring descriptorText, jobject value) {
    if (requestedThread == nullptr || depth < 0 || slot < 0 || descriptorText == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "A paused thread, non-negative depth/slot and descriptor are required");
        return;
    }
    const std::string descriptor = JStringToUtf8(env, descriptorText);
    if (descriptor.empty()) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Local descriptor must not be empty");
        return;
    }

    jthread thread = nullptr;
    {
        std::lock_guard<std::mutex> guard(gDebuggerMutex);
        for (const std::shared_ptr<DebuggerStop>& stop : gDebuggerStops) {
            if (stop->paused && stop->thread != nullptr
                    && env->IsSameObject(requestedThread, stop->thread)) {
                thread = static_cast<jthread>(env->NewLocalRef(stop->thread));
                break;
            }
        }
    }
    if (thread == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException",
            "Selected debugger thread is no longer paused");
        return;
    }

    bool suspendedHere = false;
    jint threadState = 0;
    jvmtiError error = gJvmti->GetThreadState(thread, &threadState);
    if (error == JVMTI_ERROR_NONE && (threadState & JVMTI_THREAD_STATE_SUSPENDED) == 0) {
        error = gJvmti->SuspendThread(thread);
        if (error == JVMTI_ERROR_NONE) suspendedHere = true;
    }
    if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_THREAD_SUSPENDED) {
        env->DeleteLocalRef(thread);
        ThrowJvmti(env, "Suspend debugger thread for local write", error);
        return;
    }

    const char kind = descriptor[0];
    jvalue primitive{};
    if (kind == 'L' || kind == '[') {
        error = gJvmti->SetLocalObject(thread, depth, slot, value);
    } else if (!UnboxDebuggerLocal(env, value, kind, &primitive)) {
        error = JVMTI_ERROR_NONE;
    } else {
        switch (kind) {
        case 'Z': error = gJvmti->SetLocalInt(thread, depth, slot, primitive.z); break;
        case 'B': error = gJvmti->SetLocalInt(thread, depth, slot, primitive.b); break;
        case 'C': error = gJvmti->SetLocalInt(thread, depth, slot, primitive.c); break;
        case 'S': error = gJvmti->SetLocalInt(thread, depth, slot, primitive.s); break;
        case 'I': error = gJvmti->SetLocalInt(thread, depth, slot, primitive.i); break;
        case 'J': error = gJvmti->SetLocalLong(thread, depth, slot, primitive.j); break;
        case 'F': error = gJvmti->SetLocalFloat(thread, depth, slot, primitive.f); break;
        case 'D': error = gJvmti->SetLocalDouble(thread, depth, slot, primitive.d); break;
        default: error = JVMTI_ERROR_TYPE_MISMATCH; break;
        }
    }
    const jvmtiError resumeError = suspendedHere ? gJvmti->ResumeThread(thread) : JVMTI_ERROR_NONE;
    env->DeleteLocalRef(thread);
    if (!env->ExceptionCheck() && error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "Set debugger local", error);
    } else if (!env->ExceptionCheck() && resumeError != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "Resume debugger thread after local write", resumeError);
    }
}

void JNICALL NativeDebuggerForceReturn(JNIEnv* env, jclass, jobject requestedThread,
        jobject value) {
    if (requestedThread == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "A paused debugger thread is required");
        return;
    }
    jthread thread = nullptr;
    std::string stopReason;
    {
        std::lock_guard<std::mutex> guard(gDebuggerMutex);
        for (const std::shared_ptr<DebuggerStop>& stop : gDebuggerStops) {
            if (stop->paused && stop->thread != nullptr
                    && env->IsSameObject(requestedThread, stop->thread)) {
                thread = static_cast<jthread>(env->NewLocalRef(stop->thread));
                stopReason = stop->reason;
                break;
            }
        }
    }
    if (thread == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException", "Selected debugger thread is no longer paused");
        return;
    }
    if (stopReason == "method_exit" || stopReason == "method_exit_exception") {
        env->DeleteLocalRef(thread);
        ThrowJava(env, "java/lang/IllegalStateException",
            "A METHOD_EXIT event observes a completed return. Force the return from method entry, a bytecode breakpoint, or a step stop.");
        return;
    }
    bool suspendedHere = false;
    jint state = 0;
    jvmtiError error = gJvmti->GetThreadState(thread, &state);
    if (error == JVMTI_ERROR_NONE && (state & JVMTI_THREAD_STATE_SUSPENDED) == 0) {
        error = gJvmti->SuspendThread(thread);
        if (error == JVMTI_ERROR_NONE) suspendedHere = true;
    }
    if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_THREAD_SUSPENDED) {
        env->DeleteLocalRef(thread);
        ThrowJvmti(env, "Suspend debugger thread for forced return", error);
        return;
    }
    jmethodID method = nullptr;
    jlocation location = 0;
    error = gJvmti->GetFrameLocation(thread, 0, &method, &location);
    MethodDetails details;
    jint modifiers = 0;
    if (error == JVMTI_ERROR_NONE && method != nullptr) {
        details = DescribeMethod(env, method);
        error = gJvmti->GetMethodModifiers(method, &modifiers);
    }
    if (error == JVMTI_ERROR_NONE && (modifiers & kAccNative) != 0) {
        error = JVMTI_ERROR_NATIVE_METHOD;
    }
    if (error == JVMTI_ERROR_NONE) {
        const std::size_t close = details.descriptor.rfind(')');
        const char kind = close == std::string::npos || close + 1 >= details.descriptor.size()
            ? '\0' : details.descriptor[close + 1];
        jvalue primitive{};
        if (kind == 'V') error = gJvmti->ForceEarlyReturnVoid(thread);
        else if (kind == 'L' || kind == '[') error = gJvmti->ForceEarlyReturnObject(thread, value);
        else if (!UnboxDebuggerLocal(env, value, kind, &primitive)) error = JVMTI_ERROR_NONE;
        else if (kind == 'J') error = gJvmti->ForceEarlyReturnLong(thread, primitive.j);
        else if (kind == 'F') error = gJvmti->ForceEarlyReturnFloat(thread, primitive.f);
        else if (kind == 'D') error = gJvmti->ForceEarlyReturnDouble(thread, primitive.d);
        else {
            const jint intValue = kind == 'Z' ? static_cast<jint>(primitive.z)
                : kind == 'B' ? static_cast<jint>(primitive.b)
                : kind == 'C' ? static_cast<jint>(primitive.c)
                : kind == 'S' ? static_cast<jint>(primitive.s) : primitive.i;
            error = gJvmti->ForceEarlyReturnInt(thread, intValue);
        }
    }
    const jvmtiError resumeError = suspendedHere ? gJvmti->ResumeThread(thread) : JVMTI_ERROR_NONE;
    env->DeleteLocalRef(thread);
    if (!env->ExceptionCheck() && error == JVMTI_ERROR_NATIVE_METHOD) {
        ThrowJava(env, "java/lang/IllegalStateException",
            "JVMTI cannot force an early return from a native frame; stop in its Java caller instead");
    } else if (!env->ExceptionCheck() && error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "Force early return", error);
    } else if (!env->ExceptionCheck() && resumeError != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "Resume debugger thread after forced return", resumeError);
    }
}

jobjectArray JNICALL NativeDebuggerLocals(JNIEnv* env, jclass, jobject requestedThread, jint depth) {
    if (requestedThread == nullptr || depth < 0) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "A paused debugger thread and a non-negative frame depth are required");
        return nullptr;
    }

    jthread thread = nullptr;
    {
        std::lock_guard<std::mutex> guard(gDebuggerMutex);
        for (const std::shared_ptr<DebuggerStop>& stop : gDebuggerStops) {
            if (stop->paused && stop->thread != nullptr
                    && env->IsSameObject(requestedThread, stop->thread)) {
                thread = static_cast<jthread>(env->NewLocalRef(stop->thread));
                break;
            }
        }
    }
    if (thread == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException",
            "Selected debugger thread is no longer paused");
        return nullptr;
    }

    bool suspendedHere = false;
    jint threadState = 0;
    jvmtiError error = gJvmti->GetThreadState(thread, &threadState);
    if (error == JVMTI_ERROR_NONE && (threadState & JVMTI_THREAD_STATE_SUSPENDED) == 0) {
        error = gJvmti->SuspendThread(thread);
        if (error == JVMTI_ERROR_NONE) suspendedHere = true;
    }
    if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_THREAD_SUSPENDED) {
        env->DeleteLocalRef(thread);
        ThrowJvmti(env, "Suspend debugger thread for local access", error);
        return nullptr;
    }

    jmethodID method = nullptr;
    jlocation location = 0;
    error = gJvmti->GetFrameLocation(thread, depth, &method, &location);
    if (error != JVMTI_ERROR_NONE) {
        if (suspendedHere) gJvmti->ResumeThread(thread);
        env->DeleteLocalRef(thread);
        ThrowJvmti(env, "GetFrameLocation for debugger locals", error);
        return nullptr;
    }

    jint count = 0;
    jvmtiLocalVariableEntry* table = nullptr;
    error = gJvmti->GetLocalVariableTable(method, &count, &table);
    const bool inferSlots = error == JVMTI_ERROR_ABSENT_INFORMATION;
    if (error != JVMTI_ERROR_NONE && !inferSlots) {
        if (suspendedHere) gJvmti->ResumeThread(thread);
        env->DeleteLocalRef(thread);
        ThrowJvmti(env, "GetLocalVariableTable for debugger locals", error);
        return nullptr;
    }

    if (inferSlots) {
        jint maxLocals = 0;
        error = gJvmti->GetMaxLocals(method, &maxLocals);
        if (error != JVMTI_ERROR_NONE) {
            if (suspendedHere) gJvmti->ResumeThread(thread);
            env->DeleteLocalRef(thread);
            ThrowJvmti(env, "GetMaxLocals for inferred debugger locals", error);
            return nullptr;
        }
        jclass rowClass = env->FindClass("[Ljava/lang/Object;");
        jclass objectClass = env->FindClass("java/lang/Object");
        jobjectArray result = rowClass == nullptr || objectClass == nullptr ? nullptr
            : env->NewObjectArray(maxLocals, rowClass, nullptr);
        if (rowClass != nullptr) env->DeleteLocalRef(rowClass);
        if (result != nullptr) {
            for (jint slot = 0; slot < maxLocals; ++slot) {
                jobjectArray row = env->NewObjectArray(8, objectClass, nullptr);
                if (row == nullptr) break;
                SetDebuggerLocalText(env, row, 0, "slot" + std::to_string(slot));
                SetDebuggerLocalText(env, row, 2, "inferred:maxLocals:no-LVT");
                SetDebuggerLocalText(env, row, 3, std::to_string(slot));
                SetDebuggerLocalText(env, row, 4, "0");
                SetDebuggerLocalText(env, row, 5, "-1");

                char kind = '\0';
                jvalue value{};
                jvmtiError valueError = gJvmti->GetLocalObject(thread, depth, slot, &value.l);
                if (valueError == JVMTI_ERROR_NONE) kind = 'L';
                if (kind == '\0') {
                    jint integer = 0;
                    valueError = gJvmti->GetLocalInt(thread, depth, slot, &integer);
                    if (valueError == JVMTI_ERROR_NONE) { kind = 'I'; value.i = integer; }
                }
                if (kind == '\0') {
                    valueError = gJvmti->GetLocalLong(thread, depth, slot, &value.j);
                    if (valueError == JVMTI_ERROR_NONE) kind = 'J';
                }
                if (kind == '\0') {
                    valueError = gJvmti->GetLocalFloat(thread, depth, slot, &value.f);
                    if (valueError == JVMTI_ERROR_NONE) kind = 'F';
                }
                if (kind == '\0') {
                    valueError = gJvmti->GetLocalDouble(thread, depth, slot, &value.d);
                    if (valueError == JVMTI_ERROR_NONE) kind = 'D';
                }
                const std::string descriptor = kind == '\0' ? "?" :
                    kind == 'L' ? "Ljava/lang/Object;" : std::string(1, kind);
                SetDebuggerLocalText(env, row, 1, descriptor);
                if (kind != '\0') {
                    jobject boxed = BoxValue(env, kind, value);
                    if (boxed != nullptr) {
                        env->SetObjectArrayElement(row, 6, boxed);
                        env->DeleteLocalRef(boxed);
                    }
                } else {
                    SetDebuggerLocalText(env, row, 7,
                        "not live or continuation slot at current BCI");
                }
                env->SetObjectArrayElement(result, slot, row);
                env->DeleteLocalRef(row);
            }
        }
        if (objectClass != nullptr) env->DeleteLocalRef(objectClass);
        const jvmtiError resumeError = suspendedHere
            ? gJvmti->ResumeThread(thread) : JVMTI_ERROR_NONE;
        env->DeleteLocalRef(thread);
        if (resumeError != JVMTI_ERROR_NONE) {
            ThrowJvmti(env, "Resume debugger thread after inferred local access", resumeError);
        }
        return result;
    }

    std::vector<jint> active;
    active.reserve(static_cast<std::size_t>(count));
    for (jint index = 0; index < count; ++index) {
        const jvmtiLocalVariableEntry& entry = table[index];
        if (entry.length <= 0) continue;
        const jlocation end = entry.start_location <= (std::numeric_limits<jlocation>::max)() - entry.length
            ? entry.start_location + entry.length
            : (std::numeric_limits<jlocation>::max)();
        if (location >= entry.start_location && location < end) active.push_back(index);
    }

    jclass rowClass = env->FindClass("[Ljava/lang/Object;");
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = rowClass == nullptr || objectClass == nullptr ? nullptr
        : env->NewObjectArray(static_cast<jsize>(active.size()), rowClass, nullptr);
    if (rowClass != nullptr) env->DeleteLocalRef(rowClass);
    if (result != nullptr) {
        for (jsize resultIndex = 0; resultIndex < static_cast<jsize>(active.size()); ++resultIndex) {
            const jvmtiLocalVariableEntry& entry = table[active[resultIndex]];
            jobjectArray row = env->NewObjectArray(8, objectClass, nullptr);
            if (row == nullptr) break;
            SetDebuggerLocalText(env, row, 0, entry.name == nullptr ? "" : entry.name);
            SetDebuggerLocalText(env, row, 1, entry.signature == nullptr ? "" : entry.signature);
            SetDebuggerLocalText(env, row, 2,
                entry.generic_signature == nullptr ? "" : entry.generic_signature);
            SetDebuggerLocalText(env, row, 3, std::to_string(entry.slot));
            SetDebuggerLocalText(env, row, 4, std::to_string(entry.start_location));
            SetDebuggerLocalText(env, row, 5, std::to_string(entry.length));

            const char kind = entry.signature == nullptr || entry.signature[0] == '\0'
                ? '\0' : entry.signature[0];
            jvalue value{};
            jvmtiError valueError = JVMTI_ERROR_TYPE_MISMATCH;
            switch (kind) {
            case 'Z': case 'B': case 'C': case 'S': case 'I': {
                jint integer = 0;
                valueError = gJvmti->GetLocalInt(thread, depth, entry.slot, &integer);
                if (kind == 'Z') value.z = static_cast<jboolean>(integer);
                else if (kind == 'B') value.b = static_cast<jbyte>(integer);
                else if (kind == 'C') value.c = static_cast<jchar>(integer);
                else if (kind == 'S') value.s = static_cast<jshort>(integer);
                else value.i = integer;
                break;
            }
            case 'J': valueError = gJvmti->GetLocalLong(thread, depth, entry.slot, &value.j); break;
            case 'F': valueError = gJvmti->GetLocalFloat(thread, depth, entry.slot, &value.f); break;
            case 'D': valueError = gJvmti->GetLocalDouble(thread, depth, entry.slot, &value.d); break;
            case 'L': case '[':
                valueError = gJvmti->GetLocalObject(thread, depth, entry.slot, &value.l);
                break;
            default: break;
            }
            if (valueError == JVMTI_ERROR_NONE) {
                jobject boxed = BoxValue(env, kind, value);
                if (boxed != nullptr) {
                    env->SetObjectArrayElement(row, 6, boxed);
                    env->DeleteLocalRef(boxed);
                }
            } else {
                SetDebuggerLocalText(env, row, 7, JvmtiErrorText(valueError));
            }
            env->SetObjectArrayElement(result, resultIndex, row);
            env->DeleteLocalRef(row);
        }
    }
    if (objectClass != nullptr) env->DeleteLocalRef(objectClass);
    for (jint index = 0; index < count; ++index) {
        if (table[index].name != nullptr) {
            gJvmti->Deallocate(reinterpret_cast<unsigned char*>(table[index].name));
        }
        if (table[index].signature != nullptr) {
            gJvmti->Deallocate(reinterpret_cast<unsigned char*>(table[index].signature));
        }
        if (table[index].generic_signature != nullptr) {
            gJvmti->Deallocate(reinterpret_cast<unsigned char*>(table[index].generic_signature));
        }
    }
    if (table != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(table));
    const jvmtiError resumeError = suspendedHere ? gJvmti->ResumeThread(thread) : JVMTI_ERROR_NONE;
    env->DeleteLocalRef(thread);
    if (resumeError != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "Resume debugger thread after local access", resumeError);
    }
    return result;
}

static bool ResumeDebuggerStop(JNIEnv* env, const std::shared_ptr<DebuggerStop>& stop, jint action) {
    if (stop == nullptr || !stop->paused || stop->thread == nullptr) return false;
    if (action < 0 || action > 2) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "Debugger action must be 0 (continue), 1 (step into), or 2 (step out)");
        return false;
    }
    RemoveStepOutState(env, static_cast<jthread>(stop->thread));
    jvmtiError error = gJvmti->SetEventNotificationMode(
        action == 1 ? JVMTI_ENABLE : JVMTI_DISABLE,
        JVMTI_EVENT_SINGLE_STEP, static_cast<jthread>(stop->thread));
    if (error != JVMTI_ERROR_NONE && !(action == 0 && error == JVMTI_ERROR_NOT_AVAILABLE)) {
        ThrowJvmti(env, action == 1 ? "Enable single-step" : "Disable single-step", error);
        return false;
    }
    if (action == 2) {
        bool suspendedForNotify = false;
        jint state = 0;
        error = gJvmti->GetThreadState(static_cast<jthread>(stop->thread), &state);
        if (error == JVMTI_ERROR_NONE && (state & JVMTI_THREAD_STATE_SUSPENDED) == 0) {
            error = gJvmti->SuspendThread(static_cast<jthread>(stop->thread));
            suspendedForNotify = error == JVMTI_ERROR_NONE;
        }
        if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_THREAD_SUSPENDED) {
            ThrowJvmti(env, "Suspend thread for step out", error);
            return false;
        }
        jint frameCount = 0;
        error = gJvmti->GetFrameCount(static_cast<jthread>(stop->thread), &frameCount);
        if (error == JVMTI_ERROR_NONE && frameCount < 2) {
            if (suspendedForNotify) gJvmti->ResumeThread(static_cast<jthread>(stop->thread));
            ThrowJava(env, "java/lang/IllegalStateException",
                "Cannot step out because the selected frame has no caller");
            return false;
        }
        if (error != JVMTI_ERROR_NONE) {
            if (suspendedForNotify) gJvmti->ResumeThread(static_cast<jthread>(stop->thread));
            ThrowJvmti(env, "Read frame count for step out", error);
            return false;
        }
        error = gJvmti->NotifyFramePop(static_cast<jthread>(stop->thread), 0);
        if (error == JVMTI_ERROR_NONE) {
            error = gJvmti->SetEventNotificationMode(JVMTI_ENABLE,
                JVMTI_EVENT_FRAME_POP, static_cast<jthread>(stop->thread));
        }
        if (suspendedForNotify) {
            const jvmtiError resume = gJvmti->ResumeThread(static_cast<jthread>(stop->thread));
            if (error == JVMTI_ERROR_NONE) error = resume;
        }
        if (error != JVMTI_ERROR_NONE) {
            ThrowJvmti(env, "Enable step-out frame notification", error);
            return false;
        }
        jobject reference = env->NewGlobalRef(stop->thread);
        if (reference == nullptr) return false;
        std::lock_guard<std::mutex> stepGuard(gStepOutMutex);
        gStepOutAwaitingPop.push_back(reference);
    }
    if (stop->externallySuspended) {
        error = gJvmti->ResumeThread(static_cast<jthread>(stop->thread));
        if (error != JVMTI_ERROR_NONE) {
            ThrowJvmti(env, "Resume selected debugger thread", error);
            return false;
        }
    }
    stop->paused = false;
    return true;
}

static void ReleaseResumedExternalStops(JNIEnv* env) {
    std::vector<jobject> release;
    for (auto iterator = gDebuggerStops.begin(); iterator != gDebuggerStops.end();) {
        const std::shared_ptr<DebuggerStop>& stop = *iterator;
        if (!stop->paused && stop->externallySuspended) {
            release.push_back(stop->thread);
            iterator = gDebuggerStops.erase(iterator);
        } else ++iterator;
    }
    for (jobject reference : release) env->DeleteGlobalRef(reference);
}

void JNICALL NativeDebuggerResume(JNIEnv* env, jclass, jint action) {
    std::lock_guard<std::mutex> guard(gDebuggerMutex);
    for (const std::shared_ptr<DebuggerStop>& stop : gDebuggerStops) {
        if (ResumeDebuggerStop(env, stop, action)) {
            ReleaseResumedExternalStops(env);
            gDebuggerChanged.notify_all();
            return;
        }
        if (env->ExceptionCheck()) return;
    }
    ThrowJava(env, "java/lang/IllegalStateException", "No debugger thread is paused");
}

void JNICALL NativeDebuggerResumeThread(JNIEnv* env, jclass, jobject thread, jint action) {
    std::lock_guard<std::mutex> guard(gDebuggerMutex);
    bool resumed = false;
    for (const std::shared_ptr<DebuggerStop>& stop : gDebuggerStops) {
        if (thread != nullptr && !env->IsSameObject(thread, stop->thread)) continue;
        if (ResumeDebuggerStop(env, stop, action)) resumed = true;
        if (env->ExceptionCheck() || (thread != nullptr && resumed)) break;
    }
    if (env->ExceptionCheck()) return;
    if (!resumed) {
        ThrowJava(env, "java/lang/IllegalStateException", "Selected debugger thread is not paused");
        return;
    }
    ReleaseResumedExternalStops(env);
    gDebuggerChanged.notify_all();
}

void JNICALL NativeSetFieldWatch(JNIEnv* env, jclass, jclass klass, jstring fieldNameValue,
        jstring descriptorValue, jboolean modification, jboolean enabled,
        jstring registrationIdValue, jobject receiver) {
    if (klass == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Class must not be null");
        return;
    }
    const std::string fieldName = JStringToUtf8(env, fieldNameValue);
    const std::string descriptor = JStringToUtf8(env, descriptorValue);
    const std::string registrationId = JStringToUtf8(env, registrationIdValue);
    if (registrationId.empty()) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Field watch registration id must not be empty");
        return;
    }
    jfieldID field = ResolveField(env, klass, fieldName, descriptor);
    if (field == nullptr) return;
    const bool isModification = modification == JNI_TRUE;
    if (enabled != JNI_TRUE) {
        const bool remaining = ForgetPersistentFieldWatch(
            env, registrationId, klass, field, isModification);
        if (remaining) return;
        const jvmtiError clearError = isModification
            ? gJvmti->ClearFieldModificationWatch(klass, field)
            : gJvmti->ClearFieldAccessWatch(klass, field);
        if (clearError != JVMTI_ERROR_NONE && clearError != JVMTI_ERROR_NOT_FOUND) {
            ThrowJvmti(env, isModification
                ? "ClearFieldModificationWatch" : "ClearFieldAccessWatch", clearError);
        }
        return;
    }
    jvmtiError error;
    const char* operation;
    if (isModification) {
        error = gJvmti->SetFieldModificationWatch(klass, field);
        operation = "SetFieldModificationWatch";
    } else {
        error = gJvmti->SetFieldAccessWatch(klass, field);
        operation = "SetFieldAccessWatch";
    }
    if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_DUPLICATE) {
        ThrowJvmti(env, operation, error);
        return;
    }
    RememberPersistentFieldWatch(env, registrationId, klass, field,
        fieldName, descriptor, isModification, receiver);
    {
        const jvmtiEvent event = isModification
            ? JVMTI_EVENT_FIELD_MODIFICATION : JVMTI_EVENT_FIELD_ACCESS;
        const jvmtiError eventError = gJvmti->SetEventNotificationMode(JVMTI_ENABLE, event, nullptr);
        if (eventError != JVMTI_ERROR_NONE) {
            ThrowJvmti(env, "Enable field watch debugger event", eventError);
            return;
        }
    }
}

void JNICALL NativeSetFieldWatchByName(JNIEnv* env, jclass, jstring classNameValue,
        jstring fieldNameValue, jstring descriptorValue, jboolean modification,
        jboolean enabled, jstring registrationIdValue) {
    std::string className = JStringToUtf8(env, classNameValue);
    std::replace(className.begin(), className.end(), '/', '.');
    const std::string fieldName = JStringToUtf8(env, fieldNameValue);
    const std::string descriptor = JStringToUtf8(env, descriptorValue);
    const std::string registrationId = JStringToUtf8(env, registrationIdValue);
    const bool isModification = modification == JNI_TRUE;
    if (className.empty() || fieldName.empty() || descriptor.empty()
            || registrationId.empty()) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "Class, field, descriptor and field-watch registration id are required");
        return;
    }

    if (enabled != JNI_TRUE) {
        ForgetPendingFieldWatch(registrationId);
        jclass klass = FindLoadedClass(env, className, false);
        if (klass == nullptr) return;
        jfieldID field = ResolveField(env, klass, fieldName, descriptor, false);
        if (field != nullptr) {
            const bool remaining = ForgetPersistentFieldWatch(
                env, registrationId, klass, field, isModification);
            if (!remaining) {
                const jvmtiError error = isModification
                    ? gJvmti->ClearFieldModificationWatch(klass, field)
                    : gJvmti->ClearFieldAccessWatch(klass, field);
                if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_NOT_FOUND) {
                    ThrowJvmti(env, isModification
                        ? "ClearFieldModificationWatch" : "ClearFieldAccessWatch", error);
                }
            }
        }
        env->DeleteLocalRef(klass);
        return;
    }

    const jvmtiError prepareError = gJvmti->SetEventNotificationMode(
        JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, nullptr);
    if (prepareError != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "Enable pending field watch class prepare event", prepareError);
        return;
    }
    const jvmtiEvent fieldEvent = isModification
        ? JVMTI_EVENT_FIELD_MODIFICATION : JVMTI_EVENT_FIELD_ACCESS;
    const jvmtiError eventError = gJvmti->SetEventNotificationMode(
        JVMTI_ENABLE, fieldEvent, nullptr);
    if (eventError != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "Enable pending field watch debugger event", eventError);
        return;
    }
    RememberPendingFieldWatch(registrationId, className, fieldName, descriptor, isModification);
    // Recheck after recording to close the ClassPrepare race window.
    jclass klass = FindLoadedClass(env, className, false);
    if (klass != nullptr) {
        InstallPendingDebugRegistrations(env, klass);
        env->DeleteLocalRef(klass);
    }
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
    jvmtiError error = gJvmti->RetransformClasses(1, &klass);
    if (error == JVMTI_ERROR_NONE) error = ReapplyPersistentBreakpoints(env, klass);
    ThrowJvmti(env, "RetransformClasses", error);
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
    jvmtiError error = gJvmti->RedefineClasses(1, &definition);
    if (error == JVMTI_ERROR_NONE) error = ReapplyPersistentBreakpoints(env, klass);
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

bool SelectCapability(jvmtiCapabilities& capabilities, const std::string& name) {
#define JVMRTDP_SELECT_CAPABILITY(field) if (name == #field) { capabilities.field = 1; return true; }
    JVMRTDP_SELECT_CAPABILITY(can_tag_objects)
    JVMRTDP_SELECT_CAPABILITY(can_generate_field_modification_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_field_access_events)
    JVMRTDP_SELECT_CAPABILITY(can_get_bytecodes)
    JVMRTDP_SELECT_CAPABILITY(can_get_synthetic_attribute)
    JVMRTDP_SELECT_CAPABILITY(can_get_owned_monitor_info)
    JVMRTDP_SELECT_CAPABILITY(can_get_current_contended_monitor)
    JVMRTDP_SELECT_CAPABILITY(can_get_monitor_info)
    JVMRTDP_SELECT_CAPABILITY(can_pop_frame)
    JVMRTDP_SELECT_CAPABILITY(can_redefine_classes)
    JVMRTDP_SELECT_CAPABILITY(can_signal_thread)
    JVMRTDP_SELECT_CAPABILITY(can_get_source_file_name)
    JVMRTDP_SELECT_CAPABILITY(can_get_line_numbers)
    JVMRTDP_SELECT_CAPABILITY(can_get_source_debug_extension)
    JVMRTDP_SELECT_CAPABILITY(can_access_local_variables)
    JVMRTDP_SELECT_CAPABILITY(can_maintain_original_method_order)
    JVMRTDP_SELECT_CAPABILITY(can_generate_single_step_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_frame_pop_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_breakpoint_events)
    JVMRTDP_SELECT_CAPABILITY(can_suspend)
    JVMRTDP_SELECT_CAPABILITY(can_redefine_any_class)
    JVMRTDP_SELECT_CAPABILITY(can_get_current_thread_cpu_time)
    JVMRTDP_SELECT_CAPABILITY(can_get_thread_cpu_time)
    JVMRTDP_SELECT_CAPABILITY(can_generate_method_entry_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_method_exit_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_all_class_hook_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_compiled_method_load_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_exception_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_monitor_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_vm_object_alloc_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_native_method_bind_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_garbage_collection_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_object_free_events)
    JVMRTDP_SELECT_CAPABILITY(can_force_early_return)
    JVMRTDP_SELECT_CAPABILITY(can_get_owned_monitor_stack_depth_info)
    JVMRTDP_SELECT_CAPABILITY(can_get_constant_pool)
    JVMRTDP_SELECT_CAPABILITY(can_set_native_method_prefix)
    JVMRTDP_SELECT_CAPABILITY(can_retransform_classes)
    JVMRTDP_SELECT_CAPABILITY(can_retransform_any_class)
    JVMRTDP_SELECT_CAPABILITY(can_generate_resource_exhaustion_heap_events)
    JVMRTDP_SELECT_CAPABILITY(can_generate_resource_exhaustion_threads_events)
#undef JVMRTDP_SELECT_CAPABILITY
    return false;
}

void JNICALL NativeChangeCapabilities(
        JNIEnv* env, jclass, jobjectArray capabilityNames, jboolean add) {
    if (capabilityNames == nullptr || env->GetArrayLength(capabilityNames) == 0) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "At least one capability is required");
        return;
    }
    jvmtiCapabilities requested{};
    const jsize count = env->GetArrayLength(capabilityNames);
    for (jsize index = 0; index < count; ++index) {
        jstring value = static_cast<jstring>(env->GetObjectArrayElement(capabilityNames, index));
        const std::string name = JStringToUtf8(env, value);
        if (value != nullptr) env->DeleteLocalRef(value);
        if (!SelectCapability(requested, name)) {
            ThrowJava(env, "java/lang/IllegalArgumentException", "Unknown JVMTI capability: " + name);
            return;
        }
    }
    const jvmtiError error = add == JNI_TRUE
        ? gJvmti->AddCapabilities(&requested)
        : gJvmti->RelinquishCapabilities(&requested);
    if (error != JVMTI_ERROR_NONE) {
        std::string message = std::string(add == JNI_TRUE ? "AddCapabilities" : "RelinquishCapabilities")
            + " failed in JVMTI phase " + JvmtiPhaseText() + ": " + JvmtiErrorText(error);
        if (add == JNI_TRUE && error == JVMTI_ERROR_NOT_AVAILABLE) {
            message += ". The requested capability is not potential in this phase; "
                "load JVMRTDP with -agentpath during VM startup for OnLoad-only capabilities";
        }
        ThrowJava(env, "java/lang/UnsupportedOperationException", message);
        return;
    }
    jvmtiCapabilities current{};
    if (gJvmti->GetCapabilities(&current) == JVMTI_ERROR_NONE) {
        gCanRetransform = current.can_retransform_classes != 0;
    }
}

jint JNICALL NativePhase(JNIEnv* env, jclass) {
    jvmtiPhase phase{};
    const jvmtiError error = gJvmti->GetPhase(&phase);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetPhase", error);
        return 0;
    }
    return static_cast<jint>(phase);
}

jlong JNICALL NativeTime(JNIEnv* env, jclass) {
    jlong value = 0;
    const jvmtiError error = gJvmti->GetTime(&value);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetTime", error);
        return 0;
    }
    return value;
}

jint JNICALL NativeAvailableProcessors(JNIEnv* env, jclass) {
    jint value = 0;
    const jvmtiError error = gJvmti->GetAvailableProcessors(&value);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetAvailableProcessors", error);
        return 0;
    }
    return value;
}

jint JNICALL NativeLocationFormat(JNIEnv* env, jclass) {
    jvmtiJlocationFormat format{};
    const jvmtiError error = gJvmti->GetJLocationFormat(&format);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetJLocationFormat", error);
        return 0;
    }
    return static_cast<jint>(format);
}

std::string ObjectTypeName(JNIEnv* env, jobject object) {
    if (object == nullptr) return "";
    jclass type = env->GetObjectClass(object);
    const std::string result = type == nullptr ? "" : BinaryClassName(type);
    if (type != nullptr) env->DeleteLocalRef(type);
    return result;
}

jobjectArray ClassArray(JNIEnv* env, jint count, jclass* classes) {
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

jobjectArray JNICALL NativeClassInfo(JNIEnv* env, jclass, jclass klass) {
    if (klass == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Class must not be null");
        return nullptr;
    }
    char* signature = nullptr;
    char* generic = nullptr;
    char* source = nullptr;
    jint status = 0;
    jint modifiers = 0;
    jint minorVersion = -1;
    jint majorVersion = -1;
    jboolean interfaceType = JNI_FALSE;
    jboolean arrayType = JNI_FALSE;
    jboolean modifiable = JNI_FALSE;
    jvmtiError error = gJvmti->GetClassSignature(klass, &signature, &generic);
    if (error == JVMTI_ERROR_NONE) error = gJvmti->GetClassStatus(klass, &status);
    if (error == JVMTI_ERROR_NONE) error = gJvmti->GetClassModifiers(klass, &modifiers);
    if (error == JVMTI_ERROR_NONE) error = gJvmti->IsInterface(klass, &interfaceType);
    if (error == JVMTI_ERROR_NONE) error = gJvmti->IsArrayClass(klass, &arrayType);
    if (error == JVMTI_ERROR_NONE) error = gJvmti->IsModifiableClass(klass, &modifiable);
    if (error == JVMTI_ERROR_NONE) {
        const jvmtiError versionError = gJvmti->GetClassVersionNumbers(klass, &minorVersion, &majorVersion);
        if (versionError != JVMTI_ERROR_NONE && versionError != JVMTI_ERROR_ABSENT_INFORMATION) {
            error = versionError;
        }
    }
    const jvmtiError sourceError = gJvmti->GetSourceFileName(klass, &source);
    if (error == JVMTI_ERROR_NONE && sourceError != JVMTI_ERROR_NONE
            && sourceError != JVMTI_ERROR_ABSENT_INFORMATION
            && sourceError != JVMTI_ERROR_MUST_POSSESS_CAPABILITY) error = sourceError;
    if (error != JVMTI_ERROR_NONE) ThrowJvmti(env, "Get class information", error);
    std::vector<std::string> values = {
        signature == nullptr ? "" : signature,
        generic == nullptr ? "" : generic,
        source == nullptr ? "" : source,
        std::to_string(status), std::to_string(modifiers),
        interfaceType == JNI_TRUE ? "true" : "false",
        arrayType == JNI_TRUE ? "true" : "false",
        modifiable == JNI_TRUE ? "true" : "false",
        std::to_string(minorVersion), std::to_string(majorVersion)
    };
    if (signature != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
    if (generic != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
    if (source != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(source));
    return env->ExceptionCheck() ? nullptr : NewStringArray(env, values);
}

jobjectArray JNICALL NativeImplementedInterfaces(JNIEnv* env, jclass, jclass klass) {
    jint count = 0;
    jclass* interfaces = nullptr;
    const jvmtiError error = gJvmti->GetImplementedInterfaces(klass, &count, &interfaces);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetImplementedInterfaces", error);
        return nullptr;
    }
    return ClassArray(env, count, interfaces);
}

jobject JNICALL NativeClassLoader(JNIEnv* env, jclass, jclass klass) {
    jobject loader = nullptr;
    const jvmtiError error = gJvmti->GetClassLoader(klass, &loader);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetClassLoader", error);
        return nullptr;
    }
    return loader;
}

jobjectArray JNICALL NativeClassLoaderClasses(JNIEnv* env, jclass, jobject loader) {
    jint count = 0;
    jclass* classes = nullptr;
    const jvmtiError error = gJvmti->GetClassLoaderClasses(loader, &count, &classes);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetClassLoaderClasses", error);
        return nullptr;
    }
    return ClassArray(env, count, classes);
}

jobjectArray JNICALL NativeMethodInfo(JNIEnv* env, jclass, jclass klass,
        jstring nameValue, jstring descriptorValue) {
    const std::string name = JStringToUtf8(env, nameValue);
    const std::string descriptor = JStringToUtf8(env, descriptorValue);
    jmethodID method = ResolveMethod(env, klass, name, descriptor);
    if (method == nullptr) return nullptr;
    char* actualName = nullptr;
    char* actualDescriptor = nullptr;
    char* generic = nullptr;
    jint modifiers = 0;
    jint maxLocals = -1;
    jint argumentSize = -1;
    jlocation start = 0;
    jlocation end = 0;
    jboolean nativeMethod = JNI_FALSE;
    jboolean synthetic = JNI_FALSE;
    jboolean obsolete = JNI_FALSE;
    jvmtiError error = gJvmti->GetMethodName(method, &actualName, &actualDescriptor, &generic);
    if (error == JVMTI_ERROR_NONE) error = gJvmti->GetMethodModifiers(method, &modifiers);
    if (error == JVMTI_ERROR_NONE) error = gJvmti->IsMethodNative(method, &nativeMethod);
    if (error == JVMTI_ERROR_NONE) error = gJvmti->IsMethodObsolete(method, &obsolete);
    const jvmtiError syntheticError = gJvmti->IsMethodSynthetic(method, &synthetic);
    if (error == JVMTI_ERROR_NONE && syntheticError != JVMTI_ERROR_NONE
            && syntheticError != JVMTI_ERROR_MUST_POSSESS_CAPABILITY) error = syntheticError;
    if (nativeMethod != JNI_TRUE) {
        const jvmtiError localsError = gJvmti->GetMaxLocals(method, &maxLocals);
        const jvmtiError argumentsError = gJvmti->GetArgumentsSize(method, &argumentSize);
        const jvmtiError locationError = gJvmti->GetMethodLocation(method, &start, &end);
        if (error == JVMTI_ERROR_NONE && localsError != JVMTI_ERROR_NONE) error = localsError;
        if (error == JVMTI_ERROR_NONE && argumentsError != JVMTI_ERROR_NONE) error = argumentsError;
        if (error == JVMTI_ERROR_NONE && locationError != JVMTI_ERROR_NONE) error = locationError;
    }
    if (error != JVMTI_ERROR_NONE) ThrowJvmti(env, "Get method information", error);
    std::vector<std::string> values = {
        generic == nullptr ? "" : generic, std::to_string(modifiers),
        std::to_string(maxLocals), std::to_string(argumentSize),
        std::to_string(start), std::to_string(end),
        nativeMethod == JNI_TRUE ? "true" : "false",
        synthetic == JNI_TRUE ? "true" : "false",
        obsolete == JNI_TRUE ? "true" : "false"
    };
    if (actualName != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(actualName));
    if (actualDescriptor != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(actualDescriptor));
    if (generic != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
    return env->ExceptionCheck() ? nullptr : NewStringArray(env, values);
}

jbyteArray JNICALL NativeMethodBytecodes(JNIEnv* env, jclass, jclass klass,
        jstring nameValue, jstring descriptorValue) {
    jmethodID method = ResolveMethod(env, klass, JStringToUtf8(env, nameValue),
        JStringToUtf8(env, descriptorValue));
    if (method == nullptr) return nullptr;
    jint count = 0;
    unsigned char* bytecodes = nullptr;
    const jvmtiError error = gJvmti->GetBytecodes(method, &count, &bytecodes);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetBytecodes", error);
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(count);
    if (result != nullptr && count > 0) env->SetByteArrayRegion(
        result, 0, count, reinterpret_cast<jbyte*>(bytecodes));
    if (bytecodes != nullptr) gJvmti->Deallocate(bytecodes);
    return result;
}

jobjectArray JNICALL NativeLineNumberTable(JNIEnv* env, jclass, jclass klass,
        jstring nameValue, jstring descriptorValue) {
    jmethodID method = ResolveMethod(env, klass, JStringToUtf8(env, nameValue),
        JStringToUtf8(env, descriptorValue));
    if (method == nullptr) return nullptr;
    jint count = 0;
    jvmtiLineNumberEntry* table = nullptr;
    const jvmtiError error = gJvmti->GetLineNumberTable(method, &count, &table);
    if (error == JVMTI_ERROR_ABSENT_INFORMATION) return NewStringArray(env, {});
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetLineNumberTable", error);
        return nullptr;
    }
    std::vector<std::string> values;
    values.reserve(static_cast<std::size_t>(count));
    for (jint index = 0; index < count; ++index) {
        values.push_back(std::to_string(table[index].start_location) + "|"
            + std::to_string(table[index].line_number));
    }
    if (table != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(table));
    return NewStringArray(env, values);
}

jobjectArray JNICALL NativeFieldInfo(JNIEnv* env, jclass, jclass klass,
        jstring nameValue, jstring descriptorValue) {
    const std::string name = JStringToUtf8(env, nameValue);
    const std::string descriptor = JStringToUtf8(env, descriptorValue);
    jfieldID field = ResolveField(env, klass, name, descriptor);
    if (field == nullptr) return nullptr;
    char* actualName = nullptr;
    char* actualDescriptor = nullptr;
    char* generic = nullptr;
    jint modifiers = 0;
    jboolean synthetic = JNI_FALSE;
    jclass declaring = nullptr;
    jvmtiError error = gJvmti->GetFieldName(klass, field, &actualName, &actualDescriptor, &generic);
    if (error == JVMTI_ERROR_NONE) error = gJvmti->GetFieldModifiers(klass, field, &modifiers);
    if (error == JVMTI_ERROR_NONE) error = gJvmti->GetFieldDeclaringClass(klass, field, &declaring);
    const jvmtiError syntheticError = gJvmti->IsFieldSynthetic(klass, field, &synthetic);
    if (error == JVMTI_ERROR_NONE && syntheticError != JVMTI_ERROR_NONE
            && syntheticError != JVMTI_ERROR_MUST_POSSESS_CAPABILITY) error = syntheticError;
    if (error != JVMTI_ERROR_NONE) ThrowJvmti(env, "Get field information", error);
    std::vector<std::string> values = {
        generic == nullptr ? "" : generic, std::to_string(modifiers),
        synthetic == JNI_TRUE ? "true" : "false",
        declaring == nullptr ? "" : BinaryClassName(declaring)
    };
    if (actualName != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(actualName));
    if (actualDescriptor != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(actualDescriptor));
    if (generic != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(generic));
    if (declaring != nullptr) env->DeleteLocalRef(declaring);
    return env->ExceptionCheck() ? nullptr : NewStringArray(env, values);
}

jstring JNICALL NativeSourceDebugExtension(JNIEnv* env, jclass, jclass klass) {
    char* extension = nullptr;
    const jvmtiError error = gJvmti->GetSourceDebugExtension(klass, &extension);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetSourceDebugExtension", error);
        return nullptr;
    }
    jstring result = env->NewStringUTF(extension == nullptr ? "" : extension);
    if (extension != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(extension));
    return result;
}

jbyteArray JNICALL NativeConstantPool(JNIEnv* env, jclass, jclass klass) {
    jint constantCount = 0;
    jint byteCount = 0;
    unsigned char* bytes = nullptr;
    const jvmtiError error = gJvmti->GetConstantPool(klass, &constantCount, &byteCount, &bytes);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetConstantPool", error);
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(byteCount);
    if (result != nullptr && byteCount > 0) env->SetByteArrayRegion(
        result, 0, byteCount, reinterpret_cast<jbyte*>(bytes));
    if (bytes != nullptr) gJvmti->Deallocate(bytes);
    return result;
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

jobjectArray JNICALL NativeThreadInfo(JNIEnv* env, jclass, jthread thread) {
    jvmtiThreadInfo info{};
    const jvmtiError error = gJvmti->GetThreadInfo(thread, &info);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetThreadInfo", error);
        return nullptr;
    }
    jint state = 0;
    gJvmti->GetThreadState(thread, &state);
    std::vector<std::string> values = {
        info.name == nullptr ? "" : info.name,
        std::to_string(info.priority), info.is_daemon == JNI_TRUE ? "true" : "false",
        ObjectTypeName(env, info.thread_group), ObjectTypeName(env, info.context_class_loader),
        std::to_string(state)
    };
    if (info.name != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(info.name));
    if (info.thread_group != nullptr) env->DeleteLocalRef(info.thread_group);
    if (info.context_class_loader != nullptr) env->DeleteLocalRef(info.context_class_loader);
    return NewStringArray(env, values);
}

jint JNICALL NativeFrameCount(JNIEnv* env, jclass, jthread thread) {
    jint count = 0;
    const jvmtiError error = gJvmti->GetFrameCount(thread, &count);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetFrameCount", error);
        return 0;
    }
    return count;
}

jlong JNICALL NativeThreadCpuTime(JNIEnv* env, jclass, jthread thread) {
    jlong value = 0;
    const jvmtiError error = gJvmti->GetThreadCpuTime(thread, &value);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetThreadCpuTime", error);
        return 0;
    }
    return value;
}

jobjectArray ObjectArray(JNIEnv* env, jint count, jobject* objects) {
    jobjectArray result = gObjectClass == nullptr ? nullptr
        : env->NewObjectArray(count, gObjectClass, nullptr);
    for (jint index = 0; result != nullptr && index < count; ++index) {
        env->SetObjectArrayElement(result, index, objects[index]);
        env->DeleteLocalRef(objects[index]);
    }
    if (objects != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(objects));
    return result;
}

jobjectArray JNICALL NativeOwnedMonitors(JNIEnv* env, jclass, jthread thread) {
    jint count = 0;
    jobject* monitors = nullptr;
    const jvmtiError error = gJvmti->GetOwnedMonitorInfo(thread, &count, &monitors);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetOwnedMonitorInfo", error);
        return nullptr;
    }
    return ObjectArray(env, count, monitors);
}

jobject JNICALL NativeCurrentContendedMonitor(JNIEnv* env, jclass, jthread thread) {
    jobject monitor = nullptr;
    const jvmtiError error = gJvmti->GetCurrentContendedMonitor(thread, &monitor);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetCurrentContendedMonitor", error);
        return nullptr;
    }
    return monitor;
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

jint JNICALL NativeGetObjectHashCode(JNIEnv* env, jclass, jobject object) {
    jint value = 0;
    const jvmtiError error = gJvmti->GetObjectHashCode(object, &value);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetObjectHashCode", error);
        return 0;
    }
    return value;
}

jobjectArray JNICALL NativeObjectMonitorUsage(JNIEnv* env, jclass, jobject object) {
    jvmtiMonitorUsage usage{};
    const jvmtiError error = gJvmti->GetObjectMonitorUsage(object, &usage);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetObjectMonitorUsage", error);
        return nullptr;
    }
    std::vector<std::string> values = {
        ObjectTypeName(env, usage.owner), std::to_string(usage.entry_count),
        std::to_string(usage.waiter_count), std::to_string(usage.notify_waiter_count)
    };
    if (usage.owner != nullptr) env->DeleteLocalRef(usage.owner);
    for (jint index = 0; index < usage.waiter_count; ++index) {
        if (usage.waiters[index] != nullptr) env->DeleteLocalRef(usage.waiters[index]);
    }
    for (jint index = 0; index < usage.notify_waiter_count; ++index) {
        if (usage.notify_waiters[index] != nullptr) env->DeleteLocalRef(usage.notify_waiters[index]);
    }
    if (usage.waiters != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(usage.waiters));
    if (usage.notify_waiters != nullptr) {
        gJvmti->Deallocate(reinterpret_cast<unsigned char*>(usage.notify_waiters));
    }
    return NewStringArray(env, values);
}

jobjectArray JNICALL NativeObjectsWithTag(JNIEnv* env, jclass, jlong tag) {
    jint count = 0;
    jobject* objects = nullptr;
    jlong* tags = nullptr;
    const jvmtiError error = gJvmti->GetObjectsWithTags(1, &tag, &count, &objects, &tags);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetObjectsWithTags", error);
        return nullptr;
    }
    if (tags != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(tags));
    return ObjectArray(env, count, objects);
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

jstring JNICALL NativeGetSystemProperty(JNIEnv* env, jclass, jstring nameValue) {
    const std::string name = JStringToUtf8(env, nameValue);
    if (name.empty()) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Property name must not be empty");
        return nullptr;
    }
    char* value = nullptr;
    const jvmtiError error = gJvmti->GetSystemProperty(name.c_str(), &value);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetSystemProperty", error);
        return nullptr;
    }
    jstring result = env->NewStringUTF(value == nullptr ? "" : value);
    if (value != nullptr) gJvmti->Deallocate(reinterpret_cast<unsigned char*>(value));
    return result;
}

void JNICALL NativeSetSystemProperty(
        JNIEnv* env, jclass, jstring nameValue, jstring propertyValue) {
    const std::string name = JStringToUtf8(env, nameValue);
    const std::string value = JStringToUtf8(env, propertyValue);
    if (name.empty() || propertyValue == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Property name and value are required");
        return;
    }
    ThrowJvmti(env, "SetSystemProperty", gJvmti->SetSystemProperty(name.c_str(), value.c_str()));
}

jlong JNICALL NativeCurrentThreadCpuTime(JNIEnv* env, jclass) {
    jlong value = 0;
    const jvmtiError error = gJvmti->GetCurrentThreadCpuTime(&value);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetCurrentThreadCpuTime", error);
        return 0;
    }
    return value;
}

jobjectArray JNICALL NativeTimerInfo(JNIEnv* env, jclass) {
    jvmtiTimerInfo info{};
    const jvmtiError error = gJvmti->GetTimerInfo(&info);
    if (error != JVMTI_ERROR_NONE) {
        ThrowJvmti(env, "GetTimerInfo", error);
        return nullptr;
    }
    return NewStringArray(env, {std::to_string(info.max_value),
        info.may_skip_forward == JNI_TRUE ? "true" : "false",
        info.may_skip_backward == JNI_TRUE ? "true" : "false",
        std::to_string(static_cast<int>(info.kind))});
}

void JNICALL NativeGenerateEvents(JNIEnv* env, jclass, jstring eventNameValue) {
    const std::string eventName = JStringToUtf8(env, eventNameValue);
    jvmtiEvent event{};
    if (!ResolveEvent(eventName, &event)) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Unsupported JVMTI event: " + eventName);
        return;
    }
    ThrowJvmti(env, "GenerateEvents", gJvmti->GenerateEvents(event));
}

void JNICALL NativeSetVerboseFlag(
        JNIEnv* env, jclass, jstring flagNameValue, jboolean enabled) {
    std::string name = JStringToUtf8(env, flagNameValue);
    std::transform(name.begin(), name.end(), name.begin(), [](unsigned char value) {
        return static_cast<char>(std::tolower(value));
    });
    jvmtiVerboseFlag flag{};
    if (name == "other") flag = JVMTI_VERBOSE_OTHER;
    else if (name == "gc") flag = JVMTI_VERBOSE_GC;
    else if (name == "class") flag = JVMTI_VERBOSE_CLASS;
    else if (name == "jni") flag = JVMTI_VERBOSE_JNI;
    else {
        ThrowJava(env, "java/lang/IllegalArgumentException",
            "Verbose flag must be other, gc, class or jni");
        return;
    }
    ThrowJvmti(env, "SetVerboseFlag", gJvmti->SetVerboseFlag(flag, enabled));
}

JNINativeMethod kRuntimeMethods[] = {
    {const_cast<char*>("version"), const_cast<char*>("()Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeVersion)},
    {const_cast<char*>("jvmtiVersion"), const_cast<char*>("()I"),
     reinterpret_cast<void*>(&NativeJvmtiVersion)},
};

JNINativeMethod kJniMethods[] = {
    {const_cast<char*>("readStaticFields"), const_cast<char*>("(Ljava/lang/String;)Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeReadStaticFields)},
    {const_cast<char*>("readStaticField"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeReadStaticField)},
    {const_cast<char*>("callStaticMethod"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeCallStaticMethod)},
    {const_cast<char*>("findLoadedClass"),
     const_cast<char*>("(Ljava/lang/String;)Ljava/lang/Class;"),
     reinterpret_cast<void*>(&NativeFindLoadedClass)},
    {const_cast<char*>("listLoadedClassNames"), const_cast<char*>("()[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeListLoadedClassNames)},
    {const_cast<char*>("listLoadedClasses"), const_cast<char*>("()[Ljava/lang/Class;"),
     reinterpret_cast<void*>(&NativeListLoadedClasses)},
    {const_cast<char*>("defineClass"),
     const_cast<char*>("(Ljava/lang/String;[BLjava/lang/ClassLoader;)Ljava/lang/Class;"),
     reinterpret_cast<void*>(&NativeDefineClass)},
};

JNINativeMethod kJvmtiMethods[] = {
    {const_cast<char*>("getClassBytes"), const_cast<char*>("(Ljava/lang/String;)[B"),
     reinterpret_cast<void*>(&NativeGetClassBytes)},
    {const_cast<char*>("addToClassLoaderSearch"),
     const_cast<char*>("(Ljava/lang/String;Z)V"),
     reinterpret_cast<void*>(&NativeAddToClassLoaderSearch)},
    {const_cast<char*>("setEventNotification"),
     const_cast<char*>("(Ljava/lang/String;Z)V"),
     reinterpret_cast<void*>(&NativeSetEventNotification)},
    {const_cast<char*>("setBreakpoint"),
     const_cast<char*>("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;JZLjava/lang/String;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
     reinterpret_cast<void*>(&NativeSetBreakpoint)},
    {const_cast<char*>("setBreakpointByName"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
     reinterpret_cast<void*>(&NativeSetBreakpointByName)},
    {const_cast<char*>("setDebugEventBreakpoint"),
     const_cast<char*>("(ILjava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;Z)V"),
     reinterpret_cast<void*>(&NativeSetDebugEventBreakpoint)},
    {const_cast<char*>("debuggerConfigure"), const_cast<char*>("(Z)V"),
     reinterpret_cast<void*>(&NativeDebuggerConfigure)},
    {const_cast<char*>("debuggerSnapshot"), const_cast<char*>("()[Ljava/lang/Object;"),
     reinterpret_cast<void*>(&NativeDebuggerSnapshot)},
    {const_cast<char*>("debuggerSnapshots"), const_cast<char*>("()[[Ljava/lang/Object;"),
     reinterpret_cast<void*>(&NativeDebuggerSnapshots)},
    {const_cast<char*>("debuggerResume"), const_cast<char*>("(I)V"),
     reinterpret_cast<void*>(&NativeDebuggerResume)},
    {const_cast<char*>("debuggerResumeThread"), const_cast<char*>("(Ljava/lang/Thread;I)V"),
     reinterpret_cast<void*>(&NativeDebuggerResumeThread)},
    {const_cast<char*>("debuggerPauseThread"),
     const_cast<char*>("(Ljava/lang/Thread;Ljava/lang/String;)V"),
     reinterpret_cast<void*>(&NativeDebuggerPauseThread)},
    {const_cast<char*>("debuggerLocals"), const_cast<char*>("(Ljava/lang/Thread;I)[[Ljava/lang/Object;"),
     reinterpret_cast<void*>(&NativeDebuggerLocals)},
    {const_cast<char*>("debuggerSetLocal"),
     const_cast<char*>("(Ljava/lang/Thread;IILjava/lang/String;Ljava/lang/Object;)V"),
     reinterpret_cast<void*>(&NativeDebuggerSetLocal)},
    {const_cast<char*>("debuggerForceReturn"),
     const_cast<char*>("(Ljava/lang/Thread;Ljava/lang/Object;)V"),
     reinterpret_cast<void*>(&NativeDebuggerForceReturn)},
    {const_cast<char*>("setFieldWatch"),
     const_cast<char*>("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;ZZLjava/lang/String;Ljava/lang/Object;)V"),
     reinterpret_cast<void*>(&NativeSetFieldWatch)},
    {const_cast<char*>("setFieldWatchByName"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZLjava/lang/String;)V"),
     reinterpret_cast<void*>(&NativeSetFieldWatchByName)},
    {const_cast<char*>("setStringAllocationHook"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)V"),
     reinterpret_cast<void*>(&NativeSetStringAllocationHook)},
    {const_cast<char*>("notifyFramePop"), const_cast<char*>("(Ljava/lang/Thread;I)V"),
     reinterpret_cast<void*>(&NativeNotifyFramePop)},
    {const_cast<char*>("eventQueueStatistics"), const_cast<char*>("()[J"),
     reinterpret_cast<void*>(&NativeEventQueueStatistics)},
    {const_cast<char*>("retransformClass"), const_cast<char*>("(Ljava/lang/Class;)V"),
     reinterpret_cast<void*>(&NativeRetransformClass)},
    {const_cast<char*>("redefineClass"), const_cast<char*>("(Ljava/lang/Class;[B)V"),
     reinterpret_cast<void*>(&NativeRedefineClass)},
    {const_cast<char*>("capabilities"), const_cast<char*>("()Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeCapabilities)},
    {const_cast<char*>("capabilityStatuses"), const_cast<char*>("()[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeCapabilityStatuses)},
    {const_cast<char*>("changeCapabilities"), const_cast<char*>("([Ljava/lang/String;Z)V"),
     reinterpret_cast<void*>(&NativeChangeCapabilities)},
    {const_cast<char*>("phase"), const_cast<char*>("()I"),
     reinterpret_cast<void*>(&NativePhase)},
    {const_cast<char*>("time"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(&NativeTime)},
    {const_cast<char*>("availableProcessors"), const_cast<char*>("()I"),
     reinterpret_cast<void*>(&NativeAvailableProcessors)},
    {const_cast<char*>("locationFormat"), const_cast<char*>("()I"),
     reinterpret_cast<void*>(&NativeLocationFormat)},
    {const_cast<char*>("classInfo"), const_cast<char*>("(Ljava/lang/Class;)[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeClassInfo)},
    {const_cast<char*>("implementedInterfaces"), const_cast<char*>("(Ljava/lang/Class;)[Ljava/lang/Class;"),
     reinterpret_cast<void*>(&NativeImplementedInterfaces)},
    {const_cast<char*>("classLoader"), const_cast<char*>("(Ljava/lang/Class;)Ljava/lang/ClassLoader;"),
     reinterpret_cast<void*>(&NativeClassLoader)},
    {const_cast<char*>("classLoaderClasses"),
     const_cast<char*>("(Ljava/lang/ClassLoader;)[Ljava/lang/Class;"),
     reinterpret_cast<void*>(&NativeClassLoaderClasses)},
    {const_cast<char*>("methodInfo"),
     const_cast<char*>("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeMethodInfo)},
    {const_cast<char*>("methodBytecodes"),
     const_cast<char*>("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)[B"),
     reinterpret_cast<void*>(&NativeMethodBytecodes)},
    {const_cast<char*>("lineNumberTable"),
     const_cast<char*>("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeLineNumberTable)},
    {const_cast<char*>("fieldInfo"),
     const_cast<char*>("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeFieldInfo)},
    {const_cast<char*>("sourceDebugExtension"),
     const_cast<char*>("(Ljava/lang/Class;)Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeSourceDebugExtension)},
    {const_cast<char*>("constantPool"), const_cast<char*>("(Ljava/lang/Class;)[B"),
     reinterpret_cast<void*>(&NativeConstantPool)},
    {const_cast<char*>("getAllThreads"), const_cast<char*>("()[Ljava/lang/Thread;"),
     reinterpret_cast<void*>(&NativeGetAllThreads)},
    {const_cast<char*>("getThreadState"), const_cast<char*>("(Ljava/lang/Thread;)I"),
     reinterpret_cast<void*>(&NativeGetThreadState)},
    {const_cast<char*>("getStackTrace"), const_cast<char*>("(Ljava/lang/Thread;I)[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeGetStackTrace)},
    {const_cast<char*>("threadControl"), const_cast<char*>("(Ljava/lang/Thread;I)V"),
     reinterpret_cast<void*>(&NativeThreadControl)},
    {const_cast<char*>("threadInfo"), const_cast<char*>("(Ljava/lang/Thread;)[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeThreadInfo)},
    {const_cast<char*>("frameCount"), const_cast<char*>("(Ljava/lang/Thread;)I"),
     reinterpret_cast<void*>(&NativeFrameCount)},
    {const_cast<char*>("threadCpuTime"), const_cast<char*>("(Ljava/lang/Thread;)J"),
     reinterpret_cast<void*>(&NativeThreadCpuTime)},
    {const_cast<char*>("ownedMonitors"), const_cast<char*>("(Ljava/lang/Thread;)[Ljava/lang/Object;"),
     reinterpret_cast<void*>(&NativeOwnedMonitors)},
    {const_cast<char*>("currentContendedMonitor"),
     const_cast<char*>("(Ljava/lang/Thread;)Ljava/lang/Object;"),
     reinterpret_cast<void*>(&NativeCurrentContendedMonitor)},
    {const_cast<char*>("getObjectSize"), const_cast<char*>("(Ljava/lang/Object;)J"),
     reinterpret_cast<void*>(&NativeGetObjectSize)},
    {const_cast<char*>("getObjectHashCode"), const_cast<char*>("(Ljava/lang/Object;)I"),
     reinterpret_cast<void*>(&NativeGetObjectHashCode)},
    {const_cast<char*>("objectMonitorUsage"),
     const_cast<char*>("(Ljava/lang/Object;)[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeObjectMonitorUsage)},
    {const_cast<char*>("objectsWithTag"), const_cast<char*>("(J)[Ljava/lang/Object;"),
     reinterpret_cast<void*>(&NativeObjectsWithTag)},
    {const_cast<char*>("getTag"), const_cast<char*>("(Ljava/lang/Object;)J"),
     reinterpret_cast<void*>(&NativeGetTag)},
    {const_cast<char*>("setTag"), const_cast<char*>("(Ljava/lang/Object;J)V"),
     reinterpret_cast<void*>(&NativeSetTag)},
    {const_cast<char*>("forceGarbageCollection"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(&NativeForceGarbageCollection)},
    {const_cast<char*>("systemProperties"), const_cast<char*>("()[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeSystemProperties)},
    {const_cast<char*>("getSystemProperty"), const_cast<char*>("(Ljava/lang/String;)Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeGetSystemProperty)},
    {const_cast<char*>("setSystemProperty"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;)V"),
     reinterpret_cast<void*>(&NativeSetSystemProperty)},
    {const_cast<char*>("currentThreadCpuTime"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(&NativeCurrentThreadCpuTime)},
    {const_cast<char*>("timerInfo"), const_cast<char*>("()[Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeTimerInfo)},
    {const_cast<char*>("generateEvents"), const_cast<char*>("(Ljava/lang/String;)V"),
     reinterpret_cast<void*>(&NativeGenerateEvents)},
    {const_cast<char*>("setVerboseFlag"), const_cast<char*>("(Ljava/lang/String;Z)V"),
     reinterpret_cast<void*>(&NativeSetVerboseFlag)},
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

jclass LoadClassWithLoader(JNIEnv* env, jobject loader, const char* binaryName) {
    if (loader == nullptr) return nullptr;
    jclass loaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = loaderClass == nullptr ? nullptr : env->GetMethodID(
        loaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring name = loadClass == nullptr ? nullptr : env->NewStringUTF(binaryName);
    jclass result = name == nullptr ? nullptr : static_cast<jclass>(
        env->CallObjectMethod(loader, loadClass, name));
    if (name != nullptr) env->DeleteLocalRef(name);
    if (loaderClass != nullptr) env->DeleteLocalRef(loaderClass);
    return result;
}

jclass LoadBindingClass(JNIEnv* env, std::string_view internalName, bool preloadedAgent,
        jobject classLoader) {
    std::string binaryName(internalName);
    std::replace(binaryName.begin(), binaryName.end(), '/', '.');
    if (classLoader != nullptr) return LoadClassWithLoader(env, classLoader, binaryName.c_str());
    if (!preloadedAgent) return env->FindClass(internalName.data());
    return LoadSystemClass(env, binaryName.c_str());
}

bool RegisterNativeGroup(JNIEnv* env, bool preloadedAgent, std::string_view className,
        JNINativeMethod* methods, jint methodCount, jobject classLoader) {
    jclass bindingClass = LoadBindingClass(env, className, preloadedAgent, classLoader);
    if (bindingClass == nullptr) {
        // Missing application classes during VMInit is expected when only -agentpath is used.
        // An explicit attach class loader, however, should retain its exception for diagnostics.
        if (preloadedAgent && classLoader == nullptr && env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    const jint result = env->RegisterNatives(bindingClass, methods, methodCount);
    env->DeleteLocalRef(bindingClass);
    return result == JNI_OK;
}

bool InitializeJavaBridge(JavaVM* vm, JNIEnv* env, bool preloadedAgent, jobject classLoader) {
    if (vm == nullptr || env == nullptr) return false;
    if (preloadedAgent) SetPreloadedAgentProperty(env);
    if (env->ExceptionCheck() || !CacheCallbackTypes(env)) {
        if (preloadedAgent && env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    if (!RegisterNativeGroup(env, preloadedAgent, kRuntimeBindingClass, kRuntimeMethods,
            static_cast<jint>(sizeof(kRuntimeMethods) / sizeof(kRuntimeMethods[0])), classLoader)
        || !RegisterNativeGroup(env, preloadedAgent, kJniBindingClass, kJniMethods,
            static_cast<jint>(sizeof(kJniMethods) / sizeof(kJniMethods[0])), classLoader)
        || !RegisterNativeGroup(env, preloadedAgent, kJvmtiBindingClass, kJvmtiMethods,
            static_cast<jint>(sizeof(kJvmtiMethods) / sizeof(kJvmtiMethods[0])), classLoader)) return false;
    gJavaVm = vm;

    if (gDispatcherClass == nullptr || gDispatchMethod == nullptr || gTransformMethod == nullptr) {
        jclass dispatcher = classLoader != nullptr
            ? LoadClassWithLoader(env, classLoader, "nhcm.jvmrtdp.remoteside.JvmtiCallbackDispatcher")
            : (preloadedAgent
                ? LoadSystemClass(env, "nhcm.jvmrtdp.remoteside.JvmtiCallbackDispatcher")
                : env->FindClass("nhcm/jvmrtdp/remoteside/JvmtiCallbackDispatcher"));
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
    }
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

// Called by the manual-map injector when this DLL was loaded at VM startup with -agentpath.
// It binds the Java classes from the injector's isolated URLClassLoader to the original
// OnLoad JVMTI environment, preserving capabilities that cannot be acquired in LIVE phase.
extern "C" JNIEXPORT jint JNICALL JVMRTDP_InitializeJavaBridge(
        JavaVM* vm, JNIEnv* env, jobject classLoader) {
    if (!gLoadedAsJvmtiAgent.load() || classLoader == nullptr) return JNI_ERR;
    return InitializeJavaBridge(vm, env, true, classLoader) ? JNI_OK : JNI_ERR;
}

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void*) {
    if (vm == nullptr || vm->GetEnv(reinterpret_cast<void**>(&gJvmti), JVMTI_VERSION_1_2) != JNI_OK
        || gJvmti == nullptr) return JNI_ERR;
    gJavaVm = vm;
    gLoadedAsJvmtiAgent.store(true);
    ConfigureStartupDebugger(options);
    if (!RequestAllCapabilities() || !InstallJvmtiCallbacks()) return JNI_ERR;
    if (gJvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, nullptr)
        != JVMTI_ERROR_NONE) return JNI_ERR;
    if (!gStartupMainClass.empty() || !gStartupClinitClass.empty()) {
        if (gJvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, nullptr)
                != JVMTI_ERROR_NONE
            || gJvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, nullptr)
                != JVMTI_ERROR_NONE) return JNI_ERR;
        gStartupClassPrepareOwned.store(true);
    }
    if (!gStartupEntrySpec.empty()
            && gJvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_ENTRY, nullptr)
                != JVMTI_ERROR_NONE) return JNI_ERR;
    if (!gStartupExitSpec.empty()
            && gJvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_EXIT, nullptr)
                != JVMTI_ERROR_NONE) return JNI_ERR;
    if (!gStartupExceptionPattern.empty()
            && gJvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_EXCEPTION, nullptr)
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
        || !InitializeJavaBridge(vm, env, false, nullptr)) return JNI_ERR;
    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
    {
        std::lock_guard<std::mutex> guard(gDebuggerMutex);
        gDebuggerEnabled = false;
        for (const std::shared_ptr<DebuggerStop>& stop : gDebuggerStops) {
            if (stop->paused && stop->externallySuspended && stop->thread != nullptr
                    && gJvmti != nullptr) {
                gJvmti->ResumeThread(static_cast<jthread>(stop->thread));
            }
            stop->paused = false;
        }
    }
    gDebuggerChanged.notify_all();
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
        {
            std::lock_guard<std::mutex> guard(gBreakpointMutex);
            for (const PersistentBreakpoint& breakpoint : gPersistentBreakpoints) {
                if (breakpoint.klass != nullptr) env->DeleteGlobalRef(breakpoint.klass);
                if (breakpoint.receiver != nullptr) env->DeleteGlobalRef(breakpoint.receiver);
            }
            gPersistentBreakpoints.clear();
        }
        {
            std::lock_guard<std::mutex> guard(gFieldWatchMutex);
            for (const PersistentFieldWatch& watch : gPersistentFieldWatches) {
                if (watch.klass != nullptr) env->DeleteGlobalRef(watch.klass);
                if (watch.receiver != nullptr) env->DeleteGlobalRef(watch.receiver);
            }
            gPersistentFieldWatches.clear();
        }
        {
            std::lock_guard<std::mutex> guard(gDebugEventBreakpointMutex);
            for (const DebugEventBreakpoint& breakpoint : gDebugEventBreakpoints) {
                if (breakpoint.declaredClass != nullptr) env->DeleteGlobalRef(breakpoint.declaredClass);
            }
            gDebugEventBreakpoints.clear();
        }
        {
            std::lock_guard<std::mutex> guard(gStringAllocationHookMutex);
            gStringAllocationHooks.clear();
            for (jweak reference : gMatchedStringAllocations) {
                env->DeleteWeakGlobalRef(reference);
            }
            gMatchedStringAllocations.clear();
            gStringAllocationHookCount.store(0);
        }
        RemoveStepOutState(env, nullptr);
        if (gDispatcherClass != nullptr) env->DeleteGlobalRef(gDispatcherClass);
        ReleaseCallbackTypes(env);
    }
    gDispatcherClass = nullptr;
    gDispatchMethod = nullptr;
    gTransformMethod = nullptr;
    gClassFileHookEnabled.store(false);
    gJavaMethodEntryDispatchEnabled.store(false);
    gJavaMethodExitDispatchEnabled.store(false);
    gJavaExceptionDispatchEnabled.store(false);
    gStartupBreakpointInstalled = false;
    gStartupClinitBreakpointInstalled = false;
    gStartupMainMethod = nullptr;
    gStartupMainLocation = 0;
    gStartupClinitMethod = nullptr;
    gStartupClinitLocation = 0;
    gStartupMainClass.clear();
    gStartupClinitClass.clear();
    gStartupEntrySpec.clear();
    gStartupExitSpec.clear();
    gStartupExceptionPattern.clear();
    gStartupClassPrepareOwned.store(false);
    gCallbacksInstalled.store(false);
    gCapabilitiesRequested.store(false);
    gLoadedAsJvmtiAgent.store(false);
    gCanRetransform = false;
    gJvmti = nullptr;
    gJavaVm = nullptr;
}
