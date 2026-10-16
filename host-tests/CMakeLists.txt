# SPDX-License-Identifier: GPL-3.0-or-later
#
# The N tier: the engine's render and control code built for the host and
# driven by gtest, in three variants from one configure:
#
#   host_tests_release   the optimised build the device gets
#   host_tests_sanitize  ASan + UBSan, undefined behaviour is a failure
#   host_tests_alloc     a counting operator new proves render() never
#                        touches the heap (OUT-16)
#
#   cmake -S host-tests -B host-tests/build -G Ninja -DCMAKE_BUILD_TYPE=Release
#   cmake --build host-tests/build
#   (cd host-tests/build && ctest --output-on-failure)
#
# gtest comes from the distribution (1.18); nothing is fetched.
cmake_minimum_required(VERSION 3.22)
project(ticktock_host_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(GTest REQUIRED)
include(GoogleTest)
enable_testing()

set(ENGINE_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../engine/src/main/cpp")
set(ENGINE_SOURCES
    "${ENGINE_CPP}/render/BeatGenerator.cpp"
    "${ENGINE_CPP}/render/ClickData.cpp"
    "${ENGINE_CPP}/render/RenderEngine.cpp"
    "${ENGINE_CPP}/control/OutputController.cpp"
)
set(TEST_SOURCES
    SpscQueueTest.cpp
    ClickDataTest.cpp
    BeatGeneratorTest.cpp
    VoicesTest.cpp
    OutputControllerTest.cpp
)
set(WARNINGS -Wall -Wextra -Werror)

# The JVM bridge feeds samples from this production renderer to the real detector.
add_executable(audio_fixture_driver AudioFixtureDriver.cpp
    "${ENGINE_CPP}/render/BeatGenerator.cpp"
    "${ENGINE_CPP}/render/ClickData.cpp"
    "${ENGINE_CPP}/render/RenderEngine.cpp"
    "${ENGINE_CPP}/control/OutputController.cpp")
target_include_directories(audio_fixture_driver PRIVATE "${ENGINE_CPP}")
target_compile_options(audio_fixture_driver PRIVATE ${WARNINGS} -fno-exceptions -fno-rtti)

# One executable per variant. The engine objects carry the same -fno-exceptions
# -fno-rtti the Android build uses, so a host pass proves the device build's
# constraints too; the test objects keep exceptions for gtest.
function(ticktock_variant name)
    cmake_parse_arguments(V "" "" "FLAGS;LINK;EXTRA_SOURCES;DEFINES" ${ARGN})
    add_library("engine_${name}" OBJECT ${ENGINE_SOURCES})
    target_include_directories("engine_${name}" PRIVATE "${ENGINE_CPP}")
    target_compile_options("engine_${name}" PRIVATE ${WARNINGS} -fno-exceptions -fno-rtti ${V_FLAGS})
    target_compile_definitions("engine_${name}" PRIVATE ${V_DEFINES})

    add_executable("${name}" ${TEST_SOURCES} ${V_EXTRA_SOURCES} $<TARGET_OBJECTS:engine_${name}>)
    target_include_directories("${name}" PRIVATE "${ENGINE_CPP}" "${CMAKE_CURRENT_SOURCE_DIR}")
    target_compile_options("${name}" PRIVATE ${WARNINGS} ${V_FLAGS})
    target_compile_definitions("${name}" PRIVATE ${V_DEFINES})
    target_link_options("${name}" PRIVATE ${V_LINK})
    target_link_libraries("${name}" PRIVATE GTest::gtest GTest::gtest_main)
    gtest_discover_tests("${name}" TEST_PREFIX "${name}." DISCOVERY_TIMEOUT 60)
endfunction()

ticktock_variant(host_tests_release)

# The engine objects carry no RTTI, so the vptr check has no typeinfo to
# compare against; every other UBSan check stays on and fails the run.
ticktock_variant(host_tests_sanitize
    FLAGS -fsanitize=address,undefined -fno-sanitize=vptr -fno-sanitize-recover=all -fno-omit-frame-pointer -g
    LINK -fsanitize=address,undefined -fno-sanitize=vptr
)

ticktock_variant(host_tests_alloc
    DEFINES TICKTOCK_ALLOC_COUNTER=1
    EXTRA_SOURCES support/AllocCounter.cpp AllocationTest.cpp
)

# Compile the real Android edge against test platform calls; no device or new artifact is needed.
set(TEST_JNI_INCLUDE "$ENV{JAVA_HOME}/include")
if(NOT EXISTS "${TEST_JNI_INCLUDE}/jni.h")
    message(FATAL_ERROR "JAVA_HOME must name the approved JDK for the native bridge tests")
endif()
function(ticktock_aaudio_variant name)
    cmake_parse_arguments(V "" "" "FLAGS;LINK" ${ARGN})
    add_executable("${name}" AAudioDeviceTest.cpp support/AllocCounter.cpp
        "${ENGINE_CPP}/AAudioDevice.cpp" "${ENGINE_CPP}/engine.cpp" ${ENGINE_SOURCES})
    target_include_directories("${name}" PRIVATE support/platform "${ENGINE_CPP}"
        "${CMAKE_CURRENT_SOURCE_DIR}" "${TEST_JNI_INCLUDE}" "${TEST_JNI_INCLUDE}/linux")
    target_compile_options("${name}" PRIVATE ${WARNINGS} ${V_FLAGS})
    target_link_options("${name}" PRIVATE -Wl,--wrap=sem_destroy ${V_LINK})
    target_link_libraries("${name}" PRIVATE GTest::gtest GTest::gtest_main dl)
    gtest_discover_tests("${name}" TEST_PREFIX "${name}." DISCOVERY_TIMEOUT 60)
endfunction()

ticktock_aaudio_variant(aaudio_device_tests)
ticktock_aaudio_variant(aaudio_device_tests_sanitize
    FLAGS -fsanitize=address,undefined -fno-sanitize=vptr -fno-sanitize-recover=all -fno-omit-frame-pointer -g
    LINK -fsanitize=address,undefined -fno-sanitize=vptr
)
