# Building llama.cpp with OpenCL for Adreno GPU

This guide explains how to build llama.cpp with OpenCL support for the Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3 / Adreno 750) or similar devices with Adreno 830 (Snapdragon 8 Elite).

## Why OpenCL?

| Backend | Prompt Processing | Token Generation | Summary Time (est.) |
|---------|-------------------|------------------|---------------------|
| CPU Only | ~20 tok/s | ~3-5 tok/s | 30-60 seconds |
| **OpenCL GPU** | **~170 tok/s** | **~17 tok/s** | **5-10 seconds** |

OpenCL acceleration provides **3-5x faster inference** on Adreno GPUs!

## Prerequisites

### Option A: Build on Linux/Mac

1. **Android NDK** (version 26.3.11579264 recommended)
   ```bash
   # Install via Android Studio SDK Manager, or:
   sdkmanager "ndk;26.3.11579264"
   ```

2. **CMake 3.22+**
   ```bash
   # Ubuntu/Debian
   sudo apt-get install cmake ninja-build

   # macOS
   brew install cmake ninja
   ```

3. **Git**
   ```bash
   sudo apt-get install git  # or brew install git
   ```

### Option B: Build on Windows (via WSL)

1. **Install WSL with Ubuntu**
   ```powershell
   # Run in PowerShell as Administrator
   wsl --install -d Ubuntu
   ```

2. **Install Android NDK in WSL**
   ```bash
   # In WSL Ubuntu terminal
   sudo apt-get update
   sudo apt-get install -y openjdk-17-jdk unzip wget

   # Download Android command-line tools
   wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
   unzip commandlinetools-linux-11076708_latest.zip -d ~/android-sdk

   # Install NDK
   ~/android-sdk/cmdline-tools/bin/sdkmanager --sdk_root=$HOME/android-sdk "ndk;26.3.11579264"

   # Set environment variable
   echo 'export ANDROID_NDK=$HOME/android-sdk/ndk/26.3.11579264' >> ~/.bashrc
   source ~/.bashrc
   ```

### Option C: Build Directly on Android Device (via Termux)

1. Install Termux from F-Droid (not Play Store)
2. Run:
   ```bash
   pkg update && pkg install git cmake clang

   git clone https://github.com/ggml-org/llama.cpp.git
   cd llama.cpp
   git checkout b5028  # CRITICAL: newer versions have bugs

   cmake -B build \
     -DBUILD_SHARED_LIBS=ON \
     -DGGML_OPENCL=ON \
     -DGGML_OPENCL_EMBED_KERNELS=ON \
     -DGGML_OPENCL_USE_ADRENO_KERNELS=ON

   cmake --build build --config Release
   ```

## Build Instructions

### Linux/Mac

```bash
cd scripts/
chmod +x build_llama_opencl.sh
./build_llama_opencl.sh
```

### Windows

```cmd
cd scripts\
build_llama_opencl.bat
```

## Output

After a successful build, you'll find the libraries in:
```
scripts/output/arm64-v8a/
├── libllama.so          # Main llama.cpp library
├── libggml.so           # GGML tensor library
├── libggml-base.so      # GGML base operations
├── libggml-cpu.so       # CPU backend (fallback)
├── libggml-opencl.so    # OpenCL backend (GPU)
├── libOpenCL.so         # OpenCL ICD loader
└── libc++_shared.so     # C++ standard library
```

## Installation

Copy the built libraries to your Android project:

```bash
cp scripts/output/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/
```

## Updating CMakeLists.txt

After copying the OpenCL-enabled libraries, update `app/src/main/cpp/CMakeLists.txt`:

```cmake
# Add OpenCL library
add_library(opencl SHARED IMPORTED)
set_target_properties(opencl PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/libOpenCL.so
)

# Add GGML OpenCL backend
add_library(ggml-opencl SHARED IMPORTED)
set_target_properties(ggml-opencl PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/libggml-opencl.so
)

# Update llm_jni link libraries
target_link_libraries(llm_jni
    llama
    ggml
    ggml-base
    ggml-cpu
    ggml-opencl  # Add this
    opencl       # Add this
    omp
    android
    log
)
```

## Runtime Considerations

### OpenCL Library Loading

On Android, the OpenCL driver is provided by the GPU vendor (Qualcomm). The app needs to find it at runtime:

```java
// In LlamaEngine.java or similar
static {
    try {
        // Try to load vendor OpenCL first
        System.loadLibrary("OpenCL");
    } catch (UnsatisfiedLinkError e) {
        // Fallback: load our bundled ICD loader
        System.loadLibrary("OpenCL");
    }
    System.loadLibrary("ggml");
    System.loadLibrary("ggml-opencl");
    System.loadLibrary("llama");
    System.loadLibrary("llm_jni");
}
```

### Model Quantization

For best OpenCL performance on Adreno, use **Q4_0** quantization:

```bash
# When quantizing your model, use --pure for Q4_0 throughout
./llama-quantize --pure model.gguf model-q4_0.gguf Q4_0
```

## Troubleshooting

### "OpenCL driver not found"

The device's OpenCL driver is in `/vendor/lib64/`. If not detected:

```bash
# In Termux, copy to accessible location
cp /vendor/lib64/libOpenCL.so ~/
cp /vendor/lib64/libOpenCL_adreno.so ~/
export LD_LIBRARY_PATH=$HOME:$LD_LIBRARY_PATH
```

### Segmentation fault on newer llama.cpp versions

**Solution:** Use version `b5028` or earlier. This is handled automatically by the build script.

### Build fails with "NDK not found"

Set the environment variable:
```bash
export ANDROID_NDK=/path/to/your/ndk/26.3.11579264
```

## Performance Tips

1. **Use Q4_0 quantization** — optimized for Adreno OpenCL
2. **Set reasonable context size** — 2048-4096 tokens to avoid memory issues
3. **Batch size 512** for prompt processing gives best throughput
4. **Monitor GPU usage** with `adb shell dumpsys gpu` during inference

## References

- [Qualcomm OpenCL Backend Announcement](https://www.qualcomm.com/developer/blog/2024/11/introducing-new-opn-cl-gpu-backend-llama-cpp-for-qualcomm-adreno-gpu)
- [llama.cpp OpenCL Documentation](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md)
- [llama.cpp Android Tutorial](https://github.com/JackZeng0208/llama.cpp-android-tutorial)

---
*STELLiQ Engineering — ARIA Demo Build*
