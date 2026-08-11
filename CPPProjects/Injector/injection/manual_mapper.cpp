#include "pch.h"
#include "injection/manual_mapper.h"

#include <TlHelp32.h>
#include <winternl.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>
#include <vector>

namespace jvmrtdp::injection {
namespace {

using NtCreateThreadExFunction = NTSTATUS(NTAPI*)(PHANDLE, ACCESS_MASK, PVOID, HANDLE,
    PVOID, PVOID, ULONG, SIZE_T, SIZE_T, SIZE_T, PVOID);

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

std::wstring ErrorText(const wchar_t* operation, DWORD code = GetLastError()) {
    return std::wstring(operation) + L" (Windows error " + std::to_wstring(code) + L")";
}

bool RangeFits(std::size_t offset, std::size_t size, std::size_t capacity) noexcept {
    return offset <= capacity && size <= capacity - offset;
}

const IMAGE_NT_HEADERS64* NtHeaders(const unsigned char* bytes, std::size_t size) noexcept {
    if (bytes == nullptr || size < sizeof(IMAGE_DOS_HEADER)) return nullptr;
    const auto* dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(bytes);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE || dos->e_lfanew <= 0
        || !RangeFits(static_cast<std::size_t>(dos->e_lfanew), sizeof(IMAGE_NT_HEADERS64), size)) return nullptr;
    const auto* nt = reinterpret_cast<const IMAGE_NT_HEADERS64*>(bytes + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE || nt->OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC
        || nt->FileHeader.Machine != IMAGE_FILE_MACHINE_AMD64) return nullptr;
    return nt;
}

std::uintptr_t RemoteModuleBase(DWORD processId, const wchar_t* name) {
    Handle snapshot(CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, processId));
    if (!snapshot) return 0;
    MODULEENTRY32W module{};
    module.dwSize = sizeof(module);
    if (!Module32FirstW(snapshot.get(), &module)) return 0;
    do {
        if (_wcsicmp(module.szModule, name) == 0) {
            return reinterpret_cast<std::uintptr_t>(module.modBaseAddr);
        }
    } while (Module32NextW(snapshot.get(), &module));
    return 0;
}

void* RemoteProcedure(DWORD processId, FARPROC localProcedure) {
    if (localProcedure == nullptr) return nullptr;
    HMODULE owner = nullptr;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
            | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(localProcedure), &owner)) return nullptr;
    wchar_t ownerPath[MAX_PATH]{};
    if (GetModuleFileNameW(owner, ownerPath, MAX_PATH) == 0) return nullptr;
    const wchar_t* separator = wcsrchr(ownerPath, L'\\');
    const wchar_t* ownerName = separator == nullptr ? ownerPath : separator + 1;
    const std::uintptr_t remoteBase = RemoteModuleBase(processId, ownerName);
    if (remoteBase == 0) return nullptr;
    const std::uintptr_t offset = reinterpret_cast<std::uintptr_t>(localProcedure)
        - reinterpret_cast<std::uintptr_t>(owner);
    return reinterpret_cast<void*>(remoteBase + offset);
}

NtCreateThreadExFunction ResolveThreadCreator() {
    return reinterpret_cast<NtCreateThreadExFunction>(
        GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtCreateThreadEx"));
}

DWORD NtStatusToWindowsError(NTSTATUS status) {
    using RtlNtStatusToDosErrorFunction = ULONG(WINAPI*)(NTSTATUS);
    const auto convert = reinterpret_cast<RtlNtStatusToDosErrorFunction>(
        GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlNtStatusToDosError"));
    if (convert == nullptr) return ERROR_GEN_FAILURE;
    const ULONG error = convert(status);
    return error == ERROR_SUCCESS ? ERROR_GEN_FAILURE : static_cast<DWORD>(error);
}

HANDLE StartRemoteThread(HANDLE process, void* start, void* argument) {
    const auto createThread = ResolveThreadCreator();
    if (createThread == nullptr || start == nullptr) {
        SetLastError(createThread == nullptr ? ERROR_PROC_NOT_FOUND : ERROR_INVALID_ADDRESS);
        return nullptr;
    }
    HANDLE thread = nullptr;
    const NTSTATUS status = createThread(&thread, THREAD_QUERY_INFORMATION | SYNCHRONIZE,
        nullptr, process,
        start, argument, 0, 0, 0, 0, nullptr);
    if (status >= 0 && thread != nullptr) return thread;
    if (thread != nullptr) CloseHandle(thread);
    SetLastError(NtStatusToWindowsError(status));
    return nullptr;
}

bool WaitForThread(HANDLE thread, DWORD timeoutMillis, DWORD* exitCode) {
    if (WaitForSingleObject(thread, timeoutMillis) != WAIT_OBJECT_0) return false;
    return GetExitCodeThread(thread, exitCode) != FALSE;
}

bool WriteRemote(HANDLE process, void* destination, const void* source, SIZE_T size) {
    SIZE_T written = 0;
    return WriteProcessMemory(process, destination, source, size, &written) != FALSE && written == size;
}

bool RemoteLoadDependency(HANDLE process, DWORD processId, const wchar_t* name,
        DWORD timeoutMillis, std::uintptr_t* moduleBase) {
    const SIZE_T bytes = (wcslen(name) + 1) * sizeof(wchar_t);
    void* remoteName = VirtualAllocEx(process, nullptr, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (remoteName == nullptr) return false;
    bool success = false;
    if (WriteRemote(process, remoteName, name, bytes)) {
        FARPROC localLoadLibrary = GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "LoadLibraryW");
        void* remoteLoadLibrary = RemoteProcedure(processId, localLoadLibrary);
        Handle thread(StartRemoteThread(process, remoteLoadLibrary, remoteName));
        DWORD ignored = 0;
        success = thread && WaitForThread(thread.get(), timeoutMillis, &ignored);
    }
    VirtualFreeEx(process, remoteName, 0, MEM_RELEASE);
    if (!success) return false;
    *moduleBase = RemoteModuleBase(processId, name);
    return *moduleBase != 0;
}

bool ApplyRelocations(std::vector<unsigned char>& image, const IMAGE_NT_HEADERS64* nt,
        std::uintptr_t remoteBase) {
    const std::intptr_t delta = static_cast<std::intptr_t>(remoteBase - nt->OptionalHeader.ImageBase);
    if (delta == 0) return true;
    const auto directory = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_BASERELOC];
    if (directory.VirtualAddress == 0 || directory.Size < sizeof(IMAGE_BASE_RELOCATION)
        || !RangeFits(directory.VirtualAddress, directory.Size, image.size())) return false;
    std::size_t cursor = directory.VirtualAddress;
    const std::size_t end = cursor + directory.Size;
    while (cursor < end) {
        auto* block = reinterpret_cast<IMAGE_BASE_RELOCATION*>(image.data() + cursor);
        if (block->SizeOfBlock < sizeof(*block) || block->SizeOfBlock > end - cursor) return false;
        const std::size_t count = (block->SizeOfBlock - sizeof(*block)) / sizeof(WORD);
        const WORD* entries = reinterpret_cast<const WORD*>(block + 1);
        for (std::size_t index = 0; index < count; ++index) {
            const WORD type = entries[index] >> 12;
            const WORD offset = entries[index] & 0x0fff;
            const std::size_t rva = static_cast<std::size_t>(block->VirtualAddress) + offset;
            if (type == IMAGE_REL_BASED_ABSOLUTE) continue;
            if (type != IMAGE_REL_BASED_DIR64 || !RangeFits(rva, sizeof(std::uint64_t), image.size())) return false;
            *reinterpret_cast<std::uint64_t*>(image.data() + rva) += static_cast<std::uint64_t>(delta);
        }
        cursor += block->SizeOfBlock;
    }
    return true;
}

bool ResolveImports(HANDLE process, DWORD processId, std::vector<unsigned char>& image,
        const IMAGE_NT_HEADERS64* nt, DWORD timeoutMillis) {
    const auto directory = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (directory.VirtualAddress == 0 || directory.Size == 0) return true;
    if (!RangeFits(directory.VirtualAddress, sizeof(IMAGE_IMPORT_DESCRIPTOR), image.size())) return false;
    auto* descriptor = reinterpret_cast<IMAGE_IMPORT_DESCRIPTOR*>(image.data() + directory.VirtualAddress);
    for (; descriptor->Name != 0; ++descriptor) {
        if (!RangeFits(descriptor->Name, 1, image.size())) return false;
        const char* moduleName = reinterpret_cast<const char*>(image.data() + descriptor->Name);
        const std::size_t remaining = image.size() - descriptor->Name;
        if (memchr(moduleName, '\0', remaining) == nullptr) return false;
        wchar_t wideName[MAX_PATH]{};
        if (MultiByteToWideChar(CP_ACP, 0, moduleName, -1, wideName, MAX_PATH) == 0) return false;
        std::uintptr_t remoteModule = RemoteModuleBase(processId, wideName);
        const bool apiSet = _strnicmp(moduleName, "api-ms-win-", 11) == 0
            || _strnicmp(moduleName, "ext-ms-win-", 11) == 0;
        if (remoteModule == 0 && !RemoteLoadDependency(
                process, processId, wideName, timeoutMillis, &remoteModule) && !apiSet) return false;

        HMODULE localModule = GetModuleHandleA(moduleName);
        bool loadedHere = false;
        if (localModule == nullptr) {
            localModule = LoadLibraryA(moduleName);
            loadedHere = localModule != nullptr;
        }
        if (localModule == nullptr) return false;
        const DWORD lookupRva = descriptor->OriginalFirstThunk != 0
            ? descriptor->OriginalFirstThunk : descriptor->FirstThunk;
        if (!RangeFits(lookupRva, sizeof(IMAGE_THUNK_DATA64), image.size())
            || !RangeFits(descriptor->FirstThunk, sizeof(IMAGE_THUNK_DATA64), image.size())) {
            if (loadedHere) FreeLibrary(localModule);
            return false;
        }
        auto* lookup = reinterpret_cast<IMAGE_THUNK_DATA64*>(image.data() + lookupRva);
        auto* address = reinterpret_cast<IMAGE_THUNK_DATA64*>(image.data() + descriptor->FirstThunk);
        for (; lookup->u1.AddressOfData != 0; ++lookup, ++address) {
            FARPROC localFunction = nullptr;
            if (IMAGE_SNAP_BY_ORDINAL64(lookup->u1.Ordinal)) {
                localFunction = GetProcAddress(localModule,
                    MAKEINTRESOURCEA(IMAGE_ORDINAL64(lookup->u1.Ordinal)));
            } else {
                const std::size_t nameRva = static_cast<std::size_t>(lookup->u1.AddressOfData);
                if (!RangeFits(nameRva, sizeof(IMAGE_IMPORT_BY_NAME), image.size())) {
                    if (loadedHere) FreeLibrary(localModule);
                    return false;
                }
                const auto* importName = reinterpret_cast<const IMAGE_IMPORT_BY_NAME*>(image.data() + nameRva);
                localFunction = GetProcAddress(localModule, importName->Name);
            }
            void* remoteFunction = RemoteProcedure(processId, localFunction);
            if (remoteFunction == nullptr) {
                if (loadedHere) FreeLibrary(localModule);
                return false;
            }
            address->u1.Function = reinterpret_cast<ULONGLONG>(remoteFunction);
        }
        if (loadedHere) FreeLibrary(localModule);
    }
    return true;
}

DWORD SectionProtection(DWORD characteristics) noexcept {
    const bool execute = (characteristics & IMAGE_SCN_MEM_EXECUTE) != 0;
    const bool read = (characteristics & IMAGE_SCN_MEM_READ) != 0;
    const bool write = (characteristics & IMAGE_SCN_MEM_WRITE) != 0;
    if (execute && write) return PAGE_EXECUTE_READWRITE;
    if (execute && read) return PAGE_EXECUTE_READ;
    if (execute) return PAGE_EXECUTE;
    if (read && write) return PAGE_READWRITE;
    if (read) return PAGE_READONLY;
    if (write) return PAGE_READWRITE;
    return PAGE_NOACCESS;
}

void EmitByte(std::vector<unsigned char>& code, unsigned char value) { code.push_back(value); }
void Emit32(std::vector<unsigned char>& code, std::uint32_t value) {
    const auto* bytes = reinterpret_cast<const unsigned char*>(&value);
    code.insert(code.end(), bytes, bytes + sizeof(value));
}
void Emit64(std::vector<unsigned char>& code, std::uint64_t value) {
    const auto* bytes = reinterpret_cast<const unsigned char*>(&value);
    code.insert(code.end(), bytes, bytes + sizeof(value));
}
void EmitCall3(std::vector<unsigned char>& code, std::uint64_t function,
        std::uint64_t first, std::uint32_t second, std::uint64_t third) {
    EmitByte(code, 0x48); EmitByte(code, 0xB9); Emit64(code, first);       // mov rcx, imm64
    EmitByte(code, 0xBA); Emit32(code, second);                          // mov edx, imm32
    EmitByte(code, 0x49); EmitByte(code, 0xB8); Emit64(code, third);      // mov r8, imm64
    EmitByte(code, 0x48); EmitByte(code, 0xB8); Emit64(code, function);   // mov rax, imm64
    EmitByte(code, 0xFF); EmitByte(code, 0xD0);                           // call rax
}

std::vector<std::uint64_t> TlsCallbacks(const std::vector<unsigned char>& image,
        const IMAGE_NT_HEADERS64* nt, std::uintptr_t remoteBase) {
    std::vector<std::uint64_t> result;
    const auto directory = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_TLS];
    if (directory.VirtualAddress == 0 || directory.Size < sizeof(IMAGE_TLS_DIRECTORY64)
        || !RangeFits(directory.VirtualAddress, sizeof(IMAGE_TLS_DIRECTORY64), image.size())) return result;
    const auto* tls = reinterpret_cast<const IMAGE_TLS_DIRECTORY64*>(image.data() + directory.VirtualAddress);
    if (tls->AddressOfCallBacks == 0 || tls->AddressOfCallBacks < remoteBase) return result;
    std::size_t callbackRva = static_cast<std::size_t>(tls->AddressOfCallBacks - remoteBase);
    for (std::size_t count = 0; count < 128 && RangeFits(callbackRva, sizeof(std::uint64_t), image.size()); ++count) {
        const std::uint64_t callback = *reinterpret_cast<const std::uint64_t*>(image.data() + callbackRva);
        if (callback == 0) break;
        result.push_back(callback);
        callbackRva += sizeof(std::uint64_t);
    }
    return result;
}

bool RunMappedEntry(HANDLE process, DWORD processId, void* remoteImage,
        const std::vector<unsigned char>& image, const IMAGE_NT_HEADERS64* nt,
        DWORD timeoutMillis) {
    std::vector<unsigned char> code{0x48, 0x83, 0xEC, 0x28}; // sub rsp, 28h
    const auto exceptionDirectory = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXCEPTION];
    if (exceptionDirectory.VirtualAddress != 0 && exceptionDirectory.Size >= sizeof(RUNTIME_FUNCTION)) {
        FARPROC localAddFunctionTable = GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "RtlAddFunctionTable");
        void* remoteAddFunctionTable = RemoteProcedure(processId, localAddFunctionTable);
        if (remoteAddFunctionTable == nullptr) return false;
        EmitCall3(code, reinterpret_cast<std::uint64_t>(remoteAddFunctionTable),
            reinterpret_cast<std::uint64_t>(remoteImage) + exceptionDirectory.VirtualAddress,
            exceptionDirectory.Size / sizeof(RUNTIME_FUNCTION), reinterpret_cast<std::uint64_t>(remoteImage));
    }
    for (const std::uint64_t callback : TlsCallbacks(image, nt, reinterpret_cast<std::uintptr_t>(remoteImage))) {
        EmitCall3(code, callback, reinterpret_cast<std::uint64_t>(remoteImage), DLL_PROCESS_ATTACH, 0);
    }
    EmitCall3(code,
        reinterpret_cast<std::uint64_t>(remoteImage) + nt->OptionalHeader.AddressOfEntryPoint,
        reinterpret_cast<std::uint64_t>(remoteImage), DLL_PROCESS_ATTACH, 0);
    code.insert(code.end(), {0x48, 0x83, 0xC4, 0x28, 0xC3}); // add rsp, 28h; ret

    void* remoteCode = VirtualAllocEx(process, nullptr, code.size(),
        MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (remoteCode == nullptr) return false;
    bool success = false;
    if (WriteRemote(process, remoteCode, code.data(), code.size())) {
        FlushInstructionCache(process, remoteCode, code.size());
        Handle thread(StartRemoteThread(process, remoteCode, nullptr));
        DWORD exitCode = 0;
        success = thread && WaitForThread(thread.get(), timeoutMillis, &exitCode) && exitCode != 0;
    }
    VirtualFreeEx(process, remoteCode, 0, MEM_RELEASE);
    return success;
}

} // namespace

bool ManualMapLibrary(HANDLE process, DWORD processId, const std::wstring& dllPath,
        DWORD timeoutMillis, ManualMapResult* result, std::wstring* error) {
    if (process == nullptr || result == nullptr) return false;
    Handle file(CreateFileW(dllPath.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
        OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
    if (!file) {
        if (error != nullptr) *error = ErrorText(L"Cannot open the injector DLL");
        return false;
    }
    LARGE_INTEGER length{};
    if (!GetFileSizeEx(file.get(), &length) || length.QuadPart <= 0
        || length.QuadPart > static_cast<LONGLONG>((std::numeric_limits<DWORD>::max)())) {
        if (error != nullptr) *error = L"The injector DLL has an invalid size";
        return false;
    }
    std::vector<unsigned char> fileBytes(static_cast<std::size_t>(length.QuadPart));
    DWORD read = 0;
    if (!ReadFile(file.get(), fileBytes.data(), static_cast<DWORD>(fileBytes.size()), &read, nullptr)
        || read != fileBytes.size()) {
        if (error != nullptr) *error = ErrorText(L"Cannot read the injector DLL");
        return false;
    }
    const IMAGE_NT_HEADERS64* fileNt = NtHeaders(fileBytes.data(), fileBytes.size());
    if (fileNt == nullptr || fileNt->OptionalHeader.SizeOfImage == 0
        || fileNt->OptionalHeader.SizeOfImage > 512u * 1024u * 1024u) {
        if (error != nullptr) *error = L"The injector DLL is not a valid x64 PE image";
        return false;
    }

    std::vector<unsigned char> image(fileNt->OptionalHeader.SizeOfImage, 0);
    const std::size_t headerSize = std::min<std::size_t>(fileNt->OptionalHeader.SizeOfHeaders, fileBytes.size());
    memcpy(image.data(), fileBytes.data(), headerSize);
    const IMAGE_SECTION_HEADER* section = IMAGE_FIRST_SECTION(fileNt);
    for (WORD index = 0; index < fileNt->FileHeader.NumberOfSections; ++index, ++section) {
        if (section->SizeOfRawData == 0) continue;
        if (!RangeFits(section->PointerToRawData, section->SizeOfRawData, fileBytes.size())
            || !RangeFits(section->VirtualAddress, section->SizeOfRawData, image.size())) {
            if (error != nullptr) *error = L"The injector DLL contains an invalid PE section";
            return false;
        }
        memcpy(image.data() + section->VirtualAddress,
            fileBytes.data() + section->PointerToRawData, section->SizeOfRawData);
    }

    void* remoteImage = VirtualAllocEx(process, nullptr, image.size(),
        MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (remoteImage == nullptr) {
        if (error != nullptr) *error = ErrorText(L"Cannot allocate the injector image in the target process");
        return false;
    }
    const IMAGE_NT_HEADERS64* imageNt = NtHeaders(image.data(), image.size());
    bool success = ApplyRelocations(image, imageNt, reinterpret_cast<std::uintptr_t>(remoteImage));
    if (!success && error != nullptr) *error = L"Cannot apply injector DLL base relocations";
    if (success) {
        success = ResolveImports(process, processId, image, imageNt, timeoutMillis);
        if (!success && error != nullptr) *error = L"Cannot resolve injector DLL imports in the target process";
    }
    if (success) {
        success = WriteRemote(process, remoteImage, image.data(), image.size());
        if (!success && error != nullptr) *error = ErrorText(L"Cannot write the mapped injector image");
    }
    if (success) {
        section = IMAGE_FIRST_SECTION(imageNt);
        for (WORD index = 0; index < imageNt->FileHeader.NumberOfSections; ++index, ++section) {
            if (section->Misc.VirtualSize == 0) continue;
            DWORD previous = 0;
            if (!VirtualProtectEx(process,
                    static_cast<unsigned char*>(remoteImage) + section->VirtualAddress,
                    section->Misc.VirtualSize, SectionProtection(section->Characteristics), &previous)) {
                success = false;
                if (error != nullptr) *error = ErrorText(L"Cannot protect a mapped injector section");
                break;
            }
        }
    }
    bool entryStarted = false;
    if (success) {
        FlushInstructionCache(process, remoteImage, image.size());
        entryStarted = true;
        success = RunMappedEntry(process, processId, remoteImage, image, imageNt, timeoutMillis);
        if (!success && error != nullptr) {
            *error = L"The manually mapped injector DLL entry point failed or timed out";
        }
    }
    if (!success) {
        // The entry stub may have registered the x64 unwind table or may still be
        // executing after a timeout. Keep the image mapped in that case rather
        // than leave the target with dangling runtime-function entries/code.
        if (!entryStarted) VirtualFreeEx(process, remoteImage, 0, MEM_RELEASE);
        if (error != nullptr && error->empty()) *error = L"Cannot initialize the manually mapped injector DLL";
        return false;
    }
    result->remoteImage = remoteImage;
    return true;
}

} // namespace jvmrtdp::injection
