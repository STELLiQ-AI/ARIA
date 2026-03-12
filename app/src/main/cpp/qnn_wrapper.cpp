/**
 * qnn_wrapper.cpp
 *
 * QNN HTP runtime wrapper implementation for ARIA Whisper encoder NPU acceleration.
 * Uses dlopen/dlsym to load the QNN HTP backend at runtime, avoiding build-time
 * linking against QNN libraries (which simplifies CMake and allows graceful fallback).
 *
 * QNN: Targets QNN SDK v2.44.0.260225, API version 2.26.
 * QNN: HTP backend for Hexagon V75 on Snapdragon 8 Gen 3 (S24 Ultra).
 *
 * WHY dlopen instead of direct linking:
 * - Allows the APK to start even if QNN .so files are missing (graceful degradation)
 * - Avoids symbol conflicts with other native libraries
 * - Matches the pattern used by QNN's own sample applications
 *
 * @author STELLiQ Engineering
 * @version 0.2.0-npu
 * @since ARIA NPU Build — 2026-03-11
 */

#include "qnn_wrapper.h"

#include <android/log.h>
#include <dlfcn.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <fstream>

// QNN SDK headers
#include "QNN/QnnInterface.h"
// QNN: System interface for tensor metadata discovery from context binaries
#include "QNN/System/QnnSystemInterface.h"
#include "QNN/QnnTypes.h"
#include "QNN/QnnCommon.h"
#include "QNN/QnnBackend.h"
#include "QNN/QnnContext.h"
#include "QNN/QnnGraph.h"
#include "QNN/QnnTensor.h"
#include "QNN/QnnDevice.h"
// QNN: HTP-specific device and performance infrastructure for thermal management
#include "QNN/HTP/QnnHtpDevice.h"
#include "QNN/HTP/QnnHtpPerfInfrastructure.h"

#define LOG_TAG "ARIA_QNN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// QNN FUNCTION POINTER TYPE
// ═══════════════════════════════════════════════════════════════════════════

// WHY: QnnInterface_getProviders is the entry point obtained via dlsym.
// It returns the full QNN function table for the loaded backend.
typedef Qnn_ErrorHandle_t (*QnnInterfaceGetProvidersFn)(
    const QnnInterface_t*** providerList,
    uint32_t* numProviders);

// ═══════════════════════════════════════════════════════════════════════════
// MODULE STATE
// ═══════════════════════════════════════════════════════════════════════════

namespace {

// WHY: Stores tensor specs discovered at load time from context binary metadata.
// Tensor IDs are baked in by AI Hub at compile time and change every recompilation.
// Hardcoding IDs causes QNN error 6004 (graphExecute failure) when the binary
// is recompiled. This struct enables dynamic discovery via QnnSystemContext API.
struct TensorSpec {
    uint32_t id = 0;
    std::string name;
    Qnn_DataType_t dataType = QNN_DATATYPE_FLOAT_32;
    Qnn_TensorDataFormat_t dataFormat = QNN_TENSOR_DATA_FORMAT_DENSE;
    uint32_t rank = 0;
    std::vector<uint32_t> dimensions;
};

struct QnnState {
    void* libHandle = nullptr;                     // dlopen handle for libQnnHtp.so
    QNN_INTERFACE_VER_TYPE qnnInterface = QNN_INTERFACE_VER_TYPE_INIT;
    Qnn_BackendHandle_t backendHandle = nullptr;
    Qnn_DeviceHandle_t deviceHandle = nullptr;
    Qnn_ContextHandle_t contextHandle = nullptr;
    Qnn_GraphHandle_t graphHandle = nullptr;

    bool initialized = false;
    bool contextLoaded = false;

    // Graph I/O tensor specs (discovered from context binary via QnnSystemContext)
    TensorSpec inputSpec;
    TensorSpec outputSpec;
    bool tensorSpecsDiscovered = false;

    // QNN: HTP performance infrastructure for DCVS power management.
    // Used to set voltage corners and power mode on the Hexagon V75 to control
    // thermal output during sustained recording sessions.
    QnnHtpDevice_PerfInfrastructure_t* perfInfra = nullptr;
    uint32_t powerConfigId = 0;
    bool perfConfigured = false;
};

QnnState g_qnn;

// WHY: Read entire file into memory for contextCreateFromBinary.
// Context binaries are typically 10-15MB — fits comfortably in RAM.
bool readFile(const char* path, std::vector<uint8_t>& data) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        LOGE("Failed to open file: %s", path);
        return false;
    }
    auto size = file.tellg();
    if (size <= 0) {
        LOGE("File is empty or unreadable: %s", path);
        return false;
    }
    data.resize(static_cast<size_t>(size));
    file.seekg(0, std::ios::beg);
    file.read(reinterpret_cast<char*>(data.data()), size);
    return file.good();
}

} // anonymous namespace

// ═══════════════════════════════════════════════════════════════════════════
// PUBLIC API IMPLEMENTATION
// ═══════════════════════════════════════════════════════════════════════════

namespace aria {
namespace qnn {

// WHY: The skel library path must be set before any QNN HTP operations.
// cdsprpcd daemon uses ADSP_LIBRARY_PATH to find libQnnHtpV75Skel.so on the Hexagon DSP.
// For development: skel pushed to /data/local/tmp/ via adb.
// For production: skel extracted from APK assets to filesDir at runtime.
static void configureSkeletonSearchPath(const char* appLibDir) {
    std::string path;
    if (appLibDir && strlen(appLibDir) > 0) {
        path = appLibDir;
        path += ";/data/local/tmp";
    } else {
        path = "/data/local/tmp";
    }

    // Append existing ADSP_LIBRARY_PATH if present
    const char* existing = getenv("ADSP_LIBRARY_PATH");
    if (existing && strlen(existing) > 0) {
        path += ";";
        path += existing;
    }

    setenv("ADSP_LIBRARY_PATH", path.c_str(), 1);
    LOGI("ADSP_LIBRARY_PATH set to: %s", path.c_str());
}

// QNN: Configure HTP power mode via DCVS V3 performance infrastructure.
// WHY: Without explicit power config, the HTP backend runs at maximum voltage/frequency
// by default. For sustained recording (15+ minutes), this generates too much heat and
// triggers thermal throttling at ~minute 4 (RTF degrades from 0.38x to 0.52x permanently).
//
// POWER_SAVER_MODE with NOM voltage corners tells DCVS to use higher efficiency thresholds:
// - NPU runs at nominal voltage instead of turbo — ~20-30% less heat
// - Encoder time increases by ~50-100ms (520ms → ~580-620ms) — negligible vs 5s window
// - Combined with adaptive Java-side yield, this prevents thermal throttle entirely
//
// HMX timeout at 1000μs powers down the Hexagon Matrix eXtensions 1ms after encoder
// completes. Since our duty cycle is ~520ms active / ~4500ms idle, HMX sits unpowered
// for 90% of each window — free thermal reduction with zero latency cost.
static bool configurePowerMode() {
    if (!g_qnn.deviceHandle) {
        LOGW("QNN device handle is null — skipping power configuration");
        return false;
    }

    // Step 1: Get device infrastructure (backend-owned memory)
    // QNN: QnnDevice_Infrastructure_t is a pointer type (typedef struct* ...).
    // deviceGetInfrastructure fills it with a pointer to backend-owned memory.
    QnnDevice_Infrastructure_t deviceInfra = nullptr;
    Qnn_ErrorHandle_t err = g_qnn.qnnInterface.deviceGetInfrastructure(&deviceInfra);
    if (err != QNN_SUCCESS || deviceInfra == nullptr) {
        LOGW("deviceGetInfrastructure failed: %lu — power config unavailable", err);
        return false;
    }

    // Step 2: Cast to HTP-specific infrastructure to access perf function pointers
    auto* htpInfra = static_cast<QnnHtpDevice_Infrastructure_t*>(deviceInfra);
    if (htpInfra->infraType != QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) {
        LOGW("Unexpected infrastructure type: %d — expected PERF (0)", htpInfra->infraType);
        return false;
    }

    g_qnn.perfInfra = &(htpInfra->perfInfra);
    if (!g_qnn.perfInfra->createPowerConfigId || !g_qnn.perfInfra->setPowerConfig) {
        LOGW("PerfInfrastructure function pointers are null — power config unavailable");
        g_qnn.perfInfra = nullptr;
        return false;
    }

    // Step 3: Create power config ID for this device/core
    uint32_t deviceId = 0;  // default device
    uint32_t coreId = 0;    // default core (NSP)
    err = g_qnn.perfInfra->createPowerConfigId(deviceId, coreId, &g_qnn.powerConfigId);
    if (err != QNN_SUCCESS) {
        LOGW("createPowerConfigId failed: %lu — power config unavailable", err);
        g_qnn.perfInfra = nullptr;
        return false;
    }
    LOGI("QNN power config ID created: %u", g_qnn.powerConfigId);

    // Step 4: Configure DCVS V3 — POWER_SAVER_MODE with NOM voltage corners
    // WHY NOM (not TURBO): TURBO pushes V75 to maximum clock/voltage for burst performance.
    // For sustained 15-minute recording, TURBO causes thermal saturation at ~4 minutes.
    // NOM provides ~85% of TURBO throughput at ~70% of the power draw — the sweet spot
    // for sustained operation. The encoder still completes in ~580-620ms, well within
    // the 5s window budget.
    QnnHtpPerfInfrastructure_PowerConfig_t dcvsConfig;
    memset(&dcvsConfig, 0, sizeof(dcvsConfig));
    dcvsConfig.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
    dcvsConfig.dcvsV3Config.contextId = g_qnn.powerConfigId;
    dcvsConfig.dcvsV3Config.setDcvsEnable = 1;
    dcvsConfig.dcvsV3Config.dcvsEnable = 1;
    dcvsConfig.dcvsV3Config.powerMode =
        QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_POWER_SAVER_MODE;
    // WHY: Allow NPU sleep between encoder invocations — our duty cycle is highly bursty
    dcvsConfig.dcvsV3Config.setSleepLatency = 1;
    dcvsConfig.dcvsV3Config.sleepLatency = 40;  // 40μs — fast wake for next window
    dcvsConfig.dcvsV3Config.setSleepDisable = 1;
    dcvsConfig.dcvsV3Config.sleepDisable = 0;   // 0 = sleep ENABLED
    // Bus voltage corners: SVS (min) to NOM (target/max)
    // WHY: Bus bandwidth for a single encoder inference is modest. SVS floor allows
    // DCVS to drop bus clocks during idle. NOM ceiling is sufficient for mel→embedding.
    dcvsConfig.dcvsV3Config.setBusParams = 1;
    dcvsConfig.dcvsV3Config.busVoltageCornerMin = DCVS_VOLTAGE_VCORNER_SVS;
    dcvsConfig.dcvsV3Config.busVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_NOM;
    dcvsConfig.dcvsV3Config.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_NOM;
    // Core voltage corners: SVS (min) to NOM (target/max)
    // WHY: Core at NOM provides ~85% of TURBO throughput. The ~50-100ms slowdown
    // is invisible in the pipeline (5s window, ~580ms encoder, ~1400ms decoder).
    dcvsConfig.dcvsV3Config.setCoreParams = 1;
    dcvsConfig.dcvsV3Config.coreVoltageCornerMin = DCVS_VOLTAGE_VCORNER_SVS;
    dcvsConfig.dcvsV3Config.coreVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_NOM;
    dcvsConfig.dcvsV3Config.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_NOM;

    // Step 5: Configure HMX timeout — power down matrix extensions after 1ms idle
    // WHY: HMX is only active during the ~520ms encoder forward pass. Between windows
    // (4+ seconds), it sits idle consuming power and generating heat. A 1ms timeout
    // powers it down almost immediately after the encoder finishes. The ~1-2ms wake
    // latency when the next window arrives is negligible.
    QnnHtpPerfInfrastructure_PowerConfig_t hmxConfig;
    memset(&hmxConfig, 0, sizeof(hmxConfig));
    hmxConfig.option =
        QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_HMX_TIMEOUT_INTERVAL_US;
    hmxConfig.hmxTimeoutIntervalUsConfig = 1000;  // 1ms (1000μs)

    // Step 6: Apply both configs as a NULL-terminated array
    const QnnHtpPerfInfrastructure_PowerConfig_t* configs[] = {
        &dcvsConfig, &hmxConfig, nullptr
    };
    err = g_qnn.perfInfra->setPowerConfig(
        g_qnn.powerConfigId,
        configs);
    if (err != QNN_SUCCESS) {
        LOGW("setPowerConfig failed: %lu — running at default (TURBO) power mode", err);
        // Non-fatal — inference still works, just runs hotter
        if (g_qnn.perfInfra->destroyPowerConfigId) {
            g_qnn.perfInfra->destroyPowerConfigId(g_qnn.powerConfigId);
        }
        g_qnn.powerConfigId = 0;
        g_qnn.perfInfra = nullptr;
        return false;
    }

    g_qnn.perfConfigured = true;
    LOGI("QNN HTP power mode configured: POWER_SAVER_MODE, bus/core=SVS-NOM, HMX timeout=1ms");
    LOGI("  Expected impact: encoder ~580-620ms (was ~520ms), ~30%% less heat generation");
    return true;
}

bool initialize() {
    if (g_qnn.initialized) {
        LOGI("QNN already initialized");
        return true;
    }

    // Step 0: Configure DSP skel library search path
    // WHY: Must be set BEFORE any QNN backend operations. The FastRPC daemon
    // (cdsprpcd) reads this env var to find libQnnHtpV75Skel.so for loading
    // onto the Hexagon DSP. Without this, the daemon only searches
    // /vendor/dsp/cdsp/ which doesn't contain our SDK's skel.
    configureSkeletonSearchPath(nullptr);

    // Step 1: dlopen the HTP backend
    // WHY: libQnnHtp.so is already in the APK jniLibs/ and loaded by
    // System.loadLibrary("QnnHtp") in Java. We use dlopen with RTLD_NOLOAD
    // first to get a handle to the already-loaded library. If that fails,
    // try a fresh dlopen (the system linker will find it in the APK's lib/).
    g_qnn.libHandle = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_NOLOAD);
    if (!g_qnn.libHandle) {
        g_qnn.libHandle = dlopen("libQnnHtp.so", RTLD_NOW);
    }
    if (!g_qnn.libHandle) {
        LOGE("Failed to dlopen libQnnHtp.so: %s", dlerror());
        return false;
    }
    LOGI("libQnnHtp.so loaded via dlopen");

    // Step 2: Get the QNN interface providers
    auto getProviders = reinterpret_cast<QnnInterfaceGetProvidersFn>(
        dlsym(g_qnn.libHandle, "QnnInterface_getProviders"));
    if (!getProviders) {
        LOGE("Failed to find QnnInterface_getProviders: %s", dlerror());
        return false;
    }

    const QnnInterface_t** providers = nullptr;
    uint32_t numProviders = 0;
    Qnn_ErrorHandle_t err = getProviders(&providers, &numProviders);
    if (err != QNN_SUCCESS || numProviders == 0 || providers == nullptr) {
        LOGE("QnnInterface_getProviders failed: %lu, numProviders=%u", err, numProviders);
        return false;
    }

    // WHY: Use the first provider — it's the primary HTP interface
    g_qnn.qnnInterface = providers[0]->QNN_INTERFACE_VER_NAME;
    LOGI("QNN interface obtained, numProviders=%u", numProviders);

    // Step 3: Create backend
    err = g_qnn.qnnInterface.backendCreate(
        nullptr,   // logger (null = default)
        nullptr,   // config (null = default)
        &g_qnn.backendHandle);
    if (err != QNN_SUCCESS) {
        LOGE("QNN backendCreate failed: %lu", err);
        return false;
    }
    LOGI("QNN HTP backend created");

    // Step 4: Create device (optional for HTP but recommended for Gen 3)
    err = g_qnn.qnnInterface.deviceCreate(
        nullptr,   // logger
        nullptr,   // config
        &g_qnn.deviceHandle);
    if (err != QNN_SUCCESS) {
        // QNN: Device creation is optional on some backends. Log warning but continue.
        LOGW("QNN deviceCreate returned %lu — continuing without explicit device", err);
        g_qnn.deviceHandle = nullptr;
    } else {
        LOGI("QNN device created");
    }

    // Step 5: Configure HTP power mode for sustained thermal management
    // QNN: Sets DCVS to POWER_SAVER_MODE with NOM voltage corners and HMX auto-timeout.
    // Non-fatal if it fails — inference works at default (TURBO) power, just runs hotter.
    if (g_qnn.deviceHandle) {
        if (configurePowerMode()) {
            LOGI("HTP power management active — optimized for sustained recording");
        } else {
            LOGW("HTP power management unavailable — running at default power mode");
        }
    }

    g_qnn.initialized = true;
    LOGI("QNN HTP initialization complete");
    return true;
}

// WHY: Discover graph name from context binary by scanning for "graph_" prefix.
// AI Hub assigns auto-generated names like "graph_b_x4j60r" at compile time.
// We scan the first 64KB of the binary (metadata region) for null-terminated
// strings matching the "graph_" pattern. This is more reliable than hardcoding
// because the name changes with every AI Hub compilation job.
static std::string discoverGraphName(const std::vector<uint8_t>& binaryData) {
    // Scan first 64KB for graph name strings (metadata is at the start)
    size_t scanLimit = std::min(binaryData.size(), static_cast<size_t>(65536));

    std::string current;
    for (size_t i = 0; i < scanLimit; i++) {
        uint8_t b = binaryData[i];
        if (b >= 32 && b < 127) {
            current += static_cast<char>(b);
        } else {
            if (current.length() >= 8 && current.substr(0, 6) == "graph_") {
                LOGI("Discovered graph name in binary metadata: \"%s\"", current.c_str());
                return current;
            }
            current.clear();
        }
    }
    LOGW("No graph name found in binary metadata (scanned %zu bytes)", scanLimit);
    return "";
}

// WHY: Uses QnnSystemContext API from libQnnSystem.so to extract tensor metadata
// (IDs, names, dimensions, data types) from the context binary. This is the same
// pattern as discoverGraphName() but uses the official QNN API instead of binary
// scanning. Tensor IDs change with every AI Hub compilation — hardcoding them
// causes QNN error 6004 (graphExecute failure).
static bool discoverTensorMetadata(const std::vector<uint8_t>& binaryData) {
    // Step 1: dlopen libQnnSystem.so (already in jniLibs, loaded by Java or linker)
    void* sysLib = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_NOLOAD);
    if (!sysLib) {
        sysLib = dlopen("libQnnSystem.so", RTLD_NOW);
    }
    if (!sysLib) {
        LOGW("Cannot dlopen libQnnSystem.so: %s — tensor metadata discovery unavailable", dlerror());
        return false;
    }

    // Step 2: Get QNN System interface providers
    typedef Qnn_ErrorHandle_t (*SysGetProvidersFn)(
        const QnnSystemInterface_t***, uint32_t*);
    auto getProviders = reinterpret_cast<SysGetProvidersFn>(
        dlsym(sysLib, "QnnSystemInterface_getProviders"));
    if (!getProviders) {
        LOGW("QnnSystemInterface_getProviders not found: %s", dlerror());
        return false;
    }

    const QnnSystemInterface_t** providers = nullptr;
    uint32_t numProviders = 0;
    Qnn_ErrorHandle_t err = getProviders(&providers, &numProviders);
    if (err != QNN_SUCCESS || numProviders == 0 || providers == nullptr) {
        LOGW("QnnSystemInterface_getProviders failed: %lu", err);
        return false;
    }

    auto& sysIface = providers[0]->QNN_SYSTEM_INTERFACE_VER_NAME;

    // Step 3: Create system context
    QnnSystemContext_Handle_t sysCtx = nullptr;
    err = sysIface.systemContextCreate(&sysCtx);
    if (err != QNN_SUCCESS) {
        LOGW("systemContextCreate failed: %lu", err);
        return false;
    }

    // Step 4: Extract metadata from context binary
    const QnnSystemContext_BinaryInfo_t* binaryInfo = nullptr;
    err = sysIface.systemContextGetMetaData(
        sysCtx, binaryData.data(),
        static_cast<uint64_t>(binaryData.size()), &binaryInfo);
    if (err != QNN_SUCCESS || binaryInfo == nullptr) {
        LOGW("systemContextGetMetaData failed: %lu", err);
        sysIface.systemContextFree(sysCtx);
        return false;
    }

    // Step 5: Extract graph I/O tensor specs
    // All BinaryInfo versions (V1/V2/V3) share the same layout for numGraphs and graphs fields
    uint32_t numGraphs = 0;
    QnnSystemContext_GraphInfo_t* graphs = nullptr;

    if (binaryInfo->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) {
        numGraphs = binaryInfo->contextBinaryInfoV1.numGraphs;
        graphs = binaryInfo->contextBinaryInfoV1.graphs;
    } else if (binaryInfo->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) {
        numGraphs = binaryInfo->contextBinaryInfoV2.numGraphs;
        graphs = binaryInfo->contextBinaryInfoV2.graphs;
    } else if (binaryInfo->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) {
        numGraphs = binaryInfo->contextBinaryInfoV3.numGraphs;
        graphs = binaryInfo->contextBinaryInfoV3.graphs;
    }

    if (numGraphs == 0 || graphs == nullptr) {
        LOGW("No graphs found in context binary metadata");
        sysIface.systemContextFree(sysCtx);
        return false;
    }

    LOGI("Context binary contains %u graph(s)", numGraphs);

    // Use the first graph (Whisper encoder)
    auto& graphInfo = graphs[0];
    uint32_t numInputs = 0, numOutputs = 0;
    Qnn_Tensor_t* inputs = nullptr;
    Qnn_Tensor_t* outputs = nullptr;
    const char* graphName = nullptr;

    // All GraphInfo versions (V1/V2/V3) share the same layout for the fields we need
    if (graphInfo.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
        graphName = graphInfo.graphInfoV1.graphName;
        numInputs = graphInfo.graphInfoV1.numGraphInputs;
        inputs = graphInfo.graphInfoV1.graphInputs;
        numOutputs = graphInfo.graphInfoV1.numGraphOutputs;
        outputs = graphInfo.graphInfoV1.graphOutputs;
    } else if (graphInfo.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
        graphName = graphInfo.graphInfoV2.graphName;
        numInputs = graphInfo.graphInfoV2.numGraphInputs;
        inputs = graphInfo.graphInfoV2.graphInputs;
        numOutputs = graphInfo.graphInfoV2.numGraphOutputs;
        outputs = graphInfo.graphInfoV2.graphOutputs;
    } else if (graphInfo.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) {
        graphName = graphInfo.graphInfoV3.graphName;
        numInputs = graphInfo.graphInfoV3.numGraphInputs;
        inputs = graphInfo.graphInfoV3.graphInputs;
        numOutputs = graphInfo.graphInfoV3.numGraphOutputs;
        outputs = graphInfo.graphInfoV3.graphOutputs;
    }

    LOGI("Graph \"%s\": %u input(s), %u output(s)", graphName ? graphName : "?", numInputs, numOutputs);

    // Helper lambda to extract tensor spec from Qnn_Tensor_t (handles V1 and V2)
    auto extractSpec = [](const Qnn_Tensor_t& tensor, TensorSpec& spec) {
        if (tensor.version == QNN_TENSOR_VERSION_1) {
            spec.id = tensor.v1.id;
            spec.name = tensor.v1.name ? tensor.v1.name : "";
            spec.dataType = tensor.v1.dataType;
            spec.dataFormat = tensor.v1.dataFormat;
            spec.rank = tensor.v1.rank;
            if (tensor.v1.dimensions && tensor.v1.rank > 0) {
                spec.dimensions.assign(tensor.v1.dimensions,
                                       tensor.v1.dimensions + tensor.v1.rank);
            }
        } else if (tensor.version == QNN_TENSOR_VERSION_2) {
            spec.id = tensor.v2.id;
            spec.name = tensor.v2.name ? tensor.v2.name : "";
            spec.dataType = tensor.v2.dataType;
            spec.dataFormat = tensor.v2.dataFormat;
            spec.rank = tensor.v2.rank;
            if (tensor.v2.dimensions && tensor.v2.rank > 0) {
                spec.dimensions.assign(tensor.v2.dimensions,
                                       tensor.v2.dimensions + tensor.v2.rank);
            }
        }
    };

    // Extract input tensor spec
    if (numInputs > 0 && inputs) {
        extractSpec(inputs[0], g_qnn.inputSpec);
        LOGI("  Input tensor: id=%u name=\"%s\" rank=%u dataType=0x%04x",
             g_qnn.inputSpec.id, g_qnn.inputSpec.name.c_str(),
             g_qnn.inputSpec.rank, g_qnn.inputSpec.dataType);
        if (g_qnn.inputSpec.rank > 0) {
            std::string dimStr;
            for (uint32_t d = 0; d < g_qnn.inputSpec.rank; d++) {
                if (d > 0) dimStr += "x";
                dimStr += std::to_string(g_qnn.inputSpec.dimensions[d]);
            }
            LOGI("  Input dimensions: %s", dimStr.c_str());
        }
    }

    // Extract output tensor spec
    if (numOutputs > 0 && outputs) {
        extractSpec(outputs[0], g_qnn.outputSpec);
        LOGI("  Output tensor: id=%u name=\"%s\" rank=%u dataType=0x%04x",
             g_qnn.outputSpec.id, g_qnn.outputSpec.name.c_str(),
             g_qnn.outputSpec.rank, g_qnn.outputSpec.dataType);
        if (g_qnn.outputSpec.rank > 0) {
            std::string dimStr;
            for (uint32_t d = 0; d < g_qnn.outputSpec.rank; d++) {
                if (d > 0) dimStr += "x";
                dimStr += std::to_string(g_qnn.outputSpec.dimensions[d]);
            }
            LOGI("  Output dimensions: %s", dimStr.c_str());
        }
    }

    g_qnn.tensorSpecsDiscovered = (numInputs > 0 && numOutputs > 0);

    sysIface.systemContextFree(sysCtx);
    // WHY: Don't dlclose libQnnSystem — Java may hold a reference via System.loadLibrary
    LOGI("Tensor metadata discovery %s", g_qnn.tensorSpecsDiscovered ? "succeeded" : "FAILED");
    return g_qnn.tensorSpecsDiscovered;
}

bool loadContextBinary(const char* path) {
    if (!g_qnn.initialized) {
        LOGE("QNN not initialized — call initialize() first");
        return false;
    }
    if (g_qnn.contextLoaded) {
        LOGI("Context binary already loaded");
        return true;
    }

    // Step 1: Read the context binary file
    std::vector<uint8_t> binaryData;
    if (!readFile(path, binaryData)) {
        LOGE("Failed to read context binary: %s", path);
        return false;
    }
    LOGI("Context binary read: %s (%zu bytes)", path, binaryData.size());

    // Step 1.5a: Discover tensor metadata (IDs, names, dimensions) from binary
    // QNN: This uses QnnSystemContext_getMetadata from libQnnSystem.so.
    // The discovered specs are stored in g_qnn.inputSpec / g_qnn.outputSpec
    // and used in executeGraph() to create correctly-bound tensors.
    if (!discoverTensorMetadata(binaryData)) {
        LOGW("Tensor metadata discovery failed — executeGraph() will use fallback specs");
        LOGW("  This may cause QNN error 6004 if tensor IDs don't match the binary");
    }

    // Step 1.5b: Discover graph name from binary metadata
    // WHY: AI Hub assigns graph names at compile time. We can't hardcode them
    // because the name depends on the ONNX model and compilation parameters.
    std::string discoveredName = discoverGraphName(binaryData);

    // Step 2: Create context from binary
    // WHY: contextCreateFromBinary deserializes the pre-compiled graph.
    // The binary was compiled by AI Hub targeting S24 Ultra — it contains
    // the full encoder computation graph optimized for Hexagon V75.
    Qnn_ErrorHandle_t err = g_qnn.qnnInterface.contextCreateFromBinary(
        g_qnn.backendHandle,
        g_qnn.deviceHandle,
        nullptr,                          // config (null = default)
        binaryData.data(),
        static_cast<uint32_t>(binaryData.size()),
        &g_qnn.contextHandle,
        nullptr);                         // profile (null = no profiling)
    if (err != QNN_SUCCESS) {
        LOGE("QNN contextCreateFromBinary failed: %lu", err);
        LOGE("  Possible causes: wrong HTP stub, corrupt .bin, or device mismatch");
        return false;
    }
    LOGI("QNN context created from binary");

    // Step 3: Retrieve the encoder graph using discovered name
    bool graphFound = false;

    // Try discovered name first, then common fallbacks
    std::vector<std::string> namesToTry;
    if (!discoveredName.empty()) {
        namesToTry.push_back(discoveredName);
    }
    namesToTry.push_back("main");
    namesToTry.push_back("model");
    namesToTry.push_back("encoder");
    namesToTry.push_back("forward");
    namesToTry.push_back("torch_jit");

    for (const auto& name : namesToTry) {
        err = g_qnn.qnnInterface.graphRetrieve(
            g_qnn.contextHandle,
            name.c_str(),
            &g_qnn.graphHandle);
        if (err == QNN_SUCCESS) {
            LOGI("QNN graph retrieved with name: %s", name.c_str());
            graphFound = true;
            break;
        }
    }

    if (!graphFound) {
        LOGE("Failed to retrieve graph from context binary");
        LOGE("  Tried: discovered=\"%s\" + fallbacks", discoveredName.c_str());
        return false;
    }

    g_qnn.contextLoaded = true;
    LOGI("QNN context binary loaded and graph retrieved successfully");
    LOGI("  HTP delegation is now active for Whisper encoder on Hexagon V75");
    return true;
}

bool isInitialized() {
    return g_qnn.initialized && g_qnn.contextLoaded;
}

bool executeGraph(const float* inputData, size_t inputSize,
                  float* outputData, size_t outputSize) {
    if (!isInitialized()) {
        LOGE("QNN not ready — initialize() and loadContextBinary() first");
        return false;
    }

    // =====================================================================
    // Set up input tensor
    // WHY: QNN tensors for context binary graphs use QNN_TENSOR_TYPE_APP_WRITE
    // for inputs and QNN_TENSOR_TYPE_APP_READ for outputs.
    // Tensor IDs and names MUST match what the context binary expects.
    // These are discovered at load time via discoverTensorMetadata() — never
    // hardcode them, as AI Hub assigns new IDs on every compilation.
    //   Input: mel spectrogram [1, 80, 3000] float32
    //   Output: encoder embeddings [1, 1500, N] float32 (N=384/512/768/1024/1280)
    // =====================================================================

    // Input tensor: mel spectrogram
    // QNN: Use discovered specs if available, otherwise fall back to defaults
    uint32_t inputDims[] = {1, 80, 3000};
    uint32_t inputId = g_qnn.tensorSpecsDiscovered ? g_qnn.inputSpec.id : 0;
    const char* inputName = g_qnn.tensorSpecsDiscovered
        ? g_qnn.inputSpec.name.c_str() : "mel";

    Qnn_ClientBuffer_t inputClientBuf = {
        .data = const_cast<float*>(inputData),
        .dataSize = static_cast<uint32_t>(inputSize * sizeof(float))
    };
    Qnn_TensorV2_t inputTensorV2 = {};
    inputTensorV2.id = inputId;
    inputTensorV2.name = inputName;
    inputTensorV2.type = QNN_TENSOR_TYPE_APP_WRITE;
    inputTensorV2.dataFormat = QNN_TENSOR_DATA_FORMAT_DENSE;
    inputTensorV2.dataType = g_qnn.tensorSpecsDiscovered
        ? g_qnn.inputSpec.dataType : QNN_DATATYPE_FLOAT_32;
    inputTensorV2.rank = 3;
    inputTensorV2.dimensions = inputDims;
    inputTensorV2.clientBuf = inputClientBuf;
    inputTensorV2.isDynamicDimensions = nullptr;

    Qnn_Tensor_t inputTensor = {};
    inputTensor.version = QNN_TENSOR_VERSION_2;
    inputTensor.v2 = inputTensorV2;

    // Output tensor: encoder embeddings
    // QNN: Use discovered specs if available, otherwise derive from outputSize
    uint32_t outputElements = static_cast<uint32_t>(outputSize);
    uint32_t outputDims[4];  // max rank 4
    uint32_t outputRank = 3;
    uint32_t outputId = 0;
    const char* outputName = "output_0";
    Qnn_DataType_t outputDataType = QNN_DATATYPE_FLOAT_32;

    if (g_qnn.tensorSpecsDiscovered) {
        outputId = g_qnn.outputSpec.id;
        outputName = g_qnn.outputSpec.name.c_str();
        outputDataType = g_qnn.outputSpec.dataType;
        outputRank = g_qnn.outputSpec.rank;
        for (uint32_t d = 0; d < outputRank && d < 4; d++) {
            outputDims[d] = g_qnn.outputSpec.dimensions[d];
        }
    } else {
        // Fallback: derive dimensions from output buffer size
        // For small.en: [1, 1500, 768] → 1,152,000 floats
        uint32_t encFrames = 1500;
        uint32_t encState = outputElements / encFrames;  // 768 for small.en
        outputDims[0] = 1;
        outputDims[1] = encFrames;
        outputDims[2] = encState;
    }

    Qnn_ClientBuffer_t outputClientBuf = {
        .data = outputData,
        .dataSize = static_cast<uint32_t>(outputSize * sizeof(float))
    };
    Qnn_TensorV2_t outputTensorV2 = {};
    outputTensorV2.id = outputId;
    outputTensorV2.name = outputName;
    outputTensorV2.type = QNN_TENSOR_TYPE_APP_READ;
    outputTensorV2.dataFormat = QNN_TENSOR_DATA_FORMAT_DENSE;
    outputTensorV2.dataType = outputDataType;
    outputTensorV2.rank = outputRank;
    outputTensorV2.dimensions = outputDims;
    outputTensorV2.clientBuf = outputClientBuf;
    outputTensorV2.isDynamicDimensions = nullptr;

    Qnn_Tensor_t outputTensor = {};
    outputTensor.version = QNN_TENSOR_VERSION_2;
    outputTensor.v2 = outputTensorV2;

    // Execute graph
    Qnn_ErrorHandle_t err = g_qnn.qnnInterface.graphExecute(
        g_qnn.graphHandle,
        &inputTensor,
        1,              // numInputs
        &outputTensor,
        1,              // numOutputs
        nullptr,        // profileHandle
        nullptr);       // signalHandle

    if (err != QNN_SUCCESS) {
        LOGE("QNN graphExecute failed: %lu", err);
        return false;
    }

    return true;
}

void release() {
    // QNN: Destroy power config BEFORE device/backend teardown.
    // The power config ID is tied to the device — freeing the device first
    // would leave an orphaned config that can't be cleaned up.
    if (g_qnn.perfConfigured && g_qnn.perfInfra && g_qnn.perfInfra->destroyPowerConfigId) {
        Qnn_ErrorHandle_t err = g_qnn.perfInfra->destroyPowerConfigId(g_qnn.powerConfigId);
        if (err != QNN_SUCCESS) {
            LOGW("destroyPowerConfigId failed: %lu", err);
        } else {
            LOGI("QNN power config destroyed");
        }
    }
    g_qnn.perfInfra = nullptr;  // backend-owned memory — don't free
    g_qnn.powerConfigId = 0;
    g_qnn.perfConfigured = false;

    if (g_qnn.contextHandle) {
        g_qnn.qnnInterface.contextFree(g_qnn.contextHandle, nullptr);
        g_qnn.contextHandle = nullptr;
    }
    if (g_qnn.deviceHandle) {
        g_qnn.qnnInterface.deviceFree(g_qnn.deviceHandle);
        g_qnn.deviceHandle = nullptr;
    }
    if (g_qnn.backendHandle) {
        g_qnn.qnnInterface.backendFree(g_qnn.backendHandle);
        g_qnn.backendHandle = nullptr;
    }
    if (g_qnn.libHandle) {
        // WHY: Don't dlclose — the Java side loaded it via System.loadLibrary
        // and may still hold references. Just null our handle.
        g_qnn.libHandle = nullptr;
    }
    g_qnn.graphHandle = nullptr;
    g_qnn.initialized = false;
    g_qnn.contextLoaded = false;
    g_qnn.tensorSpecsDiscovered = false;
    LOGI("QNN resources released");
}

} // namespace qnn
} // namespace aria
