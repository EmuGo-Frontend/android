#pragma once

#include <cstddef>
#include <cstdint>

#include "libretro.h"

// RetroAchievements glue between the Libretro host (core.cpp) and rcheevos' rc_client.
namespace ra {
using MemoryData = void* (*)(unsigned);
using MemorySize = size_t (*)(unsigned);

// Called on the emulation thread.
void gameLoaded(const char* path, const uint8_t* data, size_t size, MemoryData memoryData,
                MemorySize memorySize, const retro_memory_map* map);
void frame();
void idle();
void gameUnloaded();
bool takeResetRequest();
bool hardcore();
}  // namespace ra
