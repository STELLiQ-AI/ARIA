# FindOpenCL.cmake — Android NDK stub for cross-compilation
#
# WHY: The Android NDK doesn't include OpenCL headers or libraries.
# OpenCL is provided by the GPU vendor (Qualcomm Adreno) at runtime via
# /system/vendor/lib64/libOpenCL.so. This module provides:
#   - Khronos OpenCL headers for compilation
#   - A device-pulled libOpenCL.so for link-time symbol resolution
#
# The linked symbols resolve to the real Adreno driver at runtime.

set(OpenCL_FOUND TRUE)
set(OpenCL_INCLUDE_DIRS "${CMAKE_SOURCE_DIR}/opencl-headers")
set(OpenCL_LIBRARIES "${CMAKE_SOURCE_DIR}/opencl-stub/libOpenCL.so")
set(OpenCL_VERSION_STRING "2.0")

message(STATUS "ARIA: Using Android OpenCL stub (headers: ${OpenCL_INCLUDE_DIRS}, lib: ${OpenCL_LIBRARIES})")
