cmake_minimum_required(VERSION 3.22.1)
project(vap_vk)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(vap_vk SHARED
    vap_vk_jni.cpp
    vap_vk_engine.cpp
    vap_ahb_probe.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(mediandk-lib mediandk)

target_include_directories(vap_vk PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/shaders
)

target_compile_options(vap_vk PRIVATE
    -Wall
    -Wextra
    -fno-exceptions
    -fno-rtti
)

target_compile_definitions(vap_vk PRIVATE
    VK_USE_PLATFORM_ANDROID_KHR
)

target_link_libraries(vap_vk
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
    ${mediandk-lib}
)
