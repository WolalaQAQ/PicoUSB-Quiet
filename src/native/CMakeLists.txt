# Zygisk module library. Output must be named arm64-v8a.so inside the module's zygisk/ directory.
cmake_minimum_required(VERSION 3.22)
project(PicoUSBQuiet LANGUAGES CXX)

add_library(picousbquiet SHARED module.cpp)
set_target_properties(picousbquiet PROPERTIES OUTPUT_NAME "arm64-v8a" PREFIX "")
target_compile_options(picousbquiet PRIVATE
    -O2 -fPIC -Wall -Wextra -Werror -Wno-unused-parameter -fno-exceptions -fno-rtti)
target_link_options(picousbquiet PRIVATE "-s")
target_link_libraries(picousbquiet PRIVATE log dl)
