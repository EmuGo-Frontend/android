#pragma once

#include <cstdint>
#include <string>

#include "libretro.h"

// Libretro VFS for the host. Paths registered with map() are served from file descriptors
// EmuGo! opened through Android's Storage Access Framework, so cores can read ROMs and disc
// images in place without storage permissions. Everything else goes to the real filesystem.
namespace vfs {
void map(const std::string& path, int fd);
void clear();
retro_vfs_interface* interface();

retro_vfs_file_handle* open(const char* path, unsigned mode, unsigned hints);
int close(retro_vfs_file_handle* file);
int64_t seek(retro_vfs_file_handle* file, int64_t offset, int whence);
int64_t tell(retro_vfs_file_handle* file);
int64_t read(retro_vfs_file_handle* file, void* buffer, uint64_t length);
}  // namespace vfs
