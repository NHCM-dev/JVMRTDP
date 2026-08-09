#include "pch.h"

#include <cstdint>
#include <cwchar>

namespace {

HANDLE gManualMapMarker = nullptr;
void* gManualMapMarkerView = nullptr;

void PublishModuleBase(HMODULE module) noexcept {
    wchar_t name[96]{};
    swprintf_s(name, L"Local\\JVMRTDP.Injector.%08lx", GetCurrentProcessId());
    gManualMapMarker = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE,
        0, static_cast<DWORD>(sizeof(std::uintptr_t)), name);
    if (gManualMapMarker == nullptr) return;
    gManualMapMarkerView = MapViewOfFile(gManualMapMarker, FILE_MAP_WRITE, 0, 0, sizeof(std::uintptr_t));
    if (gManualMapMarkerView != nullptr) {
        *static_cast<std::uintptr_t*>(gManualMapMarkerView) = reinterpret_cast<std::uintptr_t>(module);
    }
}

void ReleaseModuleBaseMarker() noexcept {
    if (gManualMapMarkerView != nullptr) UnmapViewOfFile(gManualMapMarkerView);
    if (gManualMapMarker != nullptr) CloseHandle(gManualMapMarker);
    gManualMapMarkerView = nullptr;
    gManualMapMarker = nullptr;
}

} // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(module);
        PublishModuleBase(module);
    } else if (reason == DLL_PROCESS_DETACH) {
        ReleaseModuleBaseMarker();
    }
    return TRUE;
}
