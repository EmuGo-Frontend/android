#include "vfs.h"

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <map>
#include <mutex>

struct retro_vfs_file_handle {
  int fd;
  int64_t position;
  std::string path;
};

struct retro_vfs_dir_handle {
  DIR* dir;
  struct dirent* entry;
  std::string path;
};

namespace {
std::mutex lock;
std::map<std::string, int> mapped;  // path -> dup'ed descriptor owned by this module

int mappedFd(const char* path) {
  std::lock_guard<std::mutex> guard(lock);
  auto found = mapped.find(path);
  return found == mapped.end() ? -1 : found->second;
}

const char* getPath(retro_vfs_file_handle* file) { return file->path.c_str(); }

int64_t size(retro_vfs_file_handle* file) {
  struct stat info {};
  return fstat(file->fd, &info) == 0 ? (int64_t)info.st_size : -1;
}

int64_t truncate(retro_vfs_file_handle* file, int64_t length) {
  return ftruncate(file->fd, (off_t)length) == 0 ? 0 : -1;
}

int64_t write(retro_vfs_file_handle* file, const void* buffer, uint64_t length) {
  ssize_t written = pwrite(file->fd, buffer, (size_t)length, (off_t)file->position);
  if (written < 0) return -1;
  file->position += written;
  return written;
}

int flush(retro_vfs_file_handle* file) { return fsync(file->fd) == 0 || errno == EINVAL ? 0 : -1; }

int remove(const char* path) { return ::remove(path) == 0 ? 0 : -1; }

int rename(const char* from, const char* to) { return ::rename(from, to) == 0 ? 0 : -1; }

int stat(const char* path, int32_t* sizeOut) {
  struct stat info {};
  int fd = mappedFd(path);
  int result = fd >= 0 ? fstat(fd, &info) : ::stat(path, &info);
  if (result != 0) return 0;
  if (sizeOut) *sizeOut = (int32_t)info.st_size;
  int flags = RETRO_VFS_STAT_IS_VALID;
  if (S_ISDIR(info.st_mode)) flags |= RETRO_VFS_STAT_IS_DIRECTORY;
  if (S_ISCHR(info.st_mode)) flags |= RETRO_VFS_STAT_IS_CHARACTER_SPECIAL;
  return flags;
}

int mkdir(const char* path) {
  if (::mkdir(path, 0755) == 0) return 0;
  return errno == EEXIST ? -2 : -1;
}

retro_vfs_dir_handle* opendir(const char* path, bool) {
  DIR* dir = ::opendir(path);
  if (!dir) return nullptr;
  return new retro_vfs_dir_handle{dir, nullptr, path};
}

bool readdir(retro_vfs_dir_handle* dir) {
  dir->entry = ::readdir(dir->dir);
  return dir->entry != nullptr;
}

const char* direntName(retro_vfs_dir_handle* dir) { return dir->entry ? dir->entry->d_name : nullptr; }

bool direntIsDir(retro_vfs_dir_handle* dir) {
  if (!dir->entry) return false;
  if (dir->entry->d_type != DT_UNKNOWN) return dir->entry->d_type == DT_DIR;
  struct stat info {};
  std::string full = dir->path + "/" + dir->entry->d_name;
  return ::stat(full.c_str(), &info) == 0 && S_ISDIR(info.st_mode);
}

int closedir(retro_vfs_dir_handle* dir) {
  ::closedir(dir->dir);
  delete dir;
  return 0;
}

retro_vfs_interface table = {
    getPath, vfs::open, vfs::close, size, vfs::tell, vfs::seek, vfs::read, write, flush, remove,
    rename, truncate, stat, mkdir, opendir, readdir, direntName, direntIsDir, closedir,
};
}  // namespace

namespace vfs {
int map(const std::string& path, int fd) {
  int copy = dup(fd);
  if (copy < 0) return -1;
  std::lock_guard<std::mutex> guard(lock);
  auto found = mapped.find(path);
  if (found != mapped.end()) ::close(found->second);
  mapped[path] = copy;
  return copy;
}

void clear() {
  std::lock_guard<std::mutex> guard(lock);
  for (auto& entry : mapped) ::close(entry.second);
  mapped.clear();
}

retro_vfs_interface* interface() { return &table; }

retro_vfs_file_handle* open(const char* path, unsigned mode, unsigned) {
  if (!path) return nullptr;
  int source = mappedFd(path);
  int fd;
  if (source >= 0) {
    if (mode & RETRO_VFS_FILE_ACCESS_WRITE) return nullptr;  // ROMs are read-only
    fd = dup(source);
  } else if ((mode & RETRO_VFS_FILE_ACCESS_READ_WRITE) == RETRO_VFS_FILE_ACCESS_READ_WRITE) {
    fd = ::open(path, O_RDWR | O_CREAT | ((mode & RETRO_VFS_FILE_ACCESS_UPDATE_EXISTING) ? 0 : O_TRUNC), 0644);
  } else if (mode & RETRO_VFS_FILE_ACCESS_WRITE) {
    fd = ::open(path, O_WRONLY | O_CREAT | ((mode & RETRO_VFS_FILE_ACCESS_UPDATE_EXISTING) ? 0 : O_TRUNC), 0644);
  } else {
    fd = ::open(path, O_RDONLY);
  }
  if (fd < 0) return nullptr;
  return new retro_vfs_file_handle{fd, 0, path};
}

int close(retro_vfs_file_handle* file) {
  if (!file) return -1;
  ::close(file->fd);
  delete file;
  return 0;
}

int64_t seek(retro_vfs_file_handle* file, int64_t offset, int whence) {
  int64_t base = whence == RETRO_VFS_SEEK_POSITION_CURRENT ? file->position
                 : whence == RETRO_VFS_SEEK_POSITION_END    ? size(file)
                                                             : 0;
  if (base < 0 || base + offset < 0) return -1;
  file->position = base + offset;
  return file->position;
}

int64_t tell(retro_vfs_file_handle* file) { return file->position; }

int64_t read(retro_vfs_file_handle* file, void* buffer, uint64_t length) {
  // pread keeps each handle's position independent even when descriptors share an open file.
  ssize_t got = pread(file->fd, buffer, (size_t)length, (off_t)file->position);
  if (got < 0) return -1;
  file->position += got;
  return got;
}
}  // namespace vfs
