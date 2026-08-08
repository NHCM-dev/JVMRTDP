#pragma once

#include <Windows.h>

#include <cstdint>
#include <cstddef>

constexpr std::uint32_t kBootstrapMagic = 0x4A524442; // "JRDB"
constexpr std::uint32_t kBootstrapVersion = 1;
constexpr std::size_t kBootstrapJarPathCapacity = 32768;
constexpr std::size_t kBootstrapOptionsCapacity = 2048;
constexpr std::size_t kBootstrapErrorCapacity = 1024;

enum class BootstrapStatus : std::uint32_t {
    Pending = 0,
    Success = 1,
    InvalidParameters = 2,
    JvmNotFound = 3,
    AttachThreadFailed = 4,
    JavaBootstrapFailed = 5,
};

struct BootstrapParameters {
    std::uint32_t magic;
    std::uint32_t version;
    BootstrapStatus status;
    wchar_t jarPath[kBootstrapJarPathCapacity];
    char options[kBootstrapOptionsCapacity];
    wchar_t error[kBootstrapErrorCapacity];
};

extern "C" __declspec(dllexport) DWORD WINAPI JVMRTDP_Bootstrap(BootstrapParameters* parameters) noexcept;
