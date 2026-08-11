#include "pch.h"
#include "bootstrap.h"
#include "injection/manual_mapper.h"

#include <jni.h>
#include <TlHelp32.h>
#include <shellapi.h>

#include <algorithm>
#include <cstdint>
#include <cwchar>
#include <cwctype>
#include <limits>
#include <string>
#include <string_view>
#include <vector>

#include <winternl.h>

namespace {

constexpr std::string_view kBindingClass = "nhcm/jvmrtdp/nativebridge/InjectorNative";

class Handle final {
public:
    explicit Handle(HANDLE value = nullptr) noexcept : value_(value) {}
    ~Handle() { if (value_ != nullptr && value_ != INVALID_HANDLE_VALUE) CloseHandle(value_); }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    HANDLE get() const noexcept { return value_; }
    explicit operator bool() const noexcept { return value_ != nullptr && value_ != INVALID_HANDLE_VALUE; }
private:
    HANDLE value_;
};

class RemoteMemory final {
public:
    RemoteMemory(HANDLE process, SIZE_T size)
        : process_(process), address_(VirtualAllocEx(process, nullptr, size, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE)) {}
    ~RemoteMemory() { if (address_ != nullptr) VirtualFreeEx(process_, address_, 0, MEM_RELEASE); }
    RemoteMemory(const RemoteMemory&) = delete;
    RemoteMemory& operator=(const RemoteMemory&) = delete;
    LPVOID get() const noexcept { return address_; }
    void release() noexcept { address_ = nullptr; }
private:
    HANDLE process_;
    LPVOID address_;
};

std::wstring JStringToWide(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const jchar* characters = env->GetStringChars(value, nullptr);
    if (characters == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    std::wstring result(reinterpret_cast<const wchar_t*>(characters), static_cast<std::size_t>(length));
    env->ReleaseStringChars(value, characters);
    return result;
}

std::string JStringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* characters = env->GetStringUTFChars(value, nullptr);
    if (characters == nullptr) return {};
    std::string result(characters);
    env->ReleaseStringUTFChars(value, characters);
    return result;
}

void ThrowInjectionException(JNIEnv* env, const std::wstring& message) {
    jclass exceptionClass = env->FindClass("nhcm/jvmrtdp/throwble/InjectionException");
    if (exceptionClass == nullptr) return;
    int size = WideCharToMultiByte(CP_UTF8, 0, message.c_str(), -1, nullptr, 0, nullptr, nullptr);
    std::string utf8(size > 0 ? static_cast<std::size_t>(size) : 0, '\0');
    if (size > 1) {
        WideCharToMultiByte(CP_UTF8, 0, message.c_str(), -1, &utf8[0], size, nullptr, nullptr);
        utf8.resize(static_cast<std::size_t>(size - 1));
    }
    env->ThrowNew(exceptionClass, utf8.empty() ? "Native injection failed" : utf8.c_str());
    env->DeleteLocalRef(exceptionClass);
}

std::wstring WindowsError(const wchar_t* operation, DWORD error = GetLastError()) {
    wchar_t* systemMessage = nullptr;
    FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr, error, 0, reinterpret_cast<wchar_t*>(&systemMessage), 0, nullptr);
    std::wstring result(operation);
    result.append(L" (error ").append(std::to_wstring(error)).append(L")");
    if (systemMessage != nullptr) {
        result.append(L": ").append(systemMessage);
        LocalFree(systemMessage);
    }
    while (!result.empty() && (result.back() == L'\r' || result.back() == L'\n')) result.pop_back();
    return result;
}

bool ProcessContainsJvm(DWORD pid) {
    Handle snapshot(CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid));
    if (!snapshot) return false;
    MODULEENTRY32W module{};
    module.dwSize = sizeof(module);
    if (!Module32FirstW(snapshot.get(), &module)) return false;
    do {
        if (_wcsicmp(module.szModule, L"jvm.dll") == 0) return true;
    } while (Module32NextW(snapshot.get(), &module));
    return false;
}

std::vector<DWORD> ListJvmProcesses() {
    std::vector<DWORD> result;
    Handle snapshot(CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0));
    if (!snapshot) return result;
    PROCESSENTRY32W process{};
    process.dwSize = sizeof(process);
    if (!Process32FirstW(snapshot.get(), &process)) return result;
    do {
        if (process.th32ProcessID != 0 && ProcessContainsJvm(process.th32ProcessID)) {
            result.push_back(process.th32ProcessID);
        }
    } while (Process32NextW(snapshot.get(), &process));
    return result;
}

std::wstring BaseName(const std::wstring& path) {
    const std::size_t separator = path.find_last_of(L"\\/");
    return separator == std::wstring::npos ? path : path.substr(separator + 1);
}

struct WindowTitleSearch final {
    DWORD pid;
    HWND foreground;
    std::wstring title;
    int score;
};

BOOL CALLBACK FindProcessWindowTitle(HWND window, LPARAM parameter) noexcept {
    auto* search = reinterpret_cast<WindowTitleSearch*>(parameter);
    DWORD windowPid = 0;
    GetWindowThreadProcessId(window, &windowPid);
    if (windowPid != search->pid || !IsWindowVisible(window)) return TRUE;
    const int length = GetWindowTextLengthW(window);
    if (length <= 0 || length > 32767) return TRUE;
    std::wstring title(static_cast<std::size_t>(length) + 1, L'\0');
    const int copied = GetWindowTextW(window, &title[0], length + 1);
    if (copied <= 0) return TRUE;
    title.resize(static_cast<std::size_t>(copied));

    int score = 1;
    if (window == search->foreground) score += 8;
    if (GetWindow(window, GW_OWNER) == nullptr) score += 4;
    if ((GetWindowLongPtrW(window, GWL_EXSTYLE) & WS_EX_TOOLWINDOW) == 0) score += 2;
    if (score > search->score
        || (score == search->score && title.size() > search->title.size())) {
        search->title = title;
        search->score = score;
    }
    return TRUE;
}

std::wstring ProcessWindowTitle(DWORD pid) {
    WindowTitleSearch search{pid, GetForegroundWindow(), {}, -1};
    EnumWindows(&FindProcessWindowTitle, reinterpret_cast<LPARAM>(&search));
    return search.title;
}

std::wstring ProcessCommandLine(HANDLE process) {
    // ProcessCommandLineInformation (60) is available on supported Windows 10/11.
    // Resolve it dynamically because it is intentionally not part of the Win32 API surface.
    using NtQueryInformationProcessFunction = LONG(NTAPI*)(HANDLE, ULONG, PVOID, ULONG, PULONG);
    const auto query = reinterpret_cast<NtQueryInformationProcessFunction>(
        GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtQueryInformationProcess"));
    if (query == nullptr) return {};

    struct CommandLineUnicodeString {
        USHORT Length;
        USHORT MaximumLength;
        PWSTR Buffer;
    };
    ULONG required = 0;
    query(process, 60, nullptr, 0, &required);
    if (required < sizeof(CommandLineUnicodeString) || required > 1024 * 1024) return {};
    std::vector<unsigned char> buffer(required);
    if (query(process, 60, buffer.data(), required, &required) < 0) return {};

    const auto commandLine = reinterpret_cast<const CommandLineUnicodeString*>(buffer.data());
    const auto begin = reinterpret_cast<std::uintptr_t>(buffer.data());
    const auto end = begin + buffer.size();
    const auto text = reinterpret_cast<std::uintptr_t>(commandLine->Buffer);
    if (commandLine->Length == 0 || (commandLine->Length % sizeof(wchar_t)) != 0
        || text < begin || text > end || commandLine->Length > end - text) return {};
    return std::wstring(commandLine->Buffer, commandLine->Length / sizeof(wchar_t));
}

std::wstring JavaLaunchIdentity(const std::wstring& executableName, const std::wstring& commandLine) {
    if (_wcsicmp(executableName.c_str(), L"java.exe") != 0
        && _wcsicmp(executableName.c_str(), L"javaw.exe") != 0) return L"<embedded JVM>";
    int argumentCount = 0;
    wchar_t** arguments = CommandLineToArgvW(commandLine.c_str(), &argumentCount);
    if (arguments == nullptr) return L"<unknown>";

    std::wstring result;
    for (int index = 1; index < argumentCount; ++index) {
        const std::wstring argument(arguments[index]);
        if (_wcsicmp(argument.c_str(), L"-jar") == 0 && index + 1 < argumentCount) {
            result.append(L"-jar ").append(BaseName(arguments[index + 1]));
            break;
        }
        if ((_wcsicmp(argument.c_str(), L"-m") == 0
                || _wcsicmp(argument.c_str(), L"--module") == 0) && index + 1 < argumentCount) {
            result.append(arguments[index + 1]);
            break;
        }
        if ((_wcsicmp(argument.c_str(), L"-cp") == 0
                || _wcsicmp(argument.c_str(), L"-classpath") == 0
                || _wcsicmp(argument.c_str(), L"--class-path") == 0
                || _wcsicmp(argument.c_str(), L"-p") == 0
                || _wcsicmp(argument.c_str(), L"--module-path") == 0
                || _wcsicmp(argument.c_str(), L"--upgrade-module-path") == 0) && index + 1 < argumentCount) {
            ++index;
            continue;
        }
        if (!argument.empty() && argument.front() == L'-') continue;
        result.append(argument);
        break;
    }
    LocalFree(arguments);
    return result.empty() ? L"<unknown>" : result;
}

std::wstring FullPath(const std::wstring& path) {
    DWORD required = GetFullPathNameW(path.c_str(), 0, nullptr, nullptr);
    if (required == 0) return path;
    std::wstring result(required, L'\0');
    DWORD written = GetFullPathNameW(path.c_str(), required, &result[0], nullptr);
    if (written == 0 || written >= required) return path;
    result.resize(written);
    return result;
}

std::uintptr_t FindRemoteModule(DWORD pid, const std::wstring& moduleNameOrPath, bool compareFullPath) {
    Handle snapshot(CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid));
    if (!snapshot) return 0;
    const std::wstring expected = compareFullPath ? FullPath(moduleNameOrPath) : BaseName(moduleNameOrPath);
    MODULEENTRY32W module{};
    module.dwSize = sizeof(module);
    if (!Module32FirstW(snapshot.get(), &module)) return 0;
    do {
        const wchar_t* actual = compareFullPath ? module.szExePath : module.szModule;
        if (_wcsicmp(actual, expected.c_str()) == 0) {
            return reinterpret_cast<std::uintptr_t>(module.modBaseAddr);
        }
    } while (Module32NextW(snapshot.get(), &module));
    return 0;
}

LPTHREAD_START_ROUTINE ResolveRemoteProcedure(DWORD pid, FARPROC localProcedure) {
    HMODULE owner = nullptr;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(localProcedure), &owner)) {
        return nullptr;
    }
    wchar_t ownerPath[MAX_PATH]{};
    if (GetModuleFileNameW(owner, ownerPath, MAX_PATH) == 0) return nullptr;
    const std::uintptr_t remoteBase = FindRemoteModule(pid, ownerPath, false);
    if (remoteBase == 0) return nullptr;
    const std::uintptr_t offset = reinterpret_cast<std::uintptr_t>(localProcedure)
        - reinterpret_cast<std::uintptr_t>(owner);
    return reinterpret_cast<LPTHREAD_START_ROUTINE>(remoteBase + offset);
}

bool WriteRemote(HANDLE process, LPVOID destination, const void* source, SIZE_T size) {
    SIZE_T written = 0;
    return WriteProcessMemory(process, destination, source, size, &written) != FALSE && written == size;
}

DWORD NtStatusToWindowsError(NTSTATUS status) {
    using RtlNtStatusToDosErrorFunction = ULONG(WINAPI*)(NTSTATUS);
    const auto convert = reinterpret_cast<RtlNtStatusToDosErrorFunction>(
        GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlNtStatusToDosError"));
    if (convert == nullptr) return ERROR_GEN_FAILURE;
    const ULONG error = convert(status);
    return error == ERROR_SUCCESS ? ERROR_GEN_FAILURE : static_cast<DWORD>(error);
}

HANDLE StartRemoteThread(HANDLE process, LPTHREAD_START_ROUTINE start, void* argument) {
    using NtCreateThreadExFunction = NTSTATUS(NTAPI*)(PHANDLE, ACCESS_MASK, PVOID, HANDLE,
        PVOID, PVOID, ULONG, SIZE_T, SIZE_T, SIZE_T, PVOID);
    const auto createThread = reinterpret_cast<NtCreateThreadExFunction>(
        GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtCreateThreadEx"));
    if (createThread == nullptr || start == nullptr) {
        SetLastError(createThread == nullptr ? ERROR_PROC_NOT_FOUND : ERROR_INVALID_ADDRESS);
        return nullptr;
    }
    HANDLE thread = nullptr;
    const NTSTATUS status = createThread(&thread, THREAD_QUERY_INFORMATION | SYNCHRONIZE,
        nullptr, process,
        reinterpret_cast<void*>(start), argument, 0, 0, 0, 0, nullptr);
    if (status >= 0 && thread != nullptr) return thread;
    if (thread != nullptr) CloseHandle(thread);
    SetLastError(NtStatusToWindowsError(status));
    return nullptr;
}

std::uintptr_t PublishedInjectorBase(HANDLE process, DWORD pid) {
    wchar_t name[96]{};
    swprintf_s(name, L"Local\\JVMRTDP.Injector.%08lx", pid);
    Handle mapping(OpenFileMappingW(FILE_MAP_READ, FALSE, name));
    if (!mapping) return 0;
    void* view = MapViewOfFile(mapping.get(), FILE_MAP_READ, 0, 0, sizeof(std::uintptr_t));
    if (view == nullptr) return 0;
    const std::uintptr_t base = *static_cast<const std::uintptr_t*>(view);
    UnmapViewOfFile(view);
    if (base == 0) return 0;
    MEMORY_BASIC_INFORMATION memory{};
    if (VirtualQueryEx(process, reinterpret_cast<const void*>(base), &memory, sizeof(memory)) != sizeof(memory)
        || memory.State != MEM_COMMIT || reinterpret_cast<std::uintptr_t>(memory.AllocationBase) != base) return 0;
    return base;
}

jlong JNICALL NativeCurrentProcessId(JNIEnv*, jclass) noexcept {
    return static_cast<jlong>(GetCurrentProcessId());
}

jlongArray JNICALL NativeListJvmProcessIds(JNIEnv* env, jclass) noexcept {
    const std::vector<DWORD> processes = ListJvmProcesses();
    jlongArray result = env->NewLongArray(static_cast<jsize>(processes.size()));
    if (result == nullptr || processes.empty()) return result;
    std::vector<jlong> ids;
    ids.reserve(processes.size());
    for (DWORD pid : processes) ids.push_back(static_cast<jlong>(pid));
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(ids.size()), ids.data());
    return result;
}

jboolean JNICALL NativeIsProcessAlive(JNIEnv*, jclass, jlong pid) noexcept {
    if (pid <= 0 || pid > UINT32_MAX) return JNI_FALSE;
    Handle process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, FALSE, static_cast<DWORD>(pid)));
    if (!process) return JNI_FALSE;
    DWORD exitCode = 0;
    return GetExitCodeProcess(process.get(), &exitCode) != FALSE && exitCode == STILL_ACTIVE ? JNI_TRUE : JNI_FALSE;
}

const char* MachineName(USHORT machine) noexcept {
    switch (machine) {
    case IMAGE_FILE_MACHINE_I386: return "x86";
    case IMAGE_FILE_MACHINE_AMD64: return "x86_64";
    case IMAGE_FILE_MACHINE_ARM64: return "aarch64";
    default: return "unknown";
    }
}

jstring JNICALL NativeProcessArchitecture(JNIEnv* env, jclass, jlong pid) noexcept {
    if (pid <= 0 || pid > UINT32_MAX) return env->NewStringUTF("unknown");
    Handle process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, static_cast<DWORD>(pid)));
    if (!process) return env->NewStringUTF("unknown");
    using IsWow64Process2Function = BOOL(WINAPI*)(HANDLE, USHORT*, USHORT*);
    const auto isWow64Process2 = reinterpret_cast<IsWow64Process2Function>(
        GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "IsWow64Process2"));
    const char* architecture = "unknown";
    if (isWow64Process2 != nullptr) {
        USHORT processMachine = IMAGE_FILE_MACHINE_UNKNOWN;
        USHORT nativeMachine = IMAGE_FILE_MACHINE_UNKNOWN;
        if (isWow64Process2(process.get(), &processMachine, &nativeMachine) != FALSE) {
            architecture = MachineName(processMachine == IMAGE_FILE_MACHINE_UNKNOWN ? nativeMachine : processMachine);
        }
    }
    return env->NewStringUTF(architecture);
}

jstring JNICALL NativeProcessDisplayName(JNIEnv* env, jclass, jlong pid) noexcept {
    if (pid <= 0 || pid > UINT32_MAX) return env->NewStringUTF("");
    Handle process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, static_cast<DWORD>(pid)));
    if (!process) return env->NewStringUTF("");
    std::wstring path(32768, L'\0');
    DWORD length = static_cast<DWORD>(path.size());
    if (!QueryFullProcessImageNameW(process.get(), 0, &path[0], &length)) return env->NewStringUTF("");
    path.resize(length);
    const std::wstring executableName = BaseName(path);
    const std::wstring commandLine = ProcessCommandLine(process.get());
    const std::wstring displayName = commandLine.empty()
        ? executableName : JavaLaunchIdentity(executableName, commandLine);
    return env->NewString(
        reinterpret_cast<const jchar*>(displayName.data()), static_cast<jsize>(displayName.size()));
}

jstring JNICALL NativeProcessExecutableName(JNIEnv* env, jclass, jlong pid) noexcept {
    if (pid <= 0 || pid > UINT32_MAX) return env->NewStringUTF("");
    Handle process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, static_cast<DWORD>(pid)));
    if (!process) return env->NewStringUTF("");
    std::wstring path(32768, L'\0');
    DWORD length = static_cast<DWORD>(path.size());
    if (!QueryFullProcessImageNameW(process.get(), 0, &path[0], &length)) return env->NewStringUTF("");
    path.resize(length);
    const std::wstring name = BaseName(path);
    return env->NewString(reinterpret_cast<const jchar*>(name.data()), static_cast<jsize>(name.size()));
}

jstring JNICALL NativeProcessWindowTitle(JNIEnv* env, jclass, jlong pid) noexcept {
    if (pid <= 0 || pid > UINT32_MAX) return env->NewStringUTF("");
    const std::wstring title = ProcessWindowTitle(static_cast<DWORD>(pid));
    if (title.empty()) return env->NewStringUTF("");
    return env->NewString(reinterpret_cast<const jchar*>(title.data()), static_cast<jsize>(title.size()));
}

jlong JNICALL NativeProcessStartTimeMillis(JNIEnv*, jclass, jlong pid) noexcept {
    if (pid <= 0 || pid > UINT32_MAX) return 0;
    Handle process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, static_cast<DWORD>(pid)));
    if (!process) return 0;
    FILETIME creation{}, exit{}, kernel{}, user{};
    if (!GetProcessTimes(process.get(), &creation, &exit, &kernel, &user)) return 0;
    ULARGE_INTEGER ticks{};
    ticks.LowPart = creation.dwLowDateTime;
    ticks.HighPart = creation.dwHighDateTime;
    constexpr unsigned long long kEpochDifference100ns = 116444736000000000ULL;
    if (ticks.QuadPart < kEpochDifference100ns) return 0;
    return static_cast<jlong>((ticks.QuadPart - kEpochDifference100ns) / 10000ULL);
}

void JNICALL NativeInject(JNIEnv* env, jclass, jlong pidValue, jstring dllPathValue,
        jstring jarPathValue, jstring optionsValue, jlong timeoutMillis) noexcept {
    if (pidValue <= 0 || pidValue > UINT32_MAX) {
        ThrowInjectionException(env, L"Invalid target process ID");
        return;
    }
    const DWORD pid = static_cast<DWORD>(pidValue);
    const std::wstring dllPath = FullPath(JStringToWide(env, dllPathValue));
    const std::wstring jarPath = FullPath(JStringToWide(env, jarPathValue));
    const std::string options = JStringToUtf8(env, optionsValue);
    if (dllPath.empty() || jarPath.empty() || options.empty()
        || jarPath.size() >= kBootstrapJarPathCapacity || options.size() >= kBootstrapOptionsCapacity) {
        ThrowInjectionException(env, L"Invalid or excessively long injection parameters");
        return;
    }

    constexpr DWORD access = PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION
        | PROCESS_VM_WRITE | PROCESS_VM_READ | SYNCHRONIZE;
    Handle process(OpenProcess(access, FALSE, pid));
    if (!process) {
        ThrowInjectionException(env, WindowsError(L"Cannot open target process"));
        return;
    }

    const DWORD nativeTimeout = static_cast<DWORD>(std::min<jlong>(timeoutMillis, MAXDWORD - 1));
    std::uintptr_t remoteInjector = PublishedInjectorBase(process.get(), pid);
    if (remoteInjector == 0) {
        jvmrtdp::injection::ManualMapResult mapped{};
        std::wstring mappingError;
        if (!jvmrtdp::injection::ManualMapLibrary(
                process.get(), pid, dllPath, nativeTimeout, &mapped, &mappingError)) {
            ThrowInjectionException(env, mappingError.empty()
                ? L"Cannot manually map the injector DLL" : mappingError);
            return;
        }
        remoteInjector = reinterpret_cast<std::uintptr_t>(mapped.remoteImage);
    }
    HMODULE localInjector = nullptr;
    if (remoteInjector == 0 || !GetModuleHandleExW(
            GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(&JVMRTDP_Bootstrap), &localInjector)) {
        ThrowInjectionException(env, L"Cannot locate the loaded injector DLL");
        return;
    }
    const std::uintptr_t bootstrapOffset = reinterpret_cast<std::uintptr_t>(&JVMRTDP_Bootstrap)
        - reinterpret_cast<std::uintptr_t>(localInjector);
    auto remoteBootstrap = reinterpret_cast<LPTHREAD_START_ROUTINE>(remoteInjector + bootstrapOffset);

    BootstrapParameters parameters{};
    parameters.magic = kBootstrapMagic;
    parameters.version = kBootstrapVersion;
    parameters.status = BootstrapStatus::Pending;
    wcscpy_s(parameters.jarPath, jarPath.c_str());
    strcpy_s(parameters.options, options.c_str());
    RemoteMemory remoteParameters(process.get(), sizeof(parameters));
    if (remoteParameters.get() == nullptr
        || !WriteRemote(process.get(), remoteParameters.get(), &parameters, sizeof(parameters))) {
        ThrowInjectionException(env, WindowsError(L"Cannot write bootstrap parameters to the target process"));
        return;
    }

    Handle bootstrapThread(StartRemoteThread(process.get(), remoteBootstrap, remoteParameters.get()));
    if (!bootstrapThread) {
        ThrowInjectionException(env, WindowsError(L"Cannot create the remote JVM bootstrap thread with NtCreateThreadEx"));
        return;
    }
    DWORD wait = WaitForSingleObject(bootstrapThread.get(), nativeTimeout);
    if (wait == WAIT_TIMEOUT) {
        remoteParameters.release(); // The target thread still owns this memory.
        ThrowInjectionException(env, L"Timed out while starting JVMRTDP inside the target JVM");
        return;
    }
    if (wait != WAIT_OBJECT_0) {
        remoteParameters.release();
        ThrowInjectionException(env, WindowsError(L"Waiting for the JVM bootstrap thread failed"));
        return;
    }

    SIZE_T read = 0;
    if (!ReadProcessMemory(process.get(), remoteParameters.get(), &parameters, sizeof(parameters), &read)
        || read != sizeof(parameters)) {
        ThrowInjectionException(env, WindowsError(L"Cannot read the JVM bootstrap result"));
        return;
    }
    if (parameters.status != BootstrapStatus::Success) {
        std::wstring message = L"Target JVM bootstrap failed";
        if (parameters.error[0] != L'\0') message.append(L": ").append(parameters.error);
        ThrowInjectionException(env, message);
    }
}

JNINativeMethod kMethods[] = {
    {const_cast<char*>("nativeCurrentProcessId"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(&NativeCurrentProcessId)},
    {const_cast<char*>("nativeListJvmProcessIds"), const_cast<char*>("()[J"),
     reinterpret_cast<void*>(&NativeListJvmProcessIds)},
    {const_cast<char*>("nativeIsProcessAlive"), const_cast<char*>("(J)Z"),
     reinterpret_cast<void*>(&NativeIsProcessAlive)},
    {const_cast<char*>("nativeProcessArchitecture"), const_cast<char*>("(J)Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeProcessArchitecture)},
    {const_cast<char*>("nativeProcessDisplayName"), const_cast<char*>("(J)Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeProcessDisplayName)},
    {const_cast<char*>("nativeProcessExecutableName"), const_cast<char*>("(J)Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeProcessExecutableName)},
    {const_cast<char*>("nativeProcessWindowTitle"), const_cast<char*>("(J)Ljava/lang/String;"),
     reinterpret_cast<void*>(&NativeProcessWindowTitle)},
    {const_cast<char*>("nativeProcessStartTimeMillis"), const_cast<char*>("(J)J"),
     reinterpret_cast<void*>(&NativeProcessStartTimeMillis)},
    {const_cast<char*>("nativeInject"), const_cast<char*>("(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V"),
     reinterpret_cast<void*>(&NativeInject)},
};

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK || env == nullptr) return JNI_ERR;
    jclass bindingClass = env->FindClass(kBindingClass.data());
    if (bindingClass == nullptr) return JNI_ERR;
    const jint result = env->RegisterNatives(bindingClass, kMethods,
        static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0])));
    env->DeleteLocalRef(bindingClass);
    return result == JNI_OK ? JNI_VERSION_1_8 : JNI_ERR;
}
