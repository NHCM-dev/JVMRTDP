#include "pch.h"
#include "jnisupport.h"

#include <jvmti.h>

#include <algorithm>
#include <iomanip>
#include <limits>
#include <mutex>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>

namespace {

constexpr std::string_view kBindingClass = "nhcm/jvmrtdp/agent/NativeAgent";
constexpr jint kAccStatic = 0x0008;

JavaVM* gJavaVm = nullptr;
jvmtiEnv* gJvmti = nullptr;
bool gCanRetransform = false;
std::mutex gRetransformMutex;
thread_local jclass tRequestedClass = nullptr;
thread_local std::vector<unsigned char>* tRequestedBytes = nullptr;

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

void JNICALL ClassFileLoadHook(jvmtiEnv*, JNIEnv* env, jclass classBeingRedefined,
        jobject, const char*, jobject, jint classDataLength, const unsigned char* classData,
        jint*, unsigned char**) {
    if (tRequestedClass != nullptr && tRequestedBytes != nullptr && classBeingRedefined != nullptr
        && env->IsSameObject(tRequestedClass, classBeingRedefined)) {
        tRequestedBytes->assign(classData, classData + classDataLength);
    }
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
        std::lock_guard<std::mutex> guard(gRetransformMutex);
        tRequestedClass = klass;
        tRequestedBytes = &bytes;
        error = gJvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
        if (error == JVMTI_ERROR_NONE) error = gJvmti->RetransformClasses(1, &klass);
        const jvmtiError disableError = gJvmti->SetEventNotificationMode(
            JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
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
};

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    if (vm->GetEnv(reinterpret_cast<void**>(&gJvmti), JVMTI_VERSION_1_2) != JNI_OK || gJvmti == nullptr) {
        return JNI_ERR;
    }

    jvmtiCapabilities capabilities{};
    capabilities.can_retransform_classes = 1;
    gCanRetransform = gJvmti->AddCapabilities(&capabilities) == JVMTI_ERROR_NONE;
    jvmtiEventCallbacks callbacks{};
    callbacks.ClassFileLoadHook = &ClassFileLoadHook;
    if (gJvmti->SetEventCallbacks(&callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) {
        return JNI_ERR;
    }

    jclass bindingClass = env->FindClass(kBindingClass.data());
    if (bindingClass == nullptr) return JNI_ERR;
    const jint result = env->RegisterNatives(
        bindingClass, kMethods, static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0])));
    env->DeleteLocalRef(bindingClass);
    if (result != JNI_OK) return JNI_ERR;
    gJavaVm = vm;
    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    gCanRetransform = false;
    gJvmti = nullptr;
    gJavaVm = nullptr;
}
