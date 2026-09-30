#pragma once
#include <sys/ioctl.h>
#include <cstdint>

// Linux usbfs UAPI layout used by Android's USB host file descriptor.
// This intentionally mirrors include/uapi/linux/usbdevice_fs.h for arm64.
namespace stream4k60::usbfs {
struct IsoPacketDesc {
    uint32_t length;
    uint32_t actual_length;
    uint32_t status;
};

struct Urb {
    uint8_t type;
    uint8_t endpoint;
    int32_t status;
    uint32_t flags;
    void* buffer;
    int32_t buffer_length;
    int32_t actual_length;
    int32_t start_frame;
    union {
        int32_t number_of_packets;
        uint32_t stream_id;
    };
    int32_t error_count;
    uint32_t signr;
    void* usercontext;
};

constexpr uint8_t URB_TYPE_ISO = 0;
constexpr uint8_t URB_TYPE_BULK = 3;
constexpr uint32_t URB_ISO_ASAP = 0x02;

#ifndef USBDEVFS_SUBMITURB
#define USBDEVFS_SUBMITURB _IOR('U', 10, stream4k60::usbfs::Urb)
#endif
#ifndef USBDEVFS_DISCARDURB
#define USBDEVFS_DISCARDURB _IO('U', 11)
#endif
#ifndef USBDEVFS_REAPURBNDELAY
#define USBDEVFS_REAPURBNDELAY _IOW('U', 13, void*)
#endif

static_assert(sizeof(Urb) == (sizeof(void*) == 8 ? 56 : 32), "Unexpected usbfs URB ABI size");
static_assert(sizeof(IsoPacketDesc) == 12, "Unexpected usbfs ISO packet ABI size");
} // namespace stream4k60::usbfs
