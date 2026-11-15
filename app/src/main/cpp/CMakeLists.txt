cmake_minimum_required(VERSION 3.22.1)
project(emugo)
file(GLOB RCHEEVOS_SOURCES
    rcheevos/src/*.c rcheevos/src/rapi/*.c rcheevos/src/rcheevos/*.c rcheevos/src/rhash/*.c)
add_library(emugo SHARED core.cpp ra.cpp vfs.cpp discord.cpp ${RCHEEVOS_SOURCES})
target_include_directories(emugo PRIVATE . rcheevos/include rcheevos/src)
target_compile_definitions(emugo PRIVATE RC_DISABLE_LUA RC_CLIENT_SUPPORTS_HASH)
target_link_libraries(emugo android log dl EGL GLESv3)
