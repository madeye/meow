# NAPI glue module (libmeow.so): marshals between ArkTS and the Rust C ABI
# in libmihomo_ohos_ffi.so (built by harmony/scripts/build-rust-ohos.sh into
# entry/libs/<abi>/).
cmake_minimum_required(VERSION 3.5.0)
project(meow)

set(MEOW_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})

if(DEFINED PACKAGE_FIND_FILE)
    include(${PACKAGE_FIND_FILE})
endif()

include_directories(${MEOW_ROOT_PATH})

add_library(meow SHARED napi_init.cpp)

# libmihomo_ohos_ffi.so is placed in entry/libs/${OHOS_ARCH}/ by
# build-rust-ohos.sh; hvigor packages everything in that directory into the
# HAP automatically.
target_link_libraries(meow PUBLIC
    libace_napi.z.so
    libhilog_ndk.z.so
    ${MEOW_ROOT_PATH}/../../../libs/${OHOS_ARCH}/libmihomo_ohos_ffi.so
)
