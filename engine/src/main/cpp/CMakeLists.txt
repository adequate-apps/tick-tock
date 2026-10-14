# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(ticktock_engine LANGUAGES CXX)

# One shared library, one JNI file (added in M2). Everything that runs inside
# the render callback is also built for the host under host-tests/, so keep
# platform calls behind the files that need them.
add_library(ticktock_engine SHARED
    engine.cpp
)

target_compile_features(ticktock_engine PRIVATE cxx_std_17)
target_compile_options(ticktock_engine PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti)
