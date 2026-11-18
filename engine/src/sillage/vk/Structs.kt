package sillage.vk

import sillage.vk.Field.*

// Vulkan structs used by the engine, field for field as in the registry (vk.xml).

private fun s(name: String, vararg f: Pair<String, Field>) = Struct(name, *f)
private fun t(name: String, sType: Int, vararg f: Pair<String, Field>) =
    Struct(name, "sType" to U32, "pNext" to Ptr, *f, sType = sType)
private fun n(st: Struct) = Nested(st)

internal val VkExtent2D = s("VkExtent2D", "width" to U32, "height" to U32)
internal val VkExtent3D = s("VkExtent3D", "width" to U32, "height" to U32, "depth" to U32)
internal val VkOffset2D = s("VkOffset2D", "x" to U32, "y" to U32)
internal val VkOffset3D = s("VkOffset3D", "x" to U32, "y" to U32, "z" to U32)
internal val VkRect2D = s("VkRect2D", "offset" to n(VkOffset2D), "extent" to n(VkExtent2D))
internal val VkComponentMapping = s("VkComponentMapping", "r" to U32, "g" to U32, "b" to U32, "a" to U32)
internal val VkImageSubresourceRange = s("VkImageSubresourceRange",
    "aspectMask" to U32, "baseMipLevel" to U32, "levelCount" to U32, "baseArrayLayer" to U32, "layerCount" to U32)
internal val VkImageSubresourceLayers = s("VkImageSubresourceLayers",
    "aspectMask" to U32, "mipLevel" to U32, "baseArrayLayer" to U32, "layerCount" to U32)

internal val VkApplicationInfo = t("VkApplicationInfo", VK_STRUCTURE_TYPE_APPLICATION_INFO,
    "pApplicationName" to Ptr, "applicationVersion" to U32, "pEngineName" to Ptr, "engineVersion" to U32, "apiVersion" to U32)
internal val VkInstanceCreateInfo = t("VkInstanceCreateInfo", VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
    "flags" to U32, "pApplicationInfo" to Ptr, "enabledLayerCount" to U32, "ppEnabledLayerNames" to Ptr,
    "enabledExtensionCount" to U32, "ppEnabledExtensionNames" to Ptr)
internal val VkLayerProperties = s("VkLayerProperties",
    "layerName" to Chars(256), "specVersion" to U32, "implementationVersion" to U32, "description" to Chars(256))
internal val VkExtensionProperties = s("VkExtensionProperties", "extensionName" to Chars(256), "specVersion" to U32)

internal val VkDebugUtilsMessengerCreateInfoEXT = t("VkDebugUtilsMessengerCreateInfoEXT", VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT,
    "flags" to U32, "messageSeverity" to U32, "messageType" to U32, "pfnUserCallback" to Ptr, "pUserData" to Ptr)
internal val VkDebugUtilsMessengerCallbackDataEXT = s("VkDebugUtilsMessengerCallbackDataEXT",
    "sType" to U32, "pNext" to Ptr, "flags" to U32, "pMessageIdName" to Ptr, "messageIdNumber" to U32, "pMessage" to Ptr,
    "queueLabelCount" to U32, "pQueueLabels" to Ptr, "cmdBufLabelCount" to U32, "pCmdBufLabels" to Ptr,
    "objectCount" to U32, "pObjects" to Ptr)

internal val VkWaylandSurfaceCreateInfoKHR = t("VkWaylandSurfaceCreateInfoKHR", VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR,
    "flags" to U32, "display" to Ptr, "surface" to Ptr)
internal val VkXcbSurfaceCreateInfoKHR = t("VkXcbSurfaceCreateInfoKHR", VK_STRUCTURE_TYPE_XCB_SURFACE_CREATE_INFO_KHR,
    "flags" to U32, "connection" to Ptr, "window" to U32)

internal val VkQueueFamilyProperties = s("VkQueueFamilyProperties",
    "queueFlags" to U32, "queueCount" to U32, "timestampValidBits" to U32, "minImageTransferGranularity" to n(VkExtent3D))
internal val VkPhysicalDeviceLimits = s("VkPhysicalDeviceLimits",
    *listOf("maxImageDimension1D", "maxImageDimension2D", "maxImageDimension3D", "maxImageDimensionCube", "maxImageArrayLayers",
        "maxTexelBufferElements", "maxUniformBufferRange", "maxStorageBufferRange", "maxPushConstantsSize",
        "maxMemoryAllocationCount", "maxSamplerAllocationCount").map { it to U32 }.toTypedArray(),
    "bufferImageGranularity" to U64, "sparseAddressSpaceSize" to U64,
    *listOf("maxBoundDescriptorSets", "maxPerStageDescriptorSamplers", "maxPerStageDescriptorUniformBuffers",
        "maxPerStageDescriptorStorageBuffers", "maxPerStageDescriptorSampledImages", "maxPerStageDescriptorStorageImages",
        "maxPerStageDescriptorInputAttachments", "maxPerStageResources", "maxDescriptorSetSamplers",
        "maxDescriptorSetUniformBuffers", "maxDescriptorSetUniformBuffersDynamic", "maxDescriptorSetStorageBuffers",
        "maxDescriptorSetStorageBuffersDynamic", "maxDescriptorSetSampledImages", "maxDescriptorSetStorageImages",
        "maxDescriptorSetInputAttachments", "maxVertexInputAttributes", "maxVertexInputBindings",
        "maxVertexInputAttributeOffset", "maxVertexInputBindingStride", "maxVertexOutputComponents",
        "maxTessellationGenerationLevel", "maxTessellationPatchSize", "maxTessellationControlPerVertexInputComponents",
        "maxTessellationControlPerVertexOutputComponents", "maxTessellationControlPerPatchOutputComponents",
        "maxTessellationControlTotalOutputComponents", "maxTessellationEvaluationInputComponents",
        "maxTessellationEvaluationOutputComponents", "maxGeometryShaderInvocations", "maxGeometryInputComponents",
        "maxGeometryOutputComponents", "maxGeometryOutputVertices", "maxGeometryTotalOutputComponents",
        "maxFragmentInputComponents", "maxFragmentOutputAttachments", "maxFragmentDualSrcAttachments",
        "maxFragmentCombinedOutputResources", "maxComputeSharedMemorySize").map { it to U32 }.toTypedArray(),
    "maxComputeWorkGroupCount" to Field.Array(U32, 3), "maxComputeWorkGroupInvocations" to U32,
    "maxComputeWorkGroupSize" to Field.Array(U32, 3),
    "subPixelPrecisionBits" to U32, "subTexelPrecisionBits" to U32, "mipmapPrecisionBits" to U32,
    "maxDrawIndexedIndexValue" to U32, "maxDrawIndirectCount" to U32, "maxSamplerLodBias" to F32, "maxSamplerAnisotropy" to F32,
    "maxViewports" to U32, "maxViewportDimensions" to Field.Array(U32, 2), "viewportBoundsRange" to Field.Array(F32, 2),
    "viewportSubPixelBits" to U32, "minMemoryMapAlignment" to U64, "minTexelBufferOffsetAlignment" to U64,
    "minUniformBufferOffsetAlignment" to U64, "minStorageBufferOffsetAlignment" to U64,
    "minTexelOffset" to U32, "maxTexelOffset" to U32, "minTexelGatherOffset" to U32, "maxTexelGatherOffset" to U32,
    "minInterpolationOffset" to F32, "maxInterpolationOffset" to F32, "subPixelInterpolationOffsetBits" to U32,
    "maxFramebufferWidth" to U32, "maxFramebufferHeight" to U32, "maxFramebufferLayers" to U32,
    "framebufferColorSampleCounts" to U32, "framebufferDepthSampleCounts" to U32, "framebufferStencilSampleCounts" to U32,
    "framebufferNoAttachmentsSampleCounts" to U32, "maxColorAttachments" to U32, "sampledImageColorSampleCounts" to U32,
    "sampledImageIntegerSampleCounts" to U32, "sampledImageDepthSampleCounts" to U32, "sampledImageStencilSampleCounts" to U32,
    "storageImageSampleCounts" to U32, "maxSampleMaskWords" to U32, "timestampComputeAndGraphics" to U32,
    "timestampPeriod" to F32, "maxClipDistances" to U32, "maxCullDistances" to U32, "maxCombinedClipAndCullDistances" to U32,
    "discreteQueuePriorities" to U32, "pointSizeRange" to Field.Array(F32, 2), "lineWidthRange" to Field.Array(F32, 2),
    "pointSizeGranularity" to F32, "lineWidthGranularity" to F32, "strictLines" to U32, "standardSampleLocations" to U32,
    "optimalBufferCopyOffsetAlignment" to U64, "optimalBufferCopyRowPitchAlignment" to U64, "nonCoherentAtomSize" to U64)
internal val VkPhysicalDeviceSparseProperties = s("VkPhysicalDeviceSparseProperties",
    "residencyStandard2DBlockShape" to U32, "residencyStandard2DMultisampleBlockShape" to U32,
    "residencyStandard3DBlockShape" to U32, "residencyAlignedMipSize" to U32, "residencyNonResidentStrict" to U32)
internal val VkPhysicalDeviceProperties = s("VkPhysicalDeviceProperties",
    "apiVersion" to U32, "driverVersion" to U32, "vendorID" to U32, "deviceID" to U32, "deviceType" to U32,
    "deviceName" to Chars(256), "pipelineCacheUUID" to Bytes(16), "limits" to n(VkPhysicalDeviceLimits),
    "sparseProperties" to n(VkPhysicalDeviceSparseProperties))
internal val VkFormatProperties = s("VkFormatProperties",
    "linearTilingFeatures" to U32, "optimalTilingFeatures" to U32, "bufferFeatures" to U32)
internal val VkMemoryType = s("VkMemoryType", "propertyFlags" to U32, "heapIndex" to U32)
internal val VkMemoryHeap = s("VkMemoryHeap", "size" to U64, "flags" to U32)
internal val VkPhysicalDeviceMemoryProperties = s("VkPhysicalDeviceMemoryProperties",
    "memoryTypeCount" to U32, "memoryTypes" to Field.Array(n(VkMemoryType), 32),
    "memoryHeapCount" to U32, "memoryHeaps" to Field.Array(n(VkMemoryHeap), 16))
internal val VkMemoryRequirements = s("VkMemoryRequirements", "size" to U64, "alignment" to U64, "memoryTypeBits" to U32)

internal val VkDeviceQueueCreateInfo = t("VkDeviceQueueCreateInfo", VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
    "flags" to U32, "queueFamilyIndex" to U32, "queueCount" to U32, "pQueuePriorities" to Ptr)
internal val VkDeviceCreateInfo = t("VkDeviceCreateInfo", VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
    "flags" to U32, "queueCreateInfoCount" to U32, "pQueueCreateInfos" to Ptr, "enabledLayerCount" to U32,
    "ppEnabledLayerNames" to Ptr, "enabledExtensionCount" to U32, "ppEnabledExtensionNames" to Ptr, "pEnabledFeatures" to Ptr)
internal val VkCommandPoolCreateInfo = t("VkCommandPoolCreateInfo", VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
    "flags" to U32, "queueFamilyIndex" to U32)
internal val VkCommandBufferAllocateInfo = t("VkCommandBufferAllocateInfo", VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
    "commandPool" to U64, "level" to U32, "commandBufferCount" to U32)
internal val VkSemaphoreCreateInfo = t("VkSemaphoreCreateInfo", VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, "flags" to U32)
internal val VkFenceCreateInfo = t("VkFenceCreateInfo", VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, "flags" to U32)
internal val VkBufferCreateInfo = t("VkBufferCreateInfo", VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
    "flags" to U32, "size" to U64, "usage" to U32, "sharingMode" to U32, "queueFamilyIndexCount" to U32, "pQueueFamilyIndices" to Ptr)
internal val VkMemoryAllocateInfo = t("VkMemoryAllocateInfo", VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
    "allocationSize" to U64, "memoryTypeIndex" to U32)
internal val VkImageCreateInfo = t("VkImageCreateInfo", VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
    "flags" to U32, "imageType" to U32, "format" to U32, "extent" to n(VkExtent3D), "mipLevels" to U32, "arrayLayers" to U32,
    "samples" to U32, "tiling" to U32, "usage" to U32, "sharingMode" to U32, "queueFamilyIndexCount" to U32,
    "pQueueFamilyIndices" to Ptr, "initialLayout" to U32)
internal val VkImageViewCreateInfo = t("VkImageViewCreateInfo", VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
    "flags" to U32, "image" to U64, "viewType" to U32, "format" to U32, "components" to n(VkComponentMapping),
    "subresourceRange" to n(VkImageSubresourceRange))

internal val VkAttachmentDescription = s("VkAttachmentDescription",
    "flags" to U32, "format" to U32, "samples" to U32, "loadOp" to U32, "storeOp" to U32, "stencilLoadOp" to U32,
    "stencilStoreOp" to U32, "initialLayout" to U32, "finalLayout" to U32)
internal val VkAttachmentReference = s("VkAttachmentReference", "attachment" to U32, "layout" to U32)
internal val VkSubpassDescription = s("VkSubpassDescription",
    "flags" to U32, "pipelineBindPoint" to U32, "inputAttachmentCount" to U32, "pInputAttachments" to Ptr,
    "colorAttachmentCount" to U32, "pColorAttachments" to Ptr, "pResolveAttachments" to Ptr,
    "pDepthStencilAttachment" to Ptr, "preserveAttachmentCount" to U32, "pPreserveAttachments" to Ptr)
internal val VkSubpassDependency = s("VkSubpassDependency",
    "srcSubpass" to U32, "dstSubpass" to U32, "srcStageMask" to U32, "dstStageMask" to U32,
    "srcAccessMask" to U32, "dstAccessMask" to U32, "dependencyFlags" to U32)
internal val VkRenderPassCreateInfo = t("VkRenderPassCreateInfo", VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
    "flags" to U32, "attachmentCount" to U32, "pAttachments" to Ptr, "subpassCount" to U32, "pSubpasses" to Ptr,
    "dependencyCount" to U32, "pDependencies" to Ptr)
internal val VkFramebufferCreateInfo = t("VkFramebufferCreateInfo", VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
    "flags" to U32, "renderPass" to U64, "attachmentCount" to U32, "pAttachments" to Ptr,
    "width" to U32, "height" to U32, "layers" to U32)

internal val VkShaderModuleCreateInfo = t("VkShaderModuleCreateInfo", VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
    "flags" to U32, "codeSize" to U64, "pCode" to Ptr)
internal val VkPipelineShaderStageCreateInfo = t("VkPipelineShaderStageCreateInfo", VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
    "flags" to U32, "stage" to U32, "module" to U64, "pName" to Ptr, "pSpecializationInfo" to Ptr)
internal val VkVertexInputBindingDescription = s("VkVertexInputBindingDescription",
    "binding" to U32, "stride" to U32, "inputRate" to U32)
internal val VkVertexInputAttributeDescription = s("VkVertexInputAttributeDescription",
    "location" to U32, "binding" to U32, "format" to U32, "offset" to U32)
internal val VkPipelineVertexInputStateCreateInfo = t("VkPipelineVertexInputStateCreateInfo", VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
    "flags" to U32, "vertexBindingDescriptionCount" to U32, "pVertexBindingDescriptions" to Ptr,
    "vertexAttributeDescriptionCount" to U32, "pVertexAttributeDescriptions" to Ptr)
internal val VkPipelineInputAssemblyStateCreateInfo = t("VkPipelineInputAssemblyStateCreateInfo", VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
    "flags" to U32, "topology" to U32, "primitiveRestartEnable" to U32)
internal val VkViewport = s("VkViewport",
    "x" to F32, "y" to F32, "width" to F32, "height" to F32, "minDepth" to F32, "maxDepth" to F32)
internal val VkPipelineViewportStateCreateInfo = t("VkPipelineViewportStateCreateInfo", VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
    "flags" to U32, "viewportCount" to U32, "pViewports" to Ptr, "scissorCount" to U32, "pScissors" to Ptr)
internal val VkPipelineRasterizationStateCreateInfo = t("VkPipelineRasterizationStateCreateInfo", VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
    "flags" to U32, "depthClampEnable" to U32, "rasterizerDiscardEnable" to U32, "polygonMode" to U32, "cullMode" to U32,
    "frontFace" to U32, "depthBiasEnable" to U32, "depthBiasConstantFactor" to F32, "depthBiasClamp" to F32,
    "depthBiasSlopeFactor" to F32, "lineWidth" to F32)
internal val VkPipelineMultisampleStateCreateInfo = t("VkPipelineMultisampleStateCreateInfo", VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
    "flags" to U32, "rasterizationSamples" to U32, "sampleShadingEnable" to U32, "minSampleShading" to F32,
    "pSampleMask" to Ptr, "alphaToCoverageEnable" to U32, "alphaToOneEnable" to U32)
internal val VkStencilOpState = s("VkStencilOpState",
    "failOp" to U32, "passOp" to U32, "depthFailOp" to U32, "compareOp" to U32, "compareMask" to U32,
    "writeMask" to U32, "reference" to U32)
internal val VkPipelineDepthStencilStateCreateInfo = t("VkPipelineDepthStencilStateCreateInfo", VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO,
    "flags" to U32, "depthTestEnable" to U32, "depthWriteEnable" to U32, "depthCompareOp" to U32,
    "depthBoundsTestEnable" to U32, "stencilTestEnable" to U32, "front" to n(VkStencilOpState), "back" to n(VkStencilOpState),
    "minDepthBounds" to F32, "maxDepthBounds" to F32)
internal val VkPipelineColorBlendAttachmentState = s("VkPipelineColorBlendAttachmentState",
    "blendEnable" to U32, "srcColorBlendFactor" to U32, "dstColorBlendFactor" to U32, "colorBlendOp" to U32,
    "srcAlphaBlendFactor" to U32, "dstAlphaBlendFactor" to U32, "alphaBlendOp" to U32, "colorWriteMask" to U32)
internal val VkPipelineColorBlendStateCreateInfo = t("VkPipelineColorBlendStateCreateInfo", VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
    "flags" to U32, "logicOpEnable" to U32, "logicOp" to U32, "attachmentCount" to U32, "pAttachments" to Ptr,
    "blendConstants" to Field.Array(F32, 4))
internal val VkPushConstantRange = s("VkPushConstantRange", "stageFlags" to U32, "offset" to U32, "size" to U32)
internal val VkPipelineLayoutCreateInfo = t("VkPipelineLayoutCreateInfo", VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
    "flags" to U32, "setLayoutCount" to U32, "pSetLayouts" to Ptr, "pushConstantRangeCount" to U32, "pPushConstantRanges" to Ptr)
internal val VkGraphicsPipelineCreateInfo = t("VkGraphicsPipelineCreateInfo", VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
    "flags" to U32, "stageCount" to U32, "pStages" to Ptr, "pVertexInputState" to Ptr, "pInputAssemblyState" to Ptr,
    "pTessellationState" to Ptr, "pViewportState" to Ptr, "pRasterizationState" to Ptr, "pMultisampleState" to Ptr,
    "pDepthStencilState" to Ptr, "pColorBlendState" to Ptr, "pDynamicState" to Ptr, "layout" to U64, "renderPass" to U64,
    "subpass" to U32, "basePipelineHandle" to U64, "basePipelineIndex" to U32)

internal val VkSurfaceCapabilitiesKHR = s("VkSurfaceCapabilitiesKHR",
    "minImageCount" to U32, "maxImageCount" to U32, "currentExtent" to n(VkExtent2D), "minImageExtent" to n(VkExtent2D),
    "maxImageExtent" to n(VkExtent2D), "maxImageArrayLayers" to U32, "supportedTransforms" to U32,
    "currentTransform" to U32, "supportedCompositeAlpha" to U32, "supportedUsageFlags" to U32)
internal val VkSurfaceFormatKHR = s("VkSurfaceFormatKHR", "format" to U32, "colorSpace" to U32)
internal val VkSwapchainCreateInfoKHR = t("VkSwapchainCreateInfoKHR", VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
    "flags" to U32, "surface" to U64, "minImageCount" to U32, "imageFormat" to U32, "imageColorSpace" to U32,
    "imageExtent" to n(VkExtent2D), "imageArrayLayers" to U32, "imageUsage" to U32, "imageSharingMode" to U32,
    "queueFamilyIndexCount" to U32, "pQueueFamilyIndices" to Ptr, "preTransform" to U32, "compositeAlpha" to U32,
    "presentMode" to U32, "clipped" to U32, "oldSwapchain" to U64)
internal val VkPresentInfoKHR = t("VkPresentInfoKHR", VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
    "waitSemaphoreCount" to U32, "pWaitSemaphores" to Ptr, "swapchainCount" to U32, "pSwapchains" to Ptr,
    "pImageIndices" to Ptr, "pResults" to Ptr)

internal val VkCommandBufferBeginInfo = t("VkCommandBufferBeginInfo", VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
    "flags" to U32, "pInheritanceInfo" to Ptr)
/** VkClearValue is a 16-byte union: color.float32[4] or depthStencil {depth, stencil}. */
internal val VkClearValue = s("VkClearValue", "float32" to Field.Array(F32, 4))
internal val VkClearDepthStencilValue = s("VkClearDepthStencilValue", "depth" to F32, "stencil" to U32)
internal val VkRenderPassBeginInfo = t("VkRenderPassBeginInfo", VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
    "renderPass" to U64, "framebuffer" to U64, "renderArea" to n(VkRect2D), "clearValueCount" to U32, "pClearValues" to Ptr)
internal val VkImageMemoryBarrier = t("VkImageMemoryBarrier", VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
    "srcAccessMask" to U32, "dstAccessMask" to U32, "oldLayout" to U32, "newLayout" to U32,
    "srcQueueFamilyIndex" to U32, "dstQueueFamilyIndex" to U32, "image" to U64, "subresourceRange" to n(VkImageSubresourceRange))
internal val VkMemoryBarrier = t("VkMemoryBarrier", VK_STRUCTURE_TYPE_MEMORY_BARRIER, "srcAccessMask" to U32, "dstAccessMask" to U32)
internal val VkBufferImageCopy = s("VkBufferImageCopy",
    "bufferOffset" to U64, "bufferRowLength" to U32, "bufferImageHeight" to U32,
    "imageSubresource" to n(VkImageSubresourceLayers), "imageOffset" to n(VkOffset3D), "imageExtent" to n(VkExtent3D))
internal val VkImageBlit = s("VkImageBlit",
    "srcSubresource" to n(VkImageSubresourceLayers), "srcOffsets" to Field.Array(n(VkOffset3D), 2),
    "dstSubresource" to n(VkImageSubresourceLayers), "dstOffsets" to Field.Array(n(VkOffset3D), 2))
internal val VkSubmitInfo = t("VkSubmitInfo", VK_STRUCTURE_TYPE_SUBMIT_INFO,
    "waitSemaphoreCount" to U32, "pWaitSemaphores" to Ptr, "pWaitDstStageMask" to Ptr, "commandBufferCount" to U32,
    "pCommandBuffers" to Ptr, "signalSemaphoreCount" to U32, "pSignalSemaphores" to Ptr)

/** Every struct above, for the layout tests. */
internal val ALL_STRUCTS = listOf(VkExtent2D, VkExtent3D, VkOffset2D, VkOffset3D, VkRect2D, VkComponentMapping,
    VkImageSubresourceRange, VkImageSubresourceLayers, VkApplicationInfo, VkInstanceCreateInfo, VkLayerProperties,
    VkExtensionProperties, VkDebugUtilsMessengerCreateInfoEXT, VkDebugUtilsMessengerCallbackDataEXT,
    VkWaylandSurfaceCreateInfoKHR, VkXcbSurfaceCreateInfoKHR, VkQueueFamilyProperties, VkPhysicalDeviceLimits,
    VkPhysicalDeviceSparseProperties, VkPhysicalDeviceProperties, VkFormatProperties, VkMemoryType, VkMemoryHeap,
    VkPhysicalDeviceMemoryProperties, VkMemoryRequirements, VkDeviceQueueCreateInfo, VkDeviceCreateInfo,
    VkCommandPoolCreateInfo, VkCommandBufferAllocateInfo, VkSemaphoreCreateInfo, VkFenceCreateInfo, VkBufferCreateInfo,
    VkMemoryAllocateInfo, VkImageCreateInfo, VkImageViewCreateInfo, VkAttachmentDescription, VkAttachmentReference,
    VkSubpassDescription, VkSubpassDependency, VkRenderPassCreateInfo, VkFramebufferCreateInfo, VkShaderModuleCreateInfo,
    VkPipelineShaderStageCreateInfo, VkVertexInputBindingDescription, VkVertexInputAttributeDescription,
    VkPipelineVertexInputStateCreateInfo, VkPipelineInputAssemblyStateCreateInfo, VkViewport,
    VkPipelineViewportStateCreateInfo, VkPipelineRasterizationStateCreateInfo, VkPipelineMultisampleStateCreateInfo,
    VkStencilOpState, VkPipelineDepthStencilStateCreateInfo, VkPipelineColorBlendAttachmentState,
    VkPipelineColorBlendStateCreateInfo, VkPushConstantRange, VkPipelineLayoutCreateInfo, VkGraphicsPipelineCreateInfo,
    VkSurfaceCapabilitiesKHR, VkSurfaceFormatKHR, VkSwapchainCreateInfoKHR, VkPresentInfoKHR, VkCommandBufferBeginInfo,
    VkClearValue, VkClearDepthStencilValue, VkRenderPassBeginInfo, VkImageMemoryBarrier, VkMemoryBarrier,
    VkBufferImageCopy, VkImageBlit, VkSubmitInfo)
