package sillage

import sillage.math.Mat4
import sillage.vk.*
import java.awt.image.BufferedImage
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import javax.imageio.ImageIO

/** What the renderer needs from a window: instance extensions, a VkSurfaceKHR and the drawable size. */
internal interface SurfaceSource {
    val instanceExtensions: List<String>
    fun createSurface(vk: VkInstanceApi, stack: NativeStack): Long
    /** Drawable size in pixels as (width, height); zero while minimised. */
    fun framebufferSize(): IntArray
}

/** Backend Vulkan direct, appelé par FFM. Une image en vol : synchronisation volontairement simple pour la v0.1.
 * Le même render pass fonctionne avec une fenêtre ou sans serveur graphique. */
internal class VulkanRenderer(private val target: SurfaceSource? = null, private val validation: Boolean = false) : AutoCloseable {
    val largeur=960
    val hauteur=600
    private val arena=Arena.ofConfined()
    private val stack=NativeStack(1L shl 20, arena)
    private lateinit var vk: VkInstanceApi
    private lateinit var dev: VkDeviceApi
    private var physical: MemorySegment=MemorySegment.NULL
    private var queue: MemorySegment=MemorySegment.NULL
    private var command: MemorySegment=MemorySegment.NULL
    private var family=0
    private var surface=0L
    private var swapchain=0L
    private var swapImages=LongArray(0)
    private var swapWidth=0
    private var swapHeight=0
    private val colorFormat=VK_FORMAT_R8G8B8A8_UNORM
    private var memoryFlags=IntArray(0)
    private var pool=0L
    private var renderPass=0L
    private var framebuffer=0L
    private var layout=0L
    private var pipeline=0L
    private var acquired=0L
    private var finished=0L
    private var messenger=0L
    var erreursValidation=0
        private set
    private class Image(val image: Long, val memory: Long, val view: Long)
    private class Buffer(val buffer: Long, val memory: Long, val size: Long)
    private var color: Image?=null
    private var depth: Image?=null
    private var hudBuffer: Buffer?=null
    private val meshes=IdentityHashMap<Maillage,Buffer>()
    private var ferme=false
    var nomGpu=""
        private set

    fun ouvrir() {
        try {
            createInstance()
            if(target != null) surface=target.createSurface(vk,stack)
            chooseDevice()
            createDevice()
            stack.frame { s ->
                val out=s.allocate(JAVA_LONG)
                dev.createCommandPool(s.struct(VkCommandPoolCreateInfo,
                    "flags" to VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, "queueFamilyIndex" to family),out)
                pool=out.get(JAVA_LONG,0)
                val cmd=s.allocate(ADDRESS)
                dev.allocateCommandBuffers(s.struct(VkCommandBufferAllocateInfo,
                    "commandPool" to pool, "level" to VK_COMMAND_BUFFER_LEVEL_PRIMARY, "commandBufferCount" to 1),cmd)
                command=cmd.get(ADDRESS,0)
                val si=s.struct(VkSemaphoreCreateInfo)
                dev.createSemaphore(si,out); acquired=out.get(JAVA_LONG,0)
                dev.createSemaphore(si,out); finished=out.get(JAVA_LONG,0)
            }
            color=image(colorFormat,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_IMAGE_ASPECT_COLOR_BIT)
            depth=image(VK_FORMAT_D32_SFLOAT,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,VK_IMAGE_ASPECT_DEPTH_BIT)
            createRenderPass()
            createPipeline()
            hudBuffer=buffer(4L*1024*1024,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
            if(surface != 0L) recreateSwapchain()
            println("SILLAGE_VULKAN_OK GPU=" + nomGpu + " target=" + largeur + "x" + hauteur + " validation=" + validation)
        } catch(e: Throwable) { close(); throw e }
    }

    private fun createInstance() = stack.frame { s ->
        if(validation) require("VK_LAYER_KHRONOS_validation" in VkLoader.layerNames()) {
            "Installer vulkan-validation-layers pour utiliser --validation" }
        val extensions=(target?.instanceExtensions ?: emptyList()) + (if(validation) listOf(VK_EXT_DEBUG_UTILS_EXTENSION_NAME) else emptyList())
        val app=s.struct(VkApplicationInfo, "pApplicationName" to s.allocateFrom("Sillage"),
            "pEngineName" to s.allocateFrom("Sillage Kotlin"), "apiVersion" to VK_API_VERSION_1_0)
        val ci=s.struct(VkInstanceCreateInfo, "pApplicationInfo" to app)
        if(extensions.isNotEmpty()) ci.put(VkInstanceCreateInfo,
            "enabledExtensionCount" to extensions.size, "ppEnabledExtensionNames" to s.strings(extensions))
        if(validation) ci.put(VkInstanceCreateInfo,
            "enabledLayerCount" to 1, "ppEnabledLayerNames" to s.strings(listOf("VK_LAYER_KHRONOS_validation")))
        val out=s.allocate(ADDRESS); VkLoader.createInstance(ci,out); vk=VkInstanceApi(out.get(ADDRESS,0))
        if(validation) {
            val target=MethodHandles.lookup().findVirtual(VulkanRenderer::class.java,"onDebugMessage",
                MethodType.methodType(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,
                    MemorySegment::class.java,MemorySegment::class.java)).bindTo(this)
            val callback=Linker.nativeLinker().upcallStub(target,FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS,ADDRESS),arena)
            val di=s.struct(VkDebugUtilsMessengerCreateInfoEXT,
                "messageSeverity" to (VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT),
                "messageType" to (VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT),
                "pfnUserCallback" to callback)
            val m=s.allocate(JAVA_LONG); vk.createDebugUtilsMessengerEXT(di,m); messenger=m.get(JAVA_LONG,0)
        }
    }

    /** Appelé par la couche de validation, depuis le code natif : ne doit jamais lever d'exception. */
    @Suppress("unused")
    private fun onDebugMessage(severity: Int, types: Int, data: MemorySegment, user: MemorySegment): Int {
        try {
            val d=data.reinterpret(VkDebugUtilsMessengerCallbackDataEXT.size)
            val message=d.ptr(VkDebugUtilsMessengerCallbackDataEXT,"pMessage").reinterpret(Long.MAX_VALUE).getString(0)
            if(severity and VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT != 0) erreursValidation++
            System.err.println("Vulkan: " + message)
        } catch(_: Throwable) { erreursValidation++ }
        return VK_FALSE
    }

    private fun chooseDevice() = stack.frame { s ->
        val n=s.allocate(JAVA_INT); vk.enumeratePhysicalDevices(n,MemorySegment.NULL)
        require(n.get(JAVA_INT,0)>0) { "Aucun pilote Vulkan disponible" }
        val devices=s.allocate(ADDRESS,n.get(JAVA_INT,0).toLong()); vk.enumeratePhysicalDevices(n,devices)
        val props=s.struct(VkPhysicalDeviceProperties)
        var best=-1
        for(i in 0 until n.get(JAVA_INT,0)) {
            val candidate=devices.getAtIndex(ADDRESS,i.toLong())
            val count=s.allocate(JAVA_INT); vk.getPhysicalDeviceQueueFamilyProperties(candidate,count,MemorySegment.NULL)
            val families=s.structs(VkQueueFamilyProperties,count.get(JAVA_INT,0)); vk.getPhysicalDeviceQueueFamilyProperties(candidate,count,families)
            vk.getPhysicalDeviceProperties(candidate,props)
            for(j in 0 until count.get(JAVA_INT,0)) {
                if(families.at(VkQueueFamilyProperties,j).u32(VkQueueFamilyProperties,"queueFlags") and VK_QUEUE_GRAPHICS_BIT == 0) continue
                if(surface != 0L) {
                    val support=s.allocate(JAVA_INT); vk.getPhysicalDeviceSurfaceSupportKHR(candidate,j,surface,support)
                    if(support.get(JAVA_INT,0) != VK_TRUE) continue
                }
                val score=when(props.u32(VkPhysicalDeviceProperties,"deviceType")) {
                    VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU->100; VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU->50; else->1 }
                if(score>best) { best=score; physical=candidate; family=j; nomGpu=props.chars(VkPhysicalDeviceProperties,"deviceName") }
            }
        }
        require(best>=0) { "Aucune queue graphique Vulkan compatible avec la fenêtre" }
        val fp=s.struct(VkFormatProperties); vk.getPhysicalDeviceFormatProperties(physical,VK_FORMAT_D32_SFLOAT,fp)
        require(fp.u32(VkFormatProperties,"optimalTilingFeatures") and VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT != 0) { "D32 non pris en charge" }
        val mp=s.struct(VkPhysicalDeviceMemoryProperties); vk.getPhysicalDeviceMemoryProperties(physical,mp)
        memoryFlags=IntArray(mp.u32(VkPhysicalDeviceMemoryProperties,"memoryTypeCount")) {
            mp.u32(VkPhysicalDeviceMemoryProperties,"memoryTypes[$it].propertyFlags") }
    }

    private fun createDevice() = stack.frame { s ->
        val queues=s.struct(VkDeviceQueueCreateInfo, "queueFamilyIndex" to family, "queueCount" to 1, "pQueuePriorities" to s.floats(1f))
        val ci=s.struct(VkDeviceCreateInfo, "queueCreateInfoCount" to 1, "pQueueCreateInfos" to queues)
        if(surface != 0L) ci.put(VkDeviceCreateInfo,
            "enabledExtensionCount" to 1, "ppEnabledExtensionNames" to s.strings(listOf(VK_KHR_SWAPCHAIN_EXTENSION_NAME)))
        val p=s.allocate(ADDRESS); vk.createDevice(physical,ci,p); dev=VkDeviceApi(p.get(ADDRESS,0),vk,surface != 0L)
        dev.getDeviceQueue(family,0,p); queue=p.get(ADDRESS,0)
    }

    private fun memoryType(bits: Int, flags: Int): Int =
        memoryFlags.indices.firstOrNull { bits and (1 shl it) != 0 && memoryFlags[it] and flags == flags }
            ?: error("Type mémoire Vulkan indisponible, flags=" + flags)

    private fun buffer(size: Long, usage: Int): Buffer = stack.frame { s ->
        val p=s.allocate(JAVA_LONG)
        dev.createBuffer(s.struct(VkBufferCreateInfo, "size" to size, "usage" to usage, "sharingMode" to VK_SHARING_MODE_EXCLUSIVE),p)
        val handle=p.get(JAVA_LONG,0)
        val req=s.struct(VkMemoryRequirements); dev.getBufferMemoryRequirements(handle,req)
        dev.allocateMemory(s.struct(VkMemoryAllocateInfo, "allocationSize" to req.u64(VkMemoryRequirements,"size"),
            "memoryTypeIndex" to memoryType(req.u32(VkMemoryRequirements,"memoryTypeBits"),
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)),p)
        val memory=p.get(JAVA_LONG,0)
        dev.bindBufferMemory(handle,memory,0)
        Buffer(handle,memory,size)
    }

    private fun upload(b: Buffer, vertices: FloatArray) = stack.frame { s ->
        require(vertices.size.toLong()*4<=b.size)
        val p=s.allocate(ADDRESS); dev.mapMemory(b.memory,0,b.size,p)
        try { MemorySegment.copy(vertices,0,p.get(ADDRESS,0).reinterpret(b.size),JAVA_FLOAT,0,vertices.size) }
        finally { dev.unmapMemory(b.memory) }
    }

    private fun image(format: Int, usage: Int, aspect: Int): Image = stack.frame { s ->
        val p=s.allocate(JAVA_LONG)
        dev.createImage(s.struct(VkImageCreateInfo, "imageType" to VK_IMAGE_TYPE_2D, "format" to format,
            "extent.width" to largeur, "extent.height" to hauteur, "extent.depth" to 1, "mipLevels" to 1, "arrayLayers" to 1,
            "samples" to VK_SAMPLE_COUNT_1_BIT, "tiling" to VK_IMAGE_TILING_OPTIMAL, "usage" to usage,
            "sharingMode" to VK_SHARING_MODE_EXCLUSIVE, "initialLayout" to VK_IMAGE_LAYOUT_UNDEFINED),p)
        val img=p.get(JAVA_LONG,0)
        val req=s.struct(VkMemoryRequirements); dev.getImageMemoryRequirements(img,req)
        dev.allocateMemory(s.struct(VkMemoryAllocateInfo, "allocationSize" to req.u64(VkMemoryRequirements,"size"),
            "memoryTypeIndex" to memoryType(req.u32(VkMemoryRequirements,"memoryTypeBits"),VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)),p)
        val mem=p.get(JAVA_LONG,0)
        dev.bindImageMemory(img,mem,0)
        dev.createImageView(s.struct(VkImageViewCreateInfo, "image" to img, "viewType" to VK_IMAGE_VIEW_TYPE_2D, "format" to format,
            "subresourceRange.aspectMask" to aspect, "subresourceRange.levelCount" to 1, "subresourceRange.layerCount" to 1),p)
        Image(img,mem,p.get(JAVA_LONG,0))
    }

    private fun createRenderPass() = stack.frame { s ->
        val a=s.structs(VkAttachmentDescription,2)
        a.at(VkAttachmentDescription,0).put(VkAttachmentDescription, "format" to colorFormat, "samples" to VK_SAMPLE_COUNT_1_BIT,
            "loadOp" to VK_ATTACHMENT_LOAD_OP_CLEAR, "storeOp" to VK_ATTACHMENT_STORE_OP_STORE,
            "stencilLoadOp" to VK_ATTACHMENT_LOAD_OP_DONT_CARE, "stencilStoreOp" to VK_ATTACHMENT_STORE_OP_DONT_CARE,
            "initialLayout" to VK_IMAGE_LAYOUT_UNDEFINED, "finalLayout" to VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
        a.at(VkAttachmentDescription,1).put(VkAttachmentDescription, "format" to VK_FORMAT_D32_SFLOAT, "samples" to VK_SAMPLE_COUNT_1_BIT,
            "loadOp" to VK_ATTACHMENT_LOAD_OP_CLEAR, "storeOp" to VK_ATTACHMENT_STORE_OP_DONT_CARE,
            "stencilLoadOp" to VK_ATTACHMENT_LOAD_OP_DONT_CARE, "stencilStoreOp" to VK_ATTACHMENT_STORE_OP_DONT_CARE,
            "initialLayout" to VK_IMAGE_LAYOUT_UNDEFINED, "finalLayout" to VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        val cr=s.struct(VkAttachmentReference, "attachment" to 0, "layout" to VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        val dr=s.struct(VkAttachmentReference, "attachment" to 1, "layout" to VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        val sub=s.struct(VkSubpassDescription, "pipelineBindPoint" to VK_PIPELINE_BIND_POINT_GRAPHICS,
            "colorAttachmentCount" to 1, "pColorAttachments" to cr, "pDepthStencilAttachment" to dr)
        val deps=s.structs(VkSubpassDependency,2)
        deps.at(VkSubpassDependency,0).put(VkSubpassDependency, "srcSubpass" to VK_SUBPASS_EXTERNAL, "dstSubpass" to 0,
            "srcStageMask" to VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            "dstStageMask" to (VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT),
            "dstAccessMask" to (VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT))
        deps.at(VkSubpassDependency,1).put(VkSubpassDependency, "srcSubpass" to 0, "dstSubpass" to VK_SUBPASS_EXTERNAL,
            "srcStageMask" to VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, "dstStageMask" to VK_PIPELINE_STAGE_TRANSFER_BIT,
            "srcAccessMask" to VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, "dstAccessMask" to VK_ACCESS_TRANSFER_READ_BIT)
        val p=s.allocate(JAVA_LONG)
        dev.createRenderPass(s.struct(VkRenderPassCreateInfo, "attachmentCount" to 2, "pAttachments" to a,
            "subpassCount" to 1, "pSubpasses" to sub, "dependencyCount" to 2, "pDependencies" to deps),p)
        renderPass=p.get(JAVA_LONG,0)
        dev.createFramebuffer(s.struct(VkFramebufferCreateInfo, "renderPass" to renderPass, "attachmentCount" to 2,
            "pAttachments" to s.longs(color!!.view,depth!!.view), "width" to largeur, "height" to hauteur, "layers" to 1),p)
        framebuffer=p.get(JAVA_LONG,0)
    }

    private fun shader(resource: String): Long = stack.frame { s ->
        val bytes=javaClass.getResourceAsStream(resource)?.use { it.readAllBytes() } ?: error("Shader absent : " + resource + "; lancer tools/shaders.sh")
        val code=s.allocate(bytes.size.toLong(),4); MemorySegment.copy(bytes,0,code,JAVA_BYTE,0,bytes.size)
        val p=s.allocate(JAVA_LONG)
        dev.createShaderModule(s.struct(VkShaderModuleCreateInfo, "codeSize" to bytes.size.toLong(), "pCode" to code),p)
        p.get(JAVA_LONG,0)
    }

    private fun createPipeline() = stack.frame { s ->
        val vert=shader("/shaders/toon.vert.spv"); val frag=shader("/shaders/toon.frag.spv")
        try {
            val stages=s.structs(VkPipelineShaderStageCreateInfo,2)
            stages.at(VkPipelineShaderStageCreateInfo,0).put(VkPipelineShaderStageCreateInfo,
                "stage" to VK_SHADER_STAGE_VERTEX_BIT, "module" to vert, "pName" to s.allocateFrom("main"))
            stages.at(VkPipelineShaderStageCreateInfo,1).put(VkPipelineShaderStageCreateInfo,
                "stage" to VK_SHADER_STAGE_FRAGMENT_BIT, "module" to frag, "pName" to s.allocateFrom("main"))
            val binding=s.struct(VkVertexInputBindingDescription, "binding" to 0, "stride" to 36, "inputRate" to VK_VERTEX_INPUT_RATE_VERTEX)
            val attrs=s.structs(VkVertexInputAttributeDescription,3)
            for(i in 0..2) attrs.at(VkVertexInputAttributeDescription,i).put(VkVertexInputAttributeDescription,
                "binding" to 0, "location" to i, "format" to VK_FORMAT_R32G32B32_SFLOAT, "offset" to i*12)
            val vi=s.struct(VkPipelineVertexInputStateCreateInfo, "vertexBindingDescriptionCount" to 1, "pVertexBindingDescriptions" to binding,
                "vertexAttributeDescriptionCount" to 3, "pVertexAttributeDescriptions" to attrs)
            val ia=s.struct(VkPipelineInputAssemblyStateCreateInfo, "topology" to VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
            val vp=s.struct(VkViewport, "x" to 0f, "y" to 0f, "width" to largeur.toFloat(), "height" to hauteur.toFloat(), "minDepth" to 0f, "maxDepth" to 1f)
            val sc=s.struct(VkRect2D, "extent.width" to largeur, "extent.height" to hauteur)
            val vs=s.struct(VkPipelineViewportStateCreateInfo, "viewportCount" to 1, "pViewports" to vp, "scissorCount" to 1, "pScissors" to sc)
            val rs=s.struct(VkPipelineRasterizationStateCreateInfo, "polygonMode" to VK_POLYGON_MODE_FILL, "cullMode" to VK_CULL_MODE_NONE,
                "frontFace" to VK_FRONT_FACE_COUNTER_CLOCKWISE, "lineWidth" to 1f)
            val ms=s.struct(VkPipelineMultisampleStateCreateInfo, "rasterizationSamples" to VK_SAMPLE_COUNT_1_BIT)
            val ds=s.struct(VkPipelineDepthStencilStateCreateInfo, "depthTestEnable" to true, "depthWriteEnable" to true,
                "depthCompareOp" to VK_COMPARE_OP_LESS_OR_EQUAL)
            val ca=s.struct(VkPipelineColorBlendAttachmentState, "colorWriteMask" to
                (VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT))
            val cb=s.struct(VkPipelineColorBlendStateCreateInfo, "attachmentCount" to 1, "pAttachments" to ca)
            val push=s.struct(VkPushConstantRange, "stageFlags" to VK_SHADER_STAGE_VERTEX_BIT, "offset" to 0, "size" to 128)
            val p=s.allocate(JAVA_LONG)
            dev.createPipelineLayout(s.struct(VkPipelineLayoutCreateInfo, "pushConstantRangeCount" to 1, "pPushConstantRanges" to push),p)
            layout=p.get(JAVA_LONG,0)
            val ci=s.struct(VkGraphicsPipelineCreateInfo, "stageCount" to 2, "pStages" to stages, "pVertexInputState" to vi,
                "pInputAssemblyState" to ia, "pViewportState" to vs, "pRasterizationState" to rs, "pMultisampleState" to ms,
                "pDepthStencilState" to ds, "pColorBlendState" to cb, "layout" to layout, "renderPass" to renderPass,
                "subpass" to 0, "basePipelineIndex" to -1)
            dev.createGraphicsPipelines(1,ci,p); pipeline=p.get(JAVA_LONG,0)
        } finally { dev.destroyShaderModule(vert); dev.destroyShaderModule(frag) }
    }

    fun recreateSwapchain(): Boolean {
        val source=target ?: return true
        if(surface == 0L) return true
        return stack.frame { s ->
            val (w,h)=source.framebufferSize().let { it[0] to it[1] }
            if(w==0 || h==0) return@frame false
            dev.deviceWaitIdle()
            val caps=s.struct(VkSurfaceCapabilitiesKHR); vk.getPhysicalDeviceSurfaceCapabilitiesKHR(physical,surface,caps)
            fun cap(field: String)=caps.u32(VkSurfaceCapabilitiesKHR,field)
            require(cap("supportedUsageFlags") and VK_IMAGE_USAGE_TRANSFER_DST_BIT != 0) { "Surface sans transfert destination" }
            val n=s.allocate(JAVA_INT); vk.getPhysicalDeviceSurfaceFormatsKHR(physical,surface,n,MemorySegment.NULL)
            val formats=s.structs(VkSurfaceFormatKHR,n.get(JAVA_INT,0)); vk.getPhysicalDeviceSurfaceFormatsKHR(physical,surface,n,formats)
            fun format(i: Int)=formats.at(VkSurfaceFormatKHR,i).u32(VkSurfaceFormatKHR,"format")
            val chosen=(0 until n.get(JAVA_INT,0)).firstOrNull { format(it)==VK_FORMAT_B8G8R8A8_UNORM } ?: 0
            val imageFormat=if(format(chosen)==VK_FORMAT_UNDEFINED) VK_FORMAT_B8G8R8A8_UNORM else format(chosen)
            swapWidth=if(cap("currentExtent.width")!=-1) cap("currentExtent.width") else w.coerceIn(cap("minImageExtent.width"),cap("maxImageExtent.width"))
            swapHeight=if(cap("currentExtent.height")!=-1) cap("currentExtent.height") else h.coerceIn(cap("minImageExtent.height"),cap("maxImageExtent.height"))
            var count=cap("minImageCount")+1
            if(cap("maxImageCount")>0) count=minOf(count,cap("maxImageCount"))
            val composite=listOf(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR)
                .first { cap("supportedCompositeAlpha") and it != 0 }
            val ci=s.struct(VkSwapchainCreateInfoKHR, "surface" to surface, "minImageCount" to count, "imageFormat" to imageFormat,
                "imageColorSpace" to formats.at(VkSurfaceFormatKHR,chosen).u32(VkSurfaceFormatKHR,"colorSpace"),
                "imageExtent.width" to swapWidth, "imageExtent.height" to swapHeight, "imageArrayLayers" to 1,
                "imageUsage" to VK_IMAGE_USAGE_TRANSFER_DST_BIT, "imageSharingMode" to VK_SHARING_MODE_EXCLUSIVE,
                "preTransform" to cap("currentTransform"), "compositeAlpha" to composite, "presentMode" to VK_PRESENT_MODE_FIFO_KHR,
                "clipped" to true, "oldSwapchain" to swapchain)
            val p=s.allocate(JAVA_LONG); dev.createSwapchainKHR(ci,p)
            if(swapchain!=0L) dev.destroySwapchainKHR(swapchain)
            swapchain=p.get(JAVA_LONG,0)
            dev.getSwapchainImagesKHR(swapchain,n,MemorySegment.NULL)
            val imgs=s.allocate(JAVA_LONG,n.get(JAVA_INT,0).toLong()); dev.getSwapchainImagesKHR(swapchain,n,imgs)
            swapImages=LongArray(n.get(JAVA_INT,0)) { imgs.getAtIndex(JAVA_LONG,it.toLong()) }
            true
        }
    }

    private fun barrier(image: Long, old: Int, new: Int, srcAccess: Int, dstAccess: Int, srcStage: Int, dstStage: Int, s: NativeStack) {
        val b=s.struct(VkImageMemoryBarrier, "oldLayout" to old, "newLayout" to new, "srcAccessMask" to srcAccess, "dstAccessMask" to dstAccess,
            "srcQueueFamilyIndex" to VK_QUEUE_FAMILY_IGNORED, "dstQueueFamilyIndex" to VK_QUEUE_FAMILY_IGNORED, "image" to image,
            "subresourceRange.aspectMask" to VK_IMAGE_ASPECT_COLOR_BIT, "subresourceRange.levelCount" to 1, "subresourceRange.layerCount" to 1)
        dev.cmdPipelineBarrier(command,srcStage,dstStage,0,0,MemorySegment.NULL,0,MemorySegment.NULL,1,b)
    }

    private fun MemorySegment.subresource(prefix: String, s: Struct) = put(s,
        "$prefix.aspectMask" to VK_IMAGE_ASPECT_COLOR_BIT, "$prefix.mipLevel" to 0, "$prefix.baseArrayLayer" to 0, "$prefix.layerCount" to 1)

    fun dessiner(objets: List<Objet>, camera: Mat4, hud: Maillage, capture: Path?=null) = stack.frame { s ->
        var index=-1
        if(surface!=0L) {
            val out=s.allocate(JAVA_INT)
            val result=dev.acquireNextImageKHR(swapchain,Long.MAX_VALUE,acquired,VK_NULL_HANDLE,out)
            if(result==VK_ERROR_OUT_OF_DATE_KHR) { recreateSwapchain(); return@frame }
            check(result==VK_SUCCESS || result==VK_SUBOPTIMAL_KHR) { "Acquisition image : " + result }
            index=out.get(JAVA_INT,0)
        }
        // La frame précédente est terminée avant toute écriture de mémoire hôte.
        for(o in objets) if(o.visible && o.maillage.sommets.isNotEmpty() && !meshes.containsKey(o.maillage)) {
            val b=buffer(o.maillage.sommets.size.toLong()*4,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
            upload(b,o.maillage.sommets); meshes[o.maillage]=b
        }
        upload(hudBuffer!!,hud.sommets)
        val readback=if(capture!=null) buffer(largeur.toLong()*hauteur*4,VK_BUFFER_USAGE_TRANSFER_DST_BIT) else null
        try {
            dev.resetCommandBuffer(command,0)
            dev.beginCommandBuffer(command,s.struct(VkCommandBufferBeginInfo, "flags" to VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))
            val clear=s.structs(VkClearValue,2)
            clear.at(VkClearValue,0).put(VkClearValue, "float32[0]" to .42f, "float32[1]" to .73f, "float32[2]" to .82f, "float32[3]" to 1f)
            clear.at(VkClearValue,1).put(VkClearDepthStencilValue, "depth" to 1f, "stencil" to 0)
            val ri=s.struct(VkRenderPassBeginInfo, "renderPass" to renderPass, "framebuffer" to framebuffer,
                "renderArea.extent.width" to largeur, "renderArea.extent.height" to hauteur, "clearValueCount" to 2, "pClearValues" to clear)
            dev.cmdBeginRenderPass(command,ri,VK_SUBPASS_CONTENTS_INLINE)
            dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline)
            val constants=s.allocate(JAVA_FLOAT,32)
            val values=FloatArray(32)
            val handle=s.allocate(JAVA_LONG); val offsets=s.longs(0L)
            fun draw(b: Buffer, mesh: Maillage, model: Mat4, vp: Mat4) {
                (vp*model).writeTo(values,0); model.writeTo(values,16)
                MemorySegment.copy(values,0,constants,JAVA_FLOAT,0,32)
                dev.cmdPushConstants(command,layout,VK_SHADER_STAGE_VERTEX_BIT,0,128,constants)
                handle.set(JAVA_LONG,0,b.buffer); dev.cmdBindVertexBuffers(command,0,1,handle,offsets)
                dev.cmdDraw(command,mesh.sommets.size/9,1,0,0)
            }
            for(o in objets) if(o.visible) meshes[o.maillage]?.let { draw(it,o.maillage,o.matrice(),camera) }
            draw(hudBuffer!!,hud,Mat4.IDENTITY,Mat4.IDENTITY)
            dev.cmdEndRenderPass(command)
            if(readback!=null) {
                val region=s.struct(VkBufferImageCopy, "imageExtent.width" to largeur, "imageExtent.height" to hauteur, "imageExtent.depth" to 1)
                    .subresource("imageSubresource",VkBufferImageCopy)
                dev.cmdCopyImageToBuffer(command,color!!.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,readback.buffer,1,region)
                val mb=s.struct(VkMemoryBarrier, "srcAccessMask" to VK_ACCESS_TRANSFER_WRITE_BIT, "dstAccessMask" to VK_ACCESS_HOST_READ_BIT)
                dev.cmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,mb,0,MemorySegment.NULL,0,MemorySegment.NULL)
            }
            if(index>=0) {
                val img=swapImages[index]
                barrier(img,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,0,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,s)
                val blit=s.struct(VkImageBlit, "srcOffsets[1].x" to largeur, "srcOffsets[1].y" to hauteur, "srcOffsets[1].z" to 1,
                    "dstOffsets[1].x" to swapWidth, "dstOffsets[1].y" to swapHeight, "dstOffsets[1].z" to 1)
                    .subresource("srcSubresource",VkImageBlit).subresource("dstSubresource",VkImageBlit)
                dev.cmdBlitImage(command,color!!.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,img,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,blit,VK_FILTER_NEAREST)
                barrier(img,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,VK_ACCESS_TRANSFER_WRITE_BIT,0,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,s)
            }
            dev.endCommandBuffer(command)
            val submit=s.struct(VkSubmitInfo, "commandBufferCount" to 1, "pCommandBuffers" to s.pointers(command))
            if(index>=0) submit.put(VkSubmitInfo, "waitSemaphoreCount" to 1, "pWaitSemaphores" to s.longs(acquired),
                "pWaitDstStageMask" to s.ints(VK_PIPELINE_STAGE_TRANSFER_BIT), "signalSemaphoreCount" to 1, "pSignalSemaphores" to s.longs(finished))
            dev.queueSubmit(queue,1,submit,VK_NULL_HANDLE)
            if(index>=0) {
                val pi=s.struct(VkPresentInfoKHR, "waitSemaphoreCount" to 1, "pWaitSemaphores" to s.longs(finished),
                    "swapchainCount" to 1, "pSwapchains" to s.longs(swapchain), "pImageIndices" to s.ints(index))
                val result=dev.queuePresentKHR(queue,pi)
                dev.queueWaitIdle(queue)
                if(result==VK_ERROR_OUT_OF_DATE_KHR || result==VK_SUBOPTIMAL_KHR) recreateSwapchain() else result.vkCheck("Présentation")
            } else dev.queueWaitIdle(queue)
            if(capture!=null && readback!=null) save(readback,capture)
        } finally { if(readback!=null) destroy(readback) }
    }

    private fun save(b: Buffer, destination: Path) = stack.frame { s ->
        val p=s.allocate(ADDRESS); dev.mapMemory(b.memory,0,b.size,p)
        try {
            val bytes=p.get(ADDRESS,0).reinterpret(b.size)
            val img=BufferedImage(largeur,hauteur,BufferedImage.TYPE_INT_RGB)
            for(y in 0 until hauteur) for(x in 0 until largeur) {
                val i=((y*largeur+x)*4).toLong()
                img.setRGB(x,y,((bytes.get(JAVA_BYTE,i).toInt() and 255) shl 16) or ((bytes.get(JAVA_BYTE,i+1).toInt() and 255) shl 8) or (bytes.get(JAVA_BYTE,i+2).toInt() and 255))
            }
            destination.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            check(ImageIO.write(img,"png",destination.toFile()))
            println("SILLAGE_CAPTURE=" + destination.toAbsolutePath())
        } finally { dev.unmapMemory(b.memory) }
    }
    private fun destroy(b: Buffer) { dev.destroyBuffer(b.buffer); dev.freeMemory(b.memory) }
    private fun destroy(i: Image) { dev.destroyImageView(i.view); dev.destroyImage(i.image); dev.freeMemory(i.memory) }

    override fun close() {
        if(ferme) return
        ferme=true
        try {
            if(::dev.isInitialized) {
                dev.deviceWaitIdle()
                meshes.values.forEach { destroy(it) }; meshes.clear()
                hudBuffer?.let { destroy(it) }; hudBuffer=null
                dev.destroyPipeline(pipeline)
                dev.destroyPipelineLayout(layout)
                dev.destroyFramebuffer(framebuffer)
                dev.destroyRenderPass(renderPass)
                color?.let { destroy(it) }; depth?.let { destroy(it) }; color=null; depth=null
                dev.destroySwapchainKHR(swapchain)
                dev.destroySemaphore(acquired)
                dev.destroySemaphore(finished)
                dev.destroyCommandPool(pool)
                dev.destroyDevice()
            }
            if(::vk.isInitialized) {
                if(surface!=0L) vk.destroySurfaceKHR(surface)
                if(messenger!=0L) vk.destroyDebugUtilsMessengerEXT(messenger)
                vk.destroyInstance()
            }
        } finally { arena.close() }
    }
}
