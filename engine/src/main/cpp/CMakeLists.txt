# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(ticktock_engine LANGUAGES CXX)

# One shared library, one JNI file. Everything under render/ runs inside the
# audio callback and is also built for the host under host-tests/, together
# with control/; the AAudio and JNI files are the Android-only edge.
add_library(ticktock_engine SHARED
    engine.cpp
    render/BeatGenerator.cpp
    render/ClickData.cpp
    render/RenderEngine.cpp
    control/OutputController.cpp
    AAudioDevice.cpp
    JniBridge.cpp
)

target_include_directories(ticktock_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(ticktock_engine PRIVATE cxx_std_17)
target_compile_options(ticktock_engine PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti)
target_link_libraries(ticktock_engine PRIVATE aaudio)
