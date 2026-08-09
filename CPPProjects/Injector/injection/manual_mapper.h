#pragma once

#include <Windows.h>

#include <string>

namespace jvmrtdp::injection {

struct ManualMapResult final {
    void* remoteImage = nullptr;
};

bool ManualMapLibrary(HANDLE process, DWORD processId, const std::wstring& dllPath,
    DWORD timeoutMillis, ManualMapResult* result, std::wstring* error);

} // namespace jvmrtdp::injection
